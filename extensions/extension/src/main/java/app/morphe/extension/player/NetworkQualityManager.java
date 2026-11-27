package app.morphe.extension.player;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.Utils;
import app.morphe.extension.reload.NativeReloadOwner;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * Automatically detects Wi-Fi vs mobile data (including underneath VPNs and live Wi-Fi <-> cellular
 * handovers) and applies the user's preferred stream quality preset to both Twitch 31.3.1's native
 * player wrapper ({@code com.amazonaws.ivs.player.MediaPlayer} + wrapper quality state) and Kizu's
 * multi-stream {@code MultiStreamPlayer} instances.
 */
public final class NetworkQualityManager {
    private static final String TAG = "KizuNetQuality";
    private static final String IVS_PKG = "com.amazonaws.ivs.player.";

    private static final long NATIVE_POLL_INTERVAL_MS = 350L;
    private static final int NATIVE_MAX_POLL_ATTEMPTS = 24;

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
    private static final ConcurrentHashMap<Class<?>, Field> IVS_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> WRAPPER_QUALITY_METHOD_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean initialized;
    private static volatile NetworkType lastDispatchedNetwork;
    private static volatile WeakReference<Object> lastNativeWrapper = new WeakReference<>(null);
    private static volatile WeakReference<Object> lastNativeIvsPlayer = new WeakReference<>(null);
    private static volatile int streamLoadSeq = 1;
    private static volatile int lastAppliedStreamSeq = 0;
    private static volatile int activePollGeneration = 0;

    private static final Runnable CHECK_NET_250 = () -> evaluateAndDispatchNetworkChange(true);
    private static final Runnable CHECK_NET_900 = () -> evaluateAndDispatchNetworkChange(true);
    private static final Runnable CHECK_NET_1800 = () -> evaluateAndDispatchNetworkChange(true);

    private NetworkQualityManager() {}

    public static void init(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        synchronized (NetworkQualityManager.class) {
            if (initialized) return;
            initialized = true;
            lastDispatchedNetwork = detectNetworkType(appCtx);
            registerCallbacks(appCtx);
        }
    }

    public static void onActivityResumed() {
        ensureInitialized();
        if (!isEnabled()) return;
        MAIN.postDelayed(() -> evaluateAndDispatchNetworkChange(true), 250L);
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
        return ctx != null ? detectNetworkType(ctx) : (lastDispatchedNetwork != null ? lastDispatchedNetwork : NetworkType.WIFI);
    }

    public static String currentPreset() {
        return presetFor(currentNetworkType());
    }

    public static String presetFor(NetworkType type) {
        String raw = (type == NetworkType.MOBILE ? Settings.MOBILE_QUALITY : Settings.WIFI_QUALITY).get();
        return normalizePreset(raw);
    }

    public static String labelForPreset(String preset) {
        return PRESET_LABELS[indexForPreset(preset)];
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
        MAIN.post(() -> {
            NetworkType type = currentNetworkType();
            lastDispatchedNetwork = type;
            applyToAllActivePlayers(type, presetFor(type), false);
        });
    }

    /** Called from {@code BlockAdsPatch.overrideStreamUri} whenever a new live stream URL is built. */
    public static void onNewNativeStreamUri() {
        ensureInitialized();
        streamLoadSeq++;
        if (!isEnabled()) return;
        Object wrapper = lastNativeWrapper.get();
        Object ivs = lastNativeIvsPlayer.get();
        if (wrapper != null || ivs != null) {
            startNativeStartupPoll();
        }
    }

    /**
     * Called from {@code BlockAdsPatch.onPlayerEvent(player, ...)} on every native player event.
     * {@code player} is Twitch's native IVS player wrapper holding the live {@code MediaPlayer}.
     */
    public static void onNativePlayerEvent(Object playerWrapper) {
        if (playerWrapper == null) return;
        ensureInitialized();
        bindNativeCandidateInternal(playerWrapper);
    }

    /** Called from {@code NativeReloadViews.install} and {@code NativeReloadAction.bind}. */
    public static void bindNativeReloadCandidate(Object candidate) {
        if (candidate == null) return;
        ensureInitialized();
        Object unwrapped = candidate;
        try {
            if (candidate instanceof NativeReloadOwner owner) {
                Object inner = owner.reloadControlsOwner();
                if (inner != null) unwrapped = inner;
            }
        } catch (Throwable ignored) {}
        bindNativeCandidateInternal(unwrapped);
    }

    /** Legacy hook if {@code MediaPlayer.preload} is ever invoked directly. */
    public static void onNativePlayerLoad(Object ivsMediaPlayer) {
        if (ivsMediaPlayer == null) return;
        ensureInitialized();
        bindNativeCandidateInternal(ivsMediaPlayer);
    }

    private static void bindNativeCandidateInternal(Object candidate) {
        ResolvedNative resolved = resolveNativeIvs(candidate);
        if (resolved == null || resolved.ivsPlayer == null) return;

        Object prevIvs = lastNativeIvsPlayer.get();
        boolean newPlayerInstance = prevIvs != resolved.ivsPlayer;
        if (resolved.wrapper != null) {
            lastNativeWrapper = new WeakReference<>(resolved.wrapper);
        }
        lastNativeIvsPlayer = new WeakReference<>(resolved.ivsPlayer);

        if (!isEnabled()) return;
        if (newPlayerInstance || lastAppliedStreamSeq != streamLoadSeq) {
            lastAppliedStreamSeq = streamLoadSeq;
            startNativeStartupPoll();
        }
    }

    private static void startNativeStartupPoll() {
        final int gen = ++activePollGeneration;
        scheduleNativeStartupAttempt(gen, 0, 0);
    }

    private static void scheduleNativeStartupAttempt(int gen, int attempt, int verifyPass) {
        long delay = attempt == 0 ? 200L : (verifyPass == 1 ? 900L : (verifyPass == 2 ? 1300L : NATIVE_POLL_INTERVAL_MS));
        MAIN.postDelayed(() -> {
            if (gen != activePollGeneration || !isEnabled()) return;
            Object ivs = lastNativeIvsPlayer.get();
            Object wrapper = lastNativeWrapper.get();
            if (wrapper != null) {
                ResolvedNative refreshed = resolveNativeIvs(wrapper);
                if (refreshed != null && refreshed.ivsPlayer != null) {
                    ivs = refreshed.ivsPlayer;
                    lastNativeIvsPlayer = new WeakReference<>(ivs);
                }
            }
            if (ivs == null) return;

            String state = readIvsState(ivs);
            if ("ENDED".equals(state)) return;

            NetworkType net = currentNetworkType();
            lastDispatchedNetwork = net;
            String preset = presetFor(net);
            String applied = applyQualityToNativeAndIvs(wrapper, ivs, preset);
            if (applied != null) {
                if (verifyPass == 0) {
                    Log.i(TAG, "Applied native stream quality: " + applied
                            + " (preset=" + preset + ", network=" + net + ")");
                }
                if (verifyPass < 2) {
                    scheduleNativeStartupAttempt(gen, attempt + 1, verifyPass + 1);
                }
                return;
            }
            if (verifyPass == 0 && attempt + 1 < NATIVE_MAX_POLL_ATTEMPTS) {
                scheduleNativeStartupAttempt(gen, attempt + 1, 0);
            }
        }, delay);
    }

    /**
     * Applies a quality preset to any Amazon IVS Player/MediaPlayer instance.
     */
    public static String applyQualityToIvsPlayer(Object ivsPlayer, String preset, int capMaxHeight) {
        return applyQualityInternal(null, ivsPlayer, preset, capMaxHeight);
    }

    private static String applyQualityToNativeAndIvs(Object wrapper, Object ivsPlayer, String preset) {
        return applyQualityInternal(wrapper, ivsPlayer, preset, 0);
    }

    private static String applyQualityInternal(Object wrapper, Object ivsPlayer, String preset, int capMaxHeight) {
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
                    return commitQuality(wrapper, ivsPlayer, setAutoQuality, setQuality, capped);
                }
                invokeWrapperQualitySetter(wrapper, "auto");
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
                return commitQuality(wrapper, ivsPlayer, setAutoQuality, setQuality, chosen);
            }

            if (videoOnly.isEmpty()) {
                return commitQuality(wrapper, ivsPlayer, setAutoQuality, setQuality, all.get(0));
            }

            if ("source".equals(norm)) {
                IvsQuality chosen = capMaxHeight > 0
                        ? pickMaxHeight(videoOnly, capMaxHeight)
                        : videoOnly.get(0);
                return commitQuality(wrapper, ivsPlayer, setAutoQuality, setQuality, chosen);
            }

            int targetH = parseHeightPreset(norm);
            if (targetH <= 0) {
                invokeWrapperQualitySetter(wrapper, "auto");
                if (setAutoQuality != null) {
                    setAutoQuality.invoke(ivsPlayer, Boolean.TRUE);
                    return "Auto";
                }
                return null;
            }
            int effectiveMax = capMaxHeight > 0 ? Math.min(targetH, capMaxHeight) : targetH;
            IvsQuality chosen = pickMaxHeight(videoOnly, effectiveMax);
            return commitQuality(wrapper, ivsPlayer, setAutoQuality, setQuality, chosen);
        } catch (Throwable t) {
            Log.w(TAG, "applyQualityInternal failed: " + t.getMessage());
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

    private static String commitQuality(Object wrapper, Object ivsPlayer,
                                        Method setAutoQuality, Method setQuality,
                                        IvsQuality chosen) throws Exception {
        if (chosen == null) return null;
        if (!chosen.name.isEmpty()) {
            invokeWrapperQualitySetter(wrapper, chosen.name);
        }
        if (setAutoQuality != null) {
            try { setAutoQuality.invoke(ivsPlayer, Boolean.FALSE); } catch (Throwable ignored) {}
        }
        setQuality.invoke(ivsPlayer, chosen.raw);
        return chosen.label;
    }

    /**
     * Updates Twitch's own wrapper quality state via its unique {@code (String, boolean)V} method
     * so Twitch's UI gear menu and presenter state stay synchronized and never revert IVS quality.
     */
    private static void invokeWrapperQualitySetter(Object wrapper, String qualityName) {
        if (wrapper == null || qualityName == null || qualityName.isEmpty()) return;
        try {
            Class<?> cls = wrapper.getClass();
            Method setter = WRAPPER_QUALITY_METHOD_CACHE.get(cls);
            if (setter == null && !WRAPPER_QUALITY_METHOD_CACHE.containsKey(cls)) {
                Method found = findUniqueWrapperQualityMethod(cls);
                if (found != null) {
                    WRAPPER_QUALITY_METHOD_CACHE.put(cls, found);
                    setter = found;
                }
            }
            if (setter != null) {
                setter.invoke(wrapper, qualityName, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}
    }

    private static Method findUniqueWrapperQualityMethod(Class<?> startClass) {
        List<Method> matches = new ArrayList<>(2);
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    matches.add(m);
                }
            }
            if (!matches.isEmpty()) break;
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static ResolvedNative resolveNativeIvs(Object candidate) {
        if (candidate == null) return null;
        if (candidate.getClass().getName().startsWith(IVS_PKG)) {
            return new ResolvedNative(null, candidate);
        }
        // Depth 1: candidate is Twitch's IVS wrapper holding a MediaPlayer field directly.
        Object directIvs = readIvsField(candidate);
        if (directIvs != null) {
            return new ResolvedNative(candidate, directIvs);
        }
        // Depth 2–3: candidate is VerticalTheatre VM / controller -> player -> wrapper -> MediaPlayer.
        try {
            for (Class<?> c = candidate.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    String tn = f.getType().getName();
                    if (tn.startsWith("java.") || tn.startsWith("android.") || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object child = f.get(candidate);
                    if (child == null) continue;
                    Object ivs1 = readIvsField(child);
                    if (ivs1 != null) return new ResolvedNative(child, ivs1);
                    for (Field f2 : child.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(f2.getModifiers())) continue;
                        String tn2 = f2.getType().getName();
                        if (tn2.startsWith("java.") || tn2.startsWith("android.") || f2.getType().isPrimitive()) continue;
                        f2.setAccessible(true);
                        Object grandChild = f2.get(child);
                        if (grandChild == null) continue;
                        Object ivs2 = readIvsField(grandChild);
                        if (ivs2 != null) return new ResolvedNative(grandChild, ivs2);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object readIvsField(Object owner) {
        if (owner == null) return null;
        try {
            Class<?> cls = owner.getClass();
            Field cached = IVS_FIELD_CACHE.get(cls);
            if (cached != null) {
                return cached.get(owner);
            }
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType().getName().startsWith(IVS_PKG)) {
                        f.setAccessible(true);
                        IVS_FIELD_CACHE.put(cls, f);
                        return f.get(owner);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
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

    private static void ensureInitialized() {
        if (initialized) return;
        Context ctx = Utils.getContext();
        if (ctx != null) init(ctx);
    }

    private static void registerCallbacks(Context appCtx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleStaggeredNetworkChecks();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleStaggeredNetworkChecks();
                }

                @Override
                public void onLost(Network network) {
                    scheduleStaggeredNetworkChecks();
                }
            };

            cm.registerDefaultNetworkCallback(cb);

            // Also watch physical internet-capable interfaces directly so Wi-Fi <-> Cellular
            // switches are detected even while a VPN TUN interface stays active as the default network.
            NetworkRequest physicalReq = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(physicalReq, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleStaggeredNetworkChecks();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleStaggeredNetworkChecks();
                }

                @Override
                public void onLost(Network network) {
                    scheduleStaggeredNetworkChecks();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register network callbacks: " + t.getMessage());
        }
    }

    private static void scheduleStaggeredNetworkChecks() {
        MAIN.removeCallbacks(CHECK_NET_250);
        MAIN.removeCallbacks(CHECK_NET_900);
        MAIN.removeCallbacks(CHECK_NET_1800);
        MAIN.postDelayed(CHECK_NET_250, 250L);
        MAIN.postDelayed(CHECK_NET_900, 900L);
        MAIN.postDelayed(CHECK_NET_1800, 1800L);
    }

    private static void evaluateAndDispatchNetworkChange(boolean showSnackbar) {
        Context ctx = Utils.getContext();
        if (ctx == null) return;
        NetworkType detected = detectNetworkType(ctx);
        if (detected == lastDispatchedNetwork) return;
        lastDispatchedNetwork = detected;
        if (!isEnabled()) return;

        String preset = presetFor(detected);
        applyToAllActivePlayers(detected, preset, showSnackbar);
    }

    private static void applyToAllActivePlayers(NetworkType type, String preset, boolean showSnackbar) {
        boolean appliedAny = false;

        Object wrapper = lastNativeWrapper.get();
        Object ivs = lastNativeIvsPlayer.get();
        if (wrapper != null) {
            ResolvedNative refreshed = resolveNativeIvs(wrapper);
            if (refreshed != null && refreshed.ivsPlayer != null) {
                ivs = refreshed.ivsPlayer;
                lastNativeIvsPlayer = new WeakReference<>(ivs);
            }
        }
        if (ivs != null) {
            String state = readIvsState(ivs);
            if ("PLAYING".equals(state) || "READY".equals(state) || "BUFFERING".equals(state)) {
                String res = applyQualityToNativeAndIvs(wrapper, ivs, preset);
                if (res != null) {
                    appliedAny = true;
                    Log.i(TAG, "Switched native stream quality: " + res
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

        if (showSnackbar && appliedAny && Utils.isAppInForeground()) {
            Activity act = Utils.getCurrentActivity();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                String netName = type == NetworkType.WIFI ? "Wi-Fi" : "モバイル回線";
                TwitchUi.showSnackbar(act,
                        netName + "を検知：画質を「" + labelForPreset(preset) + "」に切り替えました",
                        null, 2400L);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static NetworkType detectNetworkType(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return NetworkType.WIFI;

            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities activeCaps = cm.getNetworkCapabilities(active);
                if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return NetworkType.WIFI;
                    }
                    if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return NetworkType.MOBILE;
                    }
                }
            }

            // Active network is a VPN or ambiguous: inspect physical internet-capable networks.
            boolean sawWifi = false;
            boolean sawCellular = false;
            Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (Network net : all) {
                    if (net == null) continue;
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        sawWifi = true;
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        sawCellular = true;
                    }
                }
            }
            if (sawWifi) return NetworkType.WIFI;
            if (sawCellular) return NetworkType.MOBILE;

            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                int t = info.getType();
                if (t == ConnectivityManager.TYPE_WIFI || t == ConnectivityManager.TYPE_ETHERNET) {
                    return NetworkType.WIFI;
                }
                if (t == ConnectivityManager.TYPE_MOBILE) {
                    return NetworkType.MOBILE;
                }
            }
            return cm.isActiveNetworkMetered() ? NetworkType.MOBILE : NetworkType.WIFI;
        } catch (Throwable ignored) {
            return NetworkType.WIFI;
        }
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

    private static final class ResolvedNative {
        final Object wrapper;
        final Object ivsPlayer;

        ResolvedNative(Object wrapper, Object ivsPlayer) {
            this.wrapper = wrapper;
            this.ivsPlayer = ivsPlayer;
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
