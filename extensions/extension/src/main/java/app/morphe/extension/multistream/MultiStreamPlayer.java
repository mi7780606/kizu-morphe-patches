package app.morphe.extension.multistream;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight video player for multi-stream slots backed by Twitch 31.3.1's bundled unobfuscated
 * Amazon IVS Player ({@code com.amazonaws.ivs.player.Player}) via reflection, falling back to
 * {@link android.media.MediaPlayer} if unavailable.
 */
final class MultiStreamPlayer implements TextureView.SurfaceTextureListener {
    private static final String TAG = "KizuMultiPlayer";
    private static final long POLL_FAST_MS = 250L;
    private static final long POLL_SLOW_MS = 2000L;
    private static final long PROXY_FALLBACK_TIMEOUT_MS = 9000L;

    interface Listener {
        void onStateChanged(boolean loading, String errorMessage);
        void onQualityChanged(String qualityLabel);
    }

    static final class QualityOption {
        final String label;
        final int height;
        final int bitrate;
        final Object rawQuality;

        QualityOption(String label, int height, int bitrate, Object rawQuality) {
            this.label = label;
            this.height = height;
            this.bitrate = bitrate;
            this.rawQuality = rawQuality;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private TextureView textureView;
    private Surface surface;

    // Amazon IVS reflective player handles.
    private Object ivsPlayer;
    private Method ivsSetSurface;
    private Method ivsLoadUri;
    private Method ivsLoadString;
    private Method ivsPlay;
    private Method ivsPause;
    private Method ivsRelease;
    private Method ivsSetVolume;
    private Method ivsGetState;
    private Method ivsGetQualities;
    private Method ivsGetQuality;
    private Method ivsSetQuality;
    private Method ivsSetAutoQualityMode;

    // Fallback Android MediaPlayer.
    private MediaPlayer fallbackPlayer;

    private Uri pendingUri;
    private Uri fallbackUri;
    private boolean usedFallbackUri;
    private long loadStartedAtMs;
    private int targetMaxHeight;
    private boolean manualQualityChosen;
    private boolean autoCapApplied;
    private boolean muted = true;
    private boolean adMuted;
    private float unmutedVolume = 1f;
    private boolean pausedByLifecycle;
    private boolean released;
    private boolean hasStartedPlaying;
    private String lastQualityLabel = "Auto";
    private List<QualityOption> cachedQualities = Collections.emptyList();

    private final Runnable pollRunnable = this::pollIvsState;

    MultiStreamPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void attachTextureView(TextureView tv) {
        this.textureView = tv;
        tv.setSurfaceTextureListener(this);
        if (tv.isAvailable()) {
            onSurfaceTextureAvailable(tv.getSurfaceTexture(), tv.getWidth(), tv.getHeight());
        }
    }

    void load(Uri playUri, Uri directFallbackUri, int maxAutoHeight) {
        if (released) return;
        this.pendingUri = playUri;
        this.fallbackUri = directFallbackUri;
        this.usedFallbackUri = false;
        this.targetMaxHeight = maxAutoHeight;
        this.manualQualityChosen = false;
        this.autoCapApplied = false;
        this.hasStartedPlaying = false;
        this.adMuted = false;
        this.cachedQualities = Collections.emptyList();
        this.lastQualityLabel = "Auto";
        notifyState(true, null);

        if (playUri == null) {
            notifyState(false, "配信URLがありません");
            return;
        }
        if (surface != null) {
            startPlayback(playUri);
        }
    }

    void setTargetMaxHeight(int maxAutoHeight) {
        this.targetMaxHeight = maxAutoHeight;
        if (!manualQualityChosen && ivsPlayer != null && !cachedQualities.isEmpty()) {
            applyAutoQualityCap();
        }
    }

    void setMuted(boolean muted) {
        this.muted = muted;
        applyVolume();
    }

    /** Temporarily mutes audio during a blocked ad break without altering user mute preference. */
    void setAdMuted(boolean adMuted) {
        if (this.adMuted == adMuted) return;
        this.adMuted = adMuted;
        applyVolume();
    }

    boolean isMuted() {
        return muted;
    }

    void setVolume(float volume) {
        this.unmutedVolume = Math.max(0f, Math.min(1f, volume));
        applyVolume();
    }

    String getCurrentQualityLabel() {
        return lastQualityLabel;
    }

    List<String> getQualityLabels() {
        refreshQualities();
        List<String> labels = new ArrayList<>(cachedQualities.size() + 1);
        labels.add("自動 (Auto)");
        for (QualityOption opt : cachedQualities) {
            labels.add(opt.label);
        }
        return labels;
    }

    void selectQuality(int index) {
        if (ivsPlayer == null) return;
        refreshQualities();
        try {
            if (index <= 0) {
                manualQualityChosen = true;
                if (ivsSetAutoQualityMode != null) {
                    ivsSetAutoQualityMode.invoke(ivsPlayer, Boolean.TRUE);
                }
                lastQualityLabel = "Auto";
                notifyQuality(lastQualityLabel);
                return;
            }
            int listIdx = index - 1;
            if (listIdx >= cachedQualities.size()) return;
            QualityOption opt = cachedQualities.get(listIdx);
            manualQualityChosen = true;
            if (ivsSetAutoQualityMode != null) {
                try { ivsSetAutoQualityMode.invoke(ivsPlayer, Boolean.FALSE); } catch (Throwable ignored) {}
            }
            if (ivsSetQuality != null && opt.rawQuality != null) {
                ivsSetQuality.invoke(ivsPlayer, opt.rawQuality);
                lastQualityLabel = opt.label;
                notifyQuality(lastQualityLabel);
            }
        } catch (Throwable t) {
            Log.e(TAG, "selectQuality failed", t);
        }
    }

    void pause() {
        pausedByLifecycle = true;
        main.removeCallbacks(pollRunnable);
        try {
            if (ivsPlayer != null && ivsPause != null) {
                ivsPause.invoke(ivsPlayer);
            } else if (fallbackPlayer != null && fallbackPlayer.isPlaying()) {
                fallbackPlayer.pause();
            }
        } catch (Throwable ignored) {}
    }

    void resume() {
        pausedByLifecycle = false;
        if (released) return;
        try {
            if (ivsPlayer != null && ivsPlay != null) {
                ivsPlay.invoke(ivsPlayer);
                schedulePoll(POLL_FAST_MS);
            } else if (fallbackPlayer != null) {
                fallbackPlayer.start();
            }
        } catch (Throwable ignored) {}
    }

    void release() {
        released = true;
        main.removeCallbacks(pollRunnable);
        releaseInternalPlayers();
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        if (textureView != null) {
            textureView.setSurfaceTextureListener(null);
            textureView = null;
        }
    }

    private void releaseInternalPlayers() {
        if (ivsPlayer != null) {
            try {
                if (ivsSetSurface != null) ivsSetSurface.invoke(ivsPlayer, new Object[]{null});
            } catch (Throwable ignored) {}
            try {
                if (ivsRelease != null) ivsRelease.invoke(ivsPlayer);
            } catch (Throwable ignored) {}
            ivsPlayer = null;
        }
        if (fallbackPlayer != null) {
            try { fallbackPlayer.stop(); } catch (Throwable ignored) {}
            try { fallbackPlayer.release(); } catch (Throwable ignored) {}
            fallbackPlayer = null;
        }
    }

    private void startPlayback(Uri uri) {
        if (released || surface == null || uri == null) return;
        main.removeCallbacks(pollRunnable);
        loadStartedAtMs = SystemClock.uptimeMillis();
        hasStartedPlaying = false;
        autoCapApplied = false;
        notifyState(true, null);

        if (ensureIvsPlayer()) {
            try {
                ivsSetSurface.invoke(ivsPlayer, surface);
                applyVolume();
                if (ivsLoadUri != null) {
                    ivsLoadUri.invoke(ivsPlayer, uri);
                } else if (ivsLoadString != null) {
                    ivsLoadString.invoke(ivsPlayer, uri.toString());
                } else {
                    throw new IllegalStateException("IVS load method missing");
                }
                if (!pausedByLifecycle && ivsPlay != null) {
                    try { ivsPlay.invoke(ivsPlayer); } catch (Throwable ignored) {}
                }
                schedulePoll(POLL_FAST_MS);
                return;
            } catch (Throwable t) {
                Log.e(TAG, "IVS playback start failed, falling back", t);
                releaseInternalPlayers();
            }
        }

        startAndroidMediaPlayer(uri);
    }

    private boolean ensureIvsPlayer() {
        if (ivsPlayer != null) return true;
        Object created = createIvsPlayerInstance();
        if (created == null) return false;
        try {
            Class<?> cls = created.getClass();
            ivsSetSurface = findMethod(cls, "setSurface", Surface.class);
            ivsLoadUri = findMethod(cls, "load", Uri.class);
            ivsLoadString = findMethod(cls, "load", String.class);
            ivsPlay = findMethod(cls, "play");
            ivsPause = findMethod(cls, "pause");
            ivsRelease = findMethod(cls, "release");
            ivsSetVolume = findMethod(cls, "setVolume", float.class);
            ivsGetState = findMethod(cls, "getState");
            ivsGetQualities = findMethod(cls, "getQualities");
            ivsGetQuality = findMethod(cls, "getQuality");
            ivsSetQuality = findSingleArgMethod(cls, "setQuality");
            ivsSetAutoQualityMode = findMethod(cls, "setAutoQualityMode", boolean.class);
            if (ivsSetSurface == null || (ivsLoadUri == null && ivsLoadString == null) || ivsPlay == null) {
                try {
                    if (ivsRelease != null) ivsRelease.invoke(created);
                } catch (Throwable ignored) {}
                return false;
            }
            ivsPlayer = created;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "IVS reflection setup failed", t);
            return false;
        }
    }

    private Object createIvsPlayerInstance() {
        try {
            Class<?> factory = Class.forName("com.amazonaws.ivs.player.Player$Factory");
            Method create = factory.getMethod("create", Context.class);
            Object p = create.invoke(null, context);
            if (p != null) return p;
        } catch (Throwable ignored) {}
        try {
            Class<?> mpCls = Class.forName("com.amazonaws.ivs.player.MediaPlayer");
            Constructor<?> ctor = mpCls.getConstructor(Context.class);
            Object p = ctor.newInstance(context);
            if (p != null) return p;
        } catch (Throwable ignored) {}
        return null;
    }

    private void startAndroidMediaPlayer(Uri uri) {
        releaseInternalPlayers();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            mp.setSurface(surface);
            mp.setDataSource(context, uri);
            mp.setOnPreparedListener(player -> {
                if (released) return;
                hasStartedPlaying = true;
                applyVolume();
                if (!pausedByLifecycle) player.start();
                notifyState(false, null);
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (released) return true;
                if (!usedFallbackUri && fallbackUri != null && !fallbackUri.equals(pendingUri)) {
                    usedFallbackUri = true;
                    pendingUri = fallbackUri;
                    startPlayback(fallbackUri);
                    return true;
                }
                notifyState(false, "再生できませんでした (" + what + ")");
                return true;
            });
            mp.prepareAsync();
            fallbackPlayer = mp;
        } catch (Throwable t) {
            Log.e(TAG, "Fallback MediaPlayer failed", t);
            notifyState(false, "再生エラー");
        }
    }

    private void pollIvsState() {
        if (released || ivsPlayer == null || pausedByLifecycle) return;
        try {
            Object stateObj = ivsGetState == null ? null : ivsGetState.invoke(ivsPlayer);
            String state = stateObj == null ? "" : stateObj.toString().toUpperCase(Locale.ROOT);
            if ("READY".equals(state)) {
                try { ivsPlay.invoke(ivsPlayer); } catch (Throwable ignored) {}
                refreshQualities();
                applyAutoQualityCapIfNeeded();
                notifyState(true, null);
                schedulePoll(POLL_FAST_MS);
            } else if ("PLAYING".equals(state)) {
                hasStartedPlaying = true;
                refreshQualities();
                applyAutoQualityCapIfNeeded();
                updateCurrentQualityLabel();
                notifyState(false, null);
                schedulePoll(POLL_SLOW_MS);
            } else if ("BUFFERING".equals(state)) {
                if (!hasStartedPlaying && maybeFallbackFromProxy()) return;
                notifyState(true, null);
                schedulePoll(POLL_FAST_MS);
            } else if ("ENDED".equals(state)) {
                if (hasStartedPlaying) {
                    notifyState(false, "配信が終了しました");
                } else if (!maybeFallbackFromProxy()) {
                    notifyState(false, "配信を読み込めませんでした");
                }
            } else {
                // IDLE or unknown
                if (!hasStartedPlaying && maybeFallbackFromProxy()) return;
                if (!hasStartedPlaying && SystemClock.uptimeMillis() - loadStartedAtMs > 15000L) {
                    notifyState(false, "接続タイムアウト");
                    return;
                }
                schedulePoll(POLL_FAST_MS);
            }
        } catch (Throwable t) {
            Log.e(TAG, "pollIvsState failed", t);
            schedulePoll(POLL_SLOW_MS);
        }
    }

    private boolean maybeFallbackFromProxy() {
        if (usedFallbackUri || fallbackUri == null || fallbackUri.equals(pendingUri)) return false;
        if (SystemClock.uptimeMillis() - loadStartedAtMs < PROXY_FALLBACK_TIMEOUT_MS) return false;
        usedFallbackUri = true;
        pendingUri = fallbackUri;
        Log.i(TAG, "Proxy slow/failed in multi-view, falling back to direct usher URI");
        startPlayback(fallbackUri);
        return true;
    }

    private void refreshQualities() {
        if (ivsPlayer == null || ivsGetQualities == null) return;
        try {
            Object raw = ivsGetQualities.invoke(ivsPlayer);
            if (!(raw instanceof Collection<?> col) || col.isEmpty()) return;
            List<QualityOption> list = new ArrayList<>(col.size());
            for (Object q : col) {
                if (q == null) continue;
                String name = invokeString(q, "getName");
                int height = invokeInt(q, "getHeight");
                int bitrate = invokeInt(q, "getBitrate");
                String label = (name != null && !name.isEmpty())
                        ? name
                        : (height > 0 ? height + "p" : "不明");
                list.add(new QualityOption(label, height, bitrate, q));
            }
            list.sort((a, b) -> {
                if (b.height != a.height) return Integer.compare(b.height, a.height);
                return Integer.compare(b.bitrate, a.bitrate);
            });
            cachedQualities = list;
        } catch (Throwable ignored) {}
    }

    private void applyAutoQualityCapIfNeeded() {
        if (autoCapApplied || manualQualityChosen || targetMaxHeight <= 0 || cachedQualities.isEmpty()) return;
        applyAutoQualityCap();
    }

    private void applyAutoQualityCap() {
        if (ivsPlayer == null || ivsSetQuality == null || cachedQualities.isEmpty() || targetMaxHeight <= 0) return;
        QualityOption best = null;
        for (QualityOption opt : cachedQualities) {
            if (opt.height > 0 && opt.height <= targetMaxHeight) {
                best = opt;
                break;
            }
        }
        if (best == null) {
            best = cachedQualities.get(cachedQualities.size() - 1);
        }
        try {
            if (ivsSetAutoQualityMode != null) {
                try { ivsSetAutoQualityMode.invoke(ivsPlayer, Boolean.FALSE); } catch (Throwable ignored) {}
            }
            ivsSetQuality.invoke(ivsPlayer, best.rawQuality);
            autoCapApplied = true;
            lastQualityLabel = best.label;
            notifyQuality(lastQualityLabel);
        } catch (Throwable ignored) {}
    }

    private void updateCurrentQualityLabel() {
        if (ivsPlayer == null || ivsGetQuality == null) return;
        try {
            Object q = ivsGetQuality.invoke(ivsPlayer);
            if (q == null) return;
            String name = invokeString(q, "getName");
            int height = invokeInt(q, "getHeight");
            String label = (name != null && !name.isEmpty())
                    ? name
                    : (height > 0 ? height + "p" : lastQualityLabel);
            if (!label.equals(lastQualityLabel)) {
                lastQualityLabel = label;
                notifyQuality(label);
            }
        } catch (Throwable ignored) {}
    }

    private void applyVolume() {
        float vol = (muted || adMuted) ? 0f : unmutedVolume;
        try {
            if (ivsPlayer != null && ivsSetVolume != null) {
                ivsSetVolume.invoke(ivsPlayer, vol);
            } else if (fallbackPlayer != null) {
                fallbackPlayer.setVolume(vol, vol);
            }
        } catch (Throwable ignored) {}
    }

    private void schedulePoll(long delayMs) {
        main.removeCallbacks(pollRunnable);
        if (!released && !pausedByLifecycle) {
            main.postDelayed(pollRunnable, delayMs);
        }
    }

    private void notifyState(boolean loading, String error) {
        if (listener == null) return;
        main.post(() -> {
            if (!released) listener.onStateChanged(loading, error);
        });
    }

    private void notifyQuality(String label) {
        if (listener == null) return;
        main.post(() -> {
            if (!released) listener.onQualityChanged(label);
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        if (released) return;
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
        }
        surface = new Surface(st);
        if (ivsPlayer != null && ivsSetSurface != null) {
            try {
                ivsSetSurface.invoke(ivsPlayer, surface);
            } catch (Throwable ignored) {}
        } else if (pendingUri != null) {
            startPlayback(pendingUri);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (ivsPlayer != null && ivsSetSurface != null) {
            try { ivsSetSurface.invoke(ivsPlayer, new Object[]{null}); } catch (Throwable ignored) {}
        } else if (fallbackPlayer != null) {
            try { fallbackPlayer.setSurface(null); } catch (Throwable ignored) {}
        }
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

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
}
