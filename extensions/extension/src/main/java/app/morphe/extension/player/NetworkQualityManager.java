package app.morphe.extension.player;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * Automatically detects Wi-Fi vs mobile data via {@link ConnectivityManager} and applies the
 * user's preferred stream quality preset to both Twitch's native Amazon IVS {@code MediaPlayer}
 * and Kizu's multi-stream {@code MultiStreamPlayer} instances.
 */
public final class NetworkQualityManager {
    private static final String TAG = "KizuNetQuality";
    private static final long NETWORK_DEBOUNCE_MS = 600L;
    private static final long NATIVE_POLL_INTERVAL_MS = 350L;
    private static final long NATIVE_SETTLE_CHECK_MS = 950L;
    private static final int NATIVE_MAX_POLL_ATTEMPTS = 22;

    public static final String[] PRESET_VALUES = {
            "auto",
            "source",
            "1080p",
            "720p",
            "480p",
            "360p",
            "160p",
            "audio_only"
    };

    public static final String[] PRESET_LABELS = {
            "自動 (Auto)",
            "最高画質 (ソース)",
            "1080p",
            "720p",
            "480p",
            "360p",
            "160p",
            "音声のみ"
    };

    public enum NetworkType {
        WIFI,
        MOBILE
    }

    public interface Listener {
        void onNetworkQualityChanged(NetworkType type, String preset);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean initialized;
    private static volatile NetworkType currentNetwork = NetworkType.WIFI;
    private static volatile WeakReference<Object> lastNativeIvsPlayer = new WeakReference<>(null);
    private static volatile int nativeApplyGeneration;
    private static volatile boolean pendingNetworkSwitchToast;

    private static final Runnable DEBOUNCED_DISPATCH = NetworkQualityManager::dispatchNetworkQualityNow;

    private NetworkQualityManager() {}

    public static void init(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        synchronized (NetworkQualityManager.class) {
            if (initialized) return;
            initialized = true;
            currentNetwork = detectNetworkType(appCtx);
            registerCallback(appCtx);
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        ensureInitialized();
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.remove(listener);
    }

    public static boolean isEnabled() {
        return Settings.AUTO_NETWORK_QUALITY.get();
    }

    public static NetworkType currentNetworkType() {
        ensureInitialized();
        Context ctx = Utils.getContext();
        if (ctx != null) {
            currentNetwork = detectNetworkType(ctx);
        }
        return currentNetwork;
    }

    public static String currentPreset() {
        NetworkType type = currentNetworkType();
        return presetFor(type);
    }

    public static String presetFor(NetworkType type) {
        String raw = (type == NetworkType.MOBILE ? Settings.MOBILE_QUALITY : Settings.WIFI_QUALITY).get();
        return normalizePreset(raw);
    }

    public static String labelForPreset(String preset) {
        int idx = indexForPreset(preset);
        return PRESET_LABELS[idx];
    }

    public static int indexForPreset(String preset) {
        String norm = normalizePreset(preset);
        for (int i = 0; i < PRESET_VALUES.length; i++) {
            if (PRESET_VALUES[i].equals(norm)) return i;
        }
        return 0;
    }

    public static String normalizePreset(String raw) {
        if (raw == null) return "auto";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (String v : PRESET_VALUES) {
            if (v.equals(lower)) return v;
        }
        return "auto";
    }

    /** Called when user updates auto-network quality toggle or Wi-Fi/Mobile presets in settings. */
    public static void onSettingsChanged() {
        ensureInitialized();
        if (!isEnabled()) return;
        pendingNetworkSwitchToast = false;
        MAIN.removeCallbacks(DEBOUNCED_DISPATCH);
        MAIN.post(DEBOUNCED_DISPATCH);
    }

    /**
     * Called from {@code BlockAdsPatch.preload(mediaPlayer, uri, listener)} whenever Twitch's
     * native Amazon IVS player begins loading a stream.
     */
    public static void onNativePlayerLoad(Object ivsMediaPlayer) {
        if (ivsMediaPlayer == null) return;
        ensureInitialized();
        lastNativeIvsPlayer = new WeakReference<>(ivsMediaPlayer);
        if (!isEnabled()) return;

        int gen = ++nativeApplyGeneration;
        scheduleNativeApply(ivsMediaPlayer, gen, 0, false);
    }

    /**
     * Applies a quality preset to any Amazon IVS Player/MediaPlayer instance.
     *
     * @param ivsPlayer    Amazon IVS Player / MediaPlayer instance
     * @param preset       One of {@link #PRESET_VALUES}
     * @param capMaxHeight Optional upper height cap (e.g. for multi-stream sub-slots); 0 = no cap
     * @return Applied quality label (e.g. "720p60", "Auto"), or {@code null} if qualities are not ready yet
     */
    public static String applyQualityToIvsPlayer(Object ivsPlayer, String preset, int capMaxHeight) {
        if (ivsPlayer == null) return null;
        String norm = normalizePreset(preset);
        try {
            Class<?> cls = ivsPlayer.getClass();
            Method getQualities = findMethod(cls, "getQualities");
            if (getQualities == null) return null;
            Object raw = getQualities.invoke(ivsPlayer);
            if (!(raw instanceof Collection<?> col) || col.isEmpty()) return null;

            List<IvsQuality> all = new ArrayList<>(col.size());
            List<IvsQuality> videoOnly = new ArrayList<>(col.size());
            IvsQuality audioOnly = null;

            for (Object q : col) {
                if (q == null) continue;
                String name = invokeString(q, "getName");
                int height = invokeInt(q, "getHeight");
                int bitrate = invokeInt(q, "getBitrate");
                String safeName = name == null ? "" : name;
                String label = !safeName.isEmpty()
                        ? safeName
                        : (height > 0 ? height + "p" : "音声のみ");
                IvsQuality item = new IvsQuality(q, safeName, label, height, bitrate);
                all.add(item);

                String lowerName = safeName.toLowerCase(Locale.ROOT);
                if (lowerName.contains("audio") || height <= 0) {
                    if (audioOnly == null || item.bitrate < audioOnly.bitrate) {
                        audioOnly = item;
                    }
                } else {
                    videoOnly.add(item);
                }
            }

            videoOnly.sort((a, b) -> {
                if (b.height != a.height) return Integer.compare(b.height, a.height);
                return Integer.compare(b.bitrate, a.bitrate);
            });

            Method setAutoQuality = findMethod(cls, "setAutoQualityMode", boolean.class);
            Method setQuality = findSingleArgMethod(cls, "setQuality");
            if (setQuality == null) return null;

            if ("auto".equals(norm)) {
                if (capMaxHeight > 0 && !videoOnly.isEmpty()) {
                    IvsQuality capped = pickMaxHeight(videoOnly, capMaxHeight);
                    return commitQuality(ivsPlayer, setAutoQuality, setQuality, capped);
                }
                if (setAutoQuality != null) {
                    setAutoQuality.invoke(ivsPlayer, Boolean.TRUE);
                    return "Auto";
                }
                return null;
            }

            if ("audio_only".equals(norm)) {
                IvsQuality chosen = audioOnly != null
                        ? audioOnly
                        : (!videoOnly.isEmpty() ? videoOnly.get(videoOnly.size() - 1) : all.get(0));
                return commitQuality(ivsPlayer, setAutoQuality, setQuality, chosen);
            }

            if (videoOnly.isEmpty()) {
                return commitQuality(ivsPlayer, setAutoQuality, setQuality, all.get(0));
            }

            if ("source".equals(norm)) {
                IvsQuality chosen = capMaxHeight > 0
                        ? pickMaxHeight(videoOnly, capMaxHeight)
                        : videoOnly.get(0);
                return commitQuality(ivsPlayer, setAutoQuality, setQuality, chosen);
            }

            int targetH = parseHeightPreset(norm);
            if (targetH <= 0) {
                if (setAutoQuality != null) {
                    setAutoQuality.invoke(ivsPlayer, Boolean.TRUE);
                    return "Auto";
                }
                return null;
            }
            int effectiveMax = capMaxHeight > 0 ? Math.min(targetH, capMaxHeight) : targetH;
            IvsQuality chosen = pickMaxHeight(videoOnly, effectiveMax);
            return commitQuality(ivsPlayer, setAutoQuality, setQuality, chosen);
        } catch (Throwable t) {
            Log.w(TAG, "applyQualityToIvsPlayer failed: " + t.getMessage());
            return null;
        }
    }

    private static IvsQuality pickMaxHeight(List<IvsQuality> sortedDescVideo, int maxHeight) {
        for (IvsQuality q : sortedDescVideo) {
            if (q.height > 0 && q.height <= maxHeight) {
                return q;
            }
        }
        return sortedDescVideo.get(sortedDescVideo.size() - 1);
    }

    private static String commitQuality(Object ivsPlayer, Method setAutoQuality, Method setQuality,
                                        IvsQuality chosen) throws Exception {
        if (chosen == null) return null;
        if (setAutoQuality != null) {
            try { setAutoQuality.invoke(ivsPlayer, Boolean.FALSE); } catch (Throwable ignored) {}
        }
        setQuality.invoke(ivsPlayer, chosen.raw);
        return chosen.label;
    }

    private static int parseHeightPreset(String preset) {
        switch (preset) {
            case "1080p": return 1080;
            case "720p": return 720;
            case "480p": return 480;
            case "360p": return 360;
            case "160p": return 160;
            default: return 0;
        }
    }

    private static void scheduleNativeApply(Object player, int gen, int attempt, boolean settlePass) {
        long delay = attempt == 0 ? 300L : (settlePass ? NATIVE_SETTLE_CHECK_MS : NATIVE_POLL_INTERVAL_MS);
        MAIN.postDelayed(() -> {
            if (gen != nativeApplyGeneration || !isEnabled()) return;
            if (lastNativeIvsPlayer.get() != player) return;
            String state = readIvsState(player);
            if ("ENDED".equals(state)) return;

            String preset = currentPreset();
            String applied = applyQualityToIvsPlayer(player, preset, 0);
            if (applied != null) {
                Log.i(TAG, "Applied native stream quality: " + applied
                        + " (preset=" + preset + ", network=" + currentNetwork + ")");
                // Perform one settle verification ~950ms later in case Twitch's own READY callback
                // overwrote the initial quality selection right as playback began.
                if (!settlePass) {
                    scheduleNativeApply(player, gen, attempt + 1, true);
                }
                return;
            }
            if (!settlePass && attempt + 1 < NATIVE_MAX_POLL_ATTEMPTS) {
                scheduleNativeApply(player, gen, attempt + 1, false);
            }
        }, delay);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        Context ctx = Utils.getContext();
        if (ctx != null) init(ctx);
    }

    private static void registerCallback(Context appCtx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handleNetworkCapabilitiesChanged(appCtx, cm.getNetworkCapabilities(network));
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    handleNetworkCapabilitiesChanged(appCtx, caps);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register network callback: " + t.getMessage());
        }
    }

    private static void handleNetworkCapabilitiesChanged(Context appCtx, NetworkCapabilities caps) {
        NetworkType detected = classifyCapabilities(appCtx, caps);
        if (detected == null || detected == currentNetwork) return;
        currentNetwork = detected;
        if (!isEnabled()) return;
        pendingNetworkSwitchToast = true;
        MAIN.removeCallbacks(DEBOUNCED_DISPATCH);
        MAIN.postDelayed(DEBOUNCED_DISPATCH, NETWORK_DEBOUNCE_MS);
    }

    private static void dispatchNetworkQualityNow() {
        if (!isEnabled()) return;
        NetworkType type = currentNetworkType();
        String preset = presetFor(type);
        boolean showNotice = pendingNetworkSwitchToast;
        pendingNetworkSwitchToast = false;

        boolean appliedAny = false;
        Object nativePlayer = lastNativeIvsPlayer.get();
        if (nativePlayer != null) {
            String state = readIvsState(nativePlayer);
            if ("PLAYING".equals(state) || "READY".equals(state) || "BUFFERING".equals(state)) {
                String res = applyQualityToIvsPlayer(nativePlayer, preset, 0);
                if (res != null) {
                    appliedAny = true;
                    Log.i(TAG, "Switched native stream quality on network change: " + res
                            + " (" + type + " -> " + preset + ")");
                }
            }
        }

        for (Listener listener : LISTENERS) {
            try {
                listener.onNetworkQualityChanged(type, preset);
                appliedAny = true;
            } catch (Throwable ignored) {}
        }

        if (showNotice && appliedAny && Utils.isAppInForeground()) {
            Activity act = Utils.getCurrentActivity();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                String netName = type == NetworkType.WIFI ? "Wi-Fi" : "モバイル回線";
                TwitchUi.showSnackbar(act,
                        netName + "を検知：画質を「" + labelForPreset(preset) + "」に切り替えました",
                        null, 2400L);
            }
        }
    }

    private static NetworkType detectNetworkType(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return NetworkType.WIFI;
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                NetworkType classified = classifyCapabilities(context, caps);
                if (classified != null) return classified;
            }
            return cm.isActiveNetworkMetered() ? NetworkType.MOBILE : NetworkType.WIFI;
        } catch (Throwable ignored) {
            return NetworkType.WIFI;
        }
    }

    private static NetworkType classifyCapabilities(Context context, NetworkCapabilities caps) {
        if (caps == null) return null;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return NetworkType.WIFI;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return NetworkType.MOBILE;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                return cm.isActiveNetworkMetered() ? NetworkType.MOBILE : NetworkType.WIFI;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readIvsState(Object ivsPlayer) {
        try {
            Method m = findMethod(ivsPlayer.getClass(), "getState");
            if (m == null) return "";
            Object st = m.invoke(ivsPlayer);
            return st == null ? "" : st.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findSingleArgMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String invokeString(Object obj, String name) {
        try {
            Method m = obj.getClass().getMethod(name);
            Object v = m.invoke(obj);
            return v instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int invokeInt(Object obj, String name) {
        try {
            Method m = obj.getClass().getMethod(name);
            Object v = m.invoke(obj);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final class IvsQuality {
        final Object raw;
        final String name;
        final String label;
        final int height;
        final int bitrate;

        IvsQuality(Object raw, String name, String label, int height, int bitrate) {
            this.raw = raw;
            this.name = name;
            this.label = label;
            this.height = height;
            this.bitrate = bitrate;
        }
    }
}
