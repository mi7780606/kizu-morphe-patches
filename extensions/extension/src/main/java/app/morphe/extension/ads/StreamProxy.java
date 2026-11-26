package app.morphe.extension.ads;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * Loads live streams through the proxy the user entered, if any. The proxy returns the stream's
 * playlist; the video itself still comes from Twitch.
 *
 * Subscribed / Turbo / ad-free streams automatically bypass any configured proxy so account
 * entitlements remain intact.
 */
public final class StreamProxy {
    private static final String DEFAULT_QUERY = "?allow_source=true&allow_audio_only=true&fast_bread=true";
    private static final String CHANNEL_PLACEHOLDER = "{channel}";
    private static final String PLAYLIST_SUFFIX = ".m3u8";
    private static final int MAX_PENDING = 16;

    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String GQL_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final String SUB_STATUS_QUERY =
            "query KizuSubStatus($login: String!) { user(login: $login) { id self { subscriptionBenefit { id tier } } } }";
    private static final long SUB_CACHE_TTL_MS = 10L * 60_000L;
    private static final long SUB_RETRY_TTL_MS = 30_000L;
    private static final int SUB_CACHE_MAX = 64;
    private static final int GQL_CONNECT_TIMEOUT_MS = 1500;
    private static final int GQL_READ_TIMEOUT_MS = 2000;
    private static final int GQL_MAX_RESPONSE_BYTES = 128 * 1024;
    private static final long WAIT_MAIN_MS = 600L;
    private static final long WAIT_BG_MS = 1500L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<Class<?>, Method> MESSAGE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, SubCacheEntry> SUB_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FutureTask<Boolean>> IN_FLIGHT_SUB_CHECKS =
            new ConcurrentHashMap<>();
    private static final Object SUB_CHECK_LOCK = new Object();

    private static final class SubCacheEntry {
        final boolean subscribed;
        final long expiresAtMs;

        SubCacheEntry(boolean subscribed, long expiresAtMs) {
            this.subscribed = subscribed;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final Map<String, Uri> originals = Collections.synchronizedMap(
            new LinkedHashMap<String, Uri>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Uri> eldest) {
                    return size() > MAX_PENDING;
                }
            });

    private StreamProxy() {}

    /**
     * Resolves a Twitch usher playlist URI for multi-stream playback, honoring ad-block proxy
     * settings while automatically bypassing the proxy on subscribed / Turbo streams.
     */
    public static Uri resolveForMultiView(Uri usherUri) {
        if (usherUri == null) return null;
        try {
            if (!Settings.BLOCK_ADS.get()) return usherUri;
            return override(usherUri);
        } catch (Throwable ignored) {
            return usherUri;
        }
    }

    /**
     * Returns true when an external stream proxy URL is configured (non-empty and not "off").
     */
    public static boolean hasExternalProxyConfigured() {
        try {
            String proxy = Settings.ADS_PROXY_URL.get().trim();
            return !proxy.isEmpty() && !"off".equalsIgnoreCase(proxy);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns true when the given usher URI or channel already has ad-free entitlement
     * (channel subscription, Twitch Turbo, privileged role, or disabled server ads).
     */
    public static boolean isAdFreeStream(Uri usherUri, String rawChannel) {
        try {
            String normalized = rawChannel == null ? "" : rawChannel.trim().toLowerCase(Locale.ROOT);
            if (hasAdFreeToken(usherUri, normalized)) {
                return true;
            }
            return !normalized.isEmpty() && isChannelSubscribed(normalized);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Pre-warms the GQL channel subscription cache when switching to a channel so stream setup
     * sees the cached entitlement without waiting on the network.
     */
    public static void prefetchSubscription(String channelLogin) {
        try {
            if (!Settings.BLOCK_ADS.get()) return;
            String proxy = Settings.ADS_PROXY_URL.get().trim();
            if (proxy.isEmpty() || "off".equalsIgnoreCase(proxy)) return;
            if (channelLogin == null) return;
            String normalized = channelLogin.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) return;

            long now = System.currentTimeMillis();
            SubCacheEntry cached = SUB_CACHE.get(normalized);
            if (cached != null && now < cached.expiresAtMs) return;

            String authToken = KizuRuntime.readAuthToken(Utils.getContext());
            if (authToken.isEmpty()) return;
            submitSubCheck(normalized, authToken);
        } catch (Throwable ignored) {}
    }

    static Uri override(Uri usherUri) {
        String proxy = Settings.ADS_PROXY_URL.get().trim();
        if (proxy.isEmpty() || "off".equalsIgnoreCase(proxy)) {
            return usherUri;
        }
        String channel = usherUri.getLastPathSegment();
        if (channel == null || !channel.endsWith(PLAYLIST_SUFFIX)) {
            Utils.logInfo("Proxy not used, no channel in " + usherUri.getPath());
            return usherUri;
        }
        channel = channel.substring(0, channel.length() - PLAYLIST_SUFFIX.length());
        if (channel.isEmpty()) return usherUri;

        String normalizedChannel = channel.toLowerCase(Locale.ROOT);
        if (hasAdFreeToken(usherUri, normalizedChannel)) {
            return usherUri;
        }
        if (isChannelSubscribed(normalizedChannel)) {
            Utils.logInfo("Proxy skipped: active channel subscription verified via GQL for " + normalizedChannel);
            return usherUri;
        }

        String name = Uri.encode(normalizedChannel);
        String url = proxy.contains(CHANNEL_PLACEHOLDER)
                ? proxy.replace(CHANNEL_PLACEHOLDER, name)
                : proxy + name + DEFAULT_QUERY;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            Utils.logInfo("Proxy URL is not a web address: " + proxy);
            return usherUri;
        }

        originals.put(uri.toString(), usherUri);
        Utils.logInfo("Loading " + normalizedChannel + " through the proxy " + uri.getHost());
        return uri;
    }

    /**
     * Checks whether Twitch's own signed access token in the usher URI already grants ad-free
     * playback (channel subscription, Twitch Turbo, privileged role, or disabled server ads).
     */
    private static boolean hasAdFreeToken(Uri usherUri, String channel) {
        if (usherUri == null) return false;
        try {
            String primary = usherUri.getQueryParameter("token");
            JSONObject tokenJson = parseTokenJson(primary);
            if (tokenJson == null) {
                Set<String> names = usherUri.getQueryParameterNames();
                if (names != null) {
                    for (String paramName : names) {
                        if ("token".equals(paramName)) continue;
                        tokenJson = parseTokenJson(usherUri.getQueryParameter(paramName));
                        if (tokenJson != null) break;
                    }
                }
            }
            if (tokenJson == null) return false;

            boolean subscriber = tokenJson.optBoolean("subscriber", false);
            boolean hideAds = tokenJson.optBoolean("hide_ads", false);
            boolean turbo = tokenJson.optBoolean("turbo", false);
            boolean privileged = tokenJson.optBoolean("privileged", false);
            boolean adFree = tokenJson.optBoolean("ad_free", false);
            boolean showAdsOff = tokenJson.has("show_ads") && !tokenJson.optBoolean("show_ads", true);
            boolean serverAdsOff = tokenJson.has("server_ads") && !tokenJson.optBoolean("server_ads", true);

            String role = tokenJson.optString("role", "").trim().toLowerCase(Locale.ROOT);
            boolean privilegedRole = "broadcaster".equals(role)
                    || "staff".equals(role)
                    || "admin".equals(role)
                    || "global_mod".equals(role);

            boolean chanSubActive = false;
            JSONObject chansub = tokenJson.optJSONObject("chansub");
            if (chansub != null) {
                if (chansub.optBoolean("subscribed", false)
                        || chansub.optBoolean("is_sub", false)
                        || !chansub.optString("tier", "").trim().isEmpty()) {
                    chanSubActive = true;
                }
            }

            if (subscriber || hideAds || turbo || privileged || adFree
                    || showAdsOff || serverAdsOff || privilegedRole || chanSubActive) {
                Utils.logInfo("Proxy skipped: ad-free token for " + channel
                        + " (subscriber=" + subscriber
                        + ", hide_ads=" + hideAds
                        + ", turbo=" + turbo
                        + ", show_ads_off=" + showAdsOff
                        + ", server_ads_off=" + serverAdsOff
                        + ", chansub=" + chanSubActive + ")");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static JSONObject parseTokenJson(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim();
        if (candidate.isEmpty()) return null;
        for (int pass = 0; pass < 2; pass++) {
            if (candidate.startsWith("{")) {
                try {
                    return new JSONObject(candidate);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            if (looksLikeJwt(candidate)) {
                JSONObject jwtPayload = decodeJwtPayload(candidate);
                if (jwtPayload != null) return jwtPayload;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.contains("%7b") || lower.contains("%22") || lower.contains("%2e")) {
                candidate = Uri.decode(candidate).trim();
            } else {
                break;
            }
        }
        return null;
    }

    private static boolean looksLikeJwt(String s) {
        int firstDot = s.indexOf('.');
        if (firstDot <= 0) return false;
        int secondDot = s.indexOf('.', firstDot + 1);
        return secondDot > firstDot + 1;
    }

    private static JSONObject decodeJwtPayload(String jwt) {
        try {
            int firstDot = jwt.indexOf('.');
            int secondDot = jwt.indexOf('.', firstDot + 1);
            if (firstDot <= 0 || secondDot <= firstDot + 1) return null;
            String payloadB64 = jwt.substring(firstDot + 1, secondDot);
            byte[] decoded = Base64.decode(payloadB64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String jsonStr = new String(decoded, StandardCharsets.UTF_8).trim();
            if (!jsonStr.startsWith("{")) return null;
            return new JSONObject(jsonStr);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isChannelSubscribed(String normalizedChannel) {
        try {
            long now = System.currentTimeMillis();
            SubCacheEntry cached = SUB_CACHE.get(normalizedChannel);
            if (cached != null && now < cached.expiresAtMs) {
                return cached.subscribed;
            }
            String authToken = KizuRuntime.readAuthToken(Utils.getContext());
            if (authToken.isEmpty()) return false;

            FutureTask<Boolean> task = submitSubCheck(normalizedChannel, authToken);
            long timeoutMs = Looper.myLooper() == Looper.getMainLooper() ? WAIT_MAIN_MS : WAIT_BG_MS;
            Boolean result = task.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static FutureTask<Boolean> submitSubCheck(String normalizedChannel, String authToken) {
        FutureTask<Boolean> task;
        boolean shouldExecute = false;
        synchronized (SUB_CHECK_LOCK) {
            FutureTask<Boolean> existing = IN_FLIGHT_SUB_CHECKS.get(normalizedChannel);
            if (existing != null) {
                task = existing;
            } else {
                final AtomicReference<FutureTask<Boolean>> holder = new AtomicReference<>();
                task = new FutureTask<>(() -> {
                    try {
                        return fetchSubscriptionBenefit(normalizedChannel, authToken);
                    } finally {
                        FutureTask<Boolean> self = holder.get();
                        if (self != null) {
                            IN_FLIGHT_SUB_CHECKS.remove(normalizedChannel, self);
                        }
                    }
                });
                holder.set(task);
                IN_FLIGHT_SUB_CHECKS.put(normalizedChannel, task);
                shouldExecute = true;
            }
        }
        if (shouldExecute) {
            KizuRuntime.ioExecutor().execute(task);
        }
        return task;
    }

    private static boolean fetchSubscriptionBenefit(String normalizedChannel, String authToken) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject()
                    .put("operationName", "KizuSubStatus")
                    .put("variables", new JSONObject().put("login", normalizedChannel))
                    .put("query", SUB_STATUS_QUERY);

            connection = (HttpURLConnection) new URL(GQL_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(GQL_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(GQL_READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Client-ID", GQL_CLIENT_ID);
            connection.setRequestProperty("Authorization", "OAuth " + authToken);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                cacheSubscription(normalizedChannel, false, SUB_RETRY_TTL_MS);
                return false;
            }

            String raw;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > GQL_MAX_RESPONSE_BYTES) {
                        cacheSubscription(normalizedChannel, false, SUB_RETRY_TTL_MS);
                        return false;
                    }
                    output.write(buffer, 0, read);
                }
                raw = output.toString(StandardCharsets.UTF_8.name());
            }

            JSONObject json = new JSONObject(raw);
            JSONObject data = json.optJSONObject("data");
            JSONObject user = data == null ? null : data.optJSONObject("user");
            JSONObject self = user == null ? null : user.optJSONObject("self");
            if (self == null) {
                cacheSubscription(normalizedChannel, false, SUB_RETRY_TTL_MS);
                return false;
            }

            JSONObject sub = self.optJSONObject("subscriptionBenefit");
            boolean subscribed = sub != null
                    && (!sub.optString("id", "").trim().isEmpty()
                    || !sub.optString("tier", "").trim().isEmpty());
            cacheSubscription(normalizedChannel, subscribed, SUB_CACHE_TTL_MS);
            return subscribed;
        } catch (Throwable ignored) {
            cacheSubscription(normalizedChannel, false, SUB_RETRY_TTL_MS);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void cacheSubscription(String normalizedChannel, boolean subscribed, long ttlMs) {
        long now = System.currentTimeMillis();
        if (SUB_CACHE.size() >= SUB_CACHE_MAX) {
            Iterator<Map.Entry<String, SubCacheEntry>> it = SUB_CACHE.entrySet().iterator();
            while (it.hasNext()) {
                SubCacheEntry entry = it.next().getValue();
                if (entry == null || now >= entry.expiresAtMs) {
                    it.remove();
                }
            }
            if (SUB_CACHE.size() >= SUB_CACHE_MAX) {
                SUB_CACHE.clear();
            }
        }
        SUB_CACHE.put(normalizedChannel, new SubCacheEntry(subscribed, now + ttlMs));
    }

    static void preload(Object mediaPlayer, Uri uri, Object listener) {
        Object used = listener;
        try {
            Uri original = uri == null ? null : originals.remove(uri.toString());
            if (original != null && listener != null) {
                Object fallback = fallbackListener(mediaPlayer, original, listener);
                if (fallback != null) used = fallback;
            }
        } catch (Exception ex) {
            Utils.logError("Block ads: failed to prepare the proxy fallback", ex);
        }
        PlayerEvents.preload(mediaPlayer, uri, used);
    }

    private static Object fallbackListener(Object mediaPlayer, Uri original, Object listener) {
        Class<?>[] interfaces = listener.getClass().getInterfaces();
        if (interfaces.length == 0) return null;
        return Proxy.newProxyInstance(listener.getClass().getClassLoader(), interfaces, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args, listener);
            if (method.getName().equals("onError")) {
                Utils.logInfo("Proxy failed: " + errorMessage(args));
                MAIN_HANDLER.post(() -> {
                    Activity activity = Utils.getCurrentActivity();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        TwitchUi.showSnackbar(activity, "Proxy failed", null, 2000L);
                    } else {
                        Context context = Utils.getContext();
                        if (context != null) Toast.makeText(context, "Proxy failed", Toast.LENGTH_SHORT).show();
                    }
                    PlayerEvents.preload(mediaPlayer, original, listener);
                });
                return null;
            }
            try {
                return method.invoke(listener, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        });
    }

    private static String errorMessage(Object[] args) {
        Object error = args == null || args.length == 0 ? null : args[0];
        if (error == null) return "unknown error";
        try {
            Class<?> type = error.getClass();
            Method m = MESSAGE_METHODS.get(type);
            if (m == null) {
                m = type.getMethod("getMessage");
                MESSAGE_METHODS.putIfAbsent(type, m);
            }
            return String.valueOf(m.invoke(error));
        } catch (Exception ex) {
            return error.toString();
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, Object listener) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1 && args[0] == proxy;
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "uyu proxy fallback for " + listener;
        }
    }
}
