package app.morphe.extension.ads;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * Loads live streams through the proxy the user entered, if any. The proxy returns the stream's
 * playlist; the video itself still comes from Twitch.
 */
final class StreamProxy {
    private static final String DEFAULT_QUERY = "?allow_source=true&allow_audio_only=true&fast_bread=true";
    private static final String CHANNEL_PLACEHOLDER = "{channel}";
    private static final String PLAYLIST_SUFFIX = ".m3u8";
    private static final int MAX_PENDING = 16;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<Class<?>, Method> MESSAGE_METHODS = new ConcurrentHashMap<>();

    private static final Map<String, Uri> originals = Collections.synchronizedMap(
            new LinkedHashMap<String, Uri>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Uri> eldest) {
                    return size() > MAX_PENDING;
                }
            });

    private StreamProxy() {}

    static Uri override(Uri usherUri) {
        String proxy = Settings.ADS_PROXY_URL.get().trim();
        if (proxy.isEmpty() || "off".equalsIgnoreCase(proxy)) {
            return usherUri;
        }
        if (hasAdFreeToken(usherUri)) {
            Utils.logInfo("Proxy skipped: stream token is already ad-free (subscriber/Turbo)");
            return usherUri;
        }
        String channel = usherUri.getLastPathSegment();
        if (channel == null || !channel.endsWith(PLAYLIST_SUFFIX)) {
            Utils.logInfo("Proxy not used, no channel in " + usherUri.getPath());
            return usherUri;
        }
        channel = channel.substring(0, channel.length() - PLAYLIST_SUFFIX.length());
        if (channel.isEmpty()) return usherUri;

        String name = Uri.encode(channel.toLowerCase(Locale.ROOT));
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
        Utils.logInfo("Loading " + channel + " through the proxy " + uri.getHost());
        return uri;
    }

    /**
     * Checks whether Twitch's own signed access token in the usher URI already grants ad-free
     * playback (e.g. channel subscription, Twitch Turbo, or privileged access). Routing such
     * streams through an anonymous public proxy drops the user's entitlement and can cause ads.
     */
    private static boolean hasAdFreeToken(Uri usherUri) {
        if (usherUri == null) return false;
        try {
            String token = usherUri.getQueryParameter("token");
            if (token == null || token.isEmpty()) return false;
            String trimmed = token.trim();
            if (trimmed.startsWith("%7B") || trimmed.startsWith("%7b")) {
                trimmed = Uri.decode(trimmed);
            }
            if (!trimmed.startsWith("{")) return false;
            JSONObject json = new JSONObject(trimmed);
            if (json.optBoolean("hide_ads", false)) return true;
            if (json.optBoolean("turbo", false)) return true;
            if (json.optBoolean("privileged", false)) return true;
            if (json.has("show_ads") && !json.optBoolean("show_ads", true)) return true;
            if (json.has("server_ads") && !json.optBoolean("server_ads", true)) return true;
        } catch (Throwable ignored) {
        }
        return false;
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
