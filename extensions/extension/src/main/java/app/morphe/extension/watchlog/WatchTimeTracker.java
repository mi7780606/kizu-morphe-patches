package app.morphe.extension.watchlog;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import app.morphe.extension.settings.Setting;
import app.morphe.extension.settings.Settings;

/**
 * 視聴ログ連携: Twitchで再生している間だけ、見ているチャンネルと秒数を
 * 1分ごとに視聴ログのサーバー（/api/watch/heartbeat）へ送る。
 */
@SuppressWarnings("unused")
public final class WatchTimeTracker {
    private static final Object LOCK = new Object();
    private static final long TICK_SECONDS = 60L;
    private static final long MAX_DELTA_MS = 3 * 60_000L;
    private static final long MAX_PENDING_MS = 6 * 60 * 60_000L;
    private static final long FAILURE_BACKOFF_MS = 30_000L;
    private static final int MAX_ID_CACHE = 200;

    private static String channelId;
    private static String channelLogin;
    private static boolean playing;
    private static long lastMark;
    private static final Map<String, Long> pendingMs = new LinkedHashMap<>();
    private static final Map<String, String> idByLogin = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ID_CACHE;
        }
    };

    private static final AtomicBoolean flushing = new AtomicBoolean(false);
    private static volatile long retryAfterMs;
    private static ScheduledFuture<?> tickFuture;
    private static boolean listenerRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null
                        || Settings.WATCHLOG_ENABLED.key.equals(key)
                        || Settings.WATCHLOG_SERVER_URL.key.equals(key)
                        || Settings.WATCHLOG_API_KEY.key.equals(key)) {
                    syncSchedule();
                }
            };

    private WatchTimeTracker() {}

    /** チャットの接続先が変わったとき（配信を開いた・レイドで移動したなど）。 */
    public static void onChannelChanged(String id, String login) {
        try {
            String newLogin = normalize(login);
            if (newLogin == null) return;
            ensurePrefListener();
            synchronized (LOCK) {
                if (id != null && !id.isEmpty()) {
                    idByLogin.put(newLogin, id);
                }
                if (newLogin.equals(channelLogin)) {
                    if (id != null && !id.isEmpty()) channelId = id;
                    return;
                }
                accrueLocked();
                channelLogin = newLogin;
                channelId = id;
            }
            syncSchedule();
        } catch (Throwable error) {
            Utils.logError("WatchLog: channel change failed", error);
        }
    }

    /** プレーヤーの状態（PLAYING / PAUSED / STOPPED / BUFFERING / IDLE / ENDED ...）。 */
    public static void onPlayerState(Enum<?> state) {
        try {
            if (state == null) return;
            String name = state.name();
            Boolean nowPlaying;
            if (name.equals("PLAYING")) nowPlaying = true;
            else if (name.equals("PAUSED") || name.equals("STOPPED") || name.equals("ENDED")
                    || name.equals("IDLE") || name.equals("ERROR")) nowPlaying = false;
            else nowPlaying = null;
            if (nowPlaying == null) return;

            ensurePrefListener();
            boolean stopped;
            boolean shouldFlushNow;
            synchronized (LOCK) {
                if (nowPlaying == playing) return;
                accrueLocked();
                playing = nowPlaying;
                lastMark = SystemClock.elapsedRealtime();
                stopped = !nowPlaying;
                shouldFlushNow = stopped && isConfigured() && !pendingMs.isEmpty();
            }
            syncSchedule();
            if (shouldFlushNow) {
                KizuRuntime.ioExecutor().execute(WatchTimeTracker::flush);
            }
        } catch (Throwable error) {
            Utils.logError("WatchLog: player state failed", error);
        }
    }

    private static synchronized void ensurePrefListener() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        Setting.addChangeListener(PREF_LISTENER);
    }

    private static boolean isConfigured() {
        if (!Settings.WATCHLOG_ENABLED.get()) return false;
        String base = Settings.WATCHLOG_SERVER_URL.get();
        String key = Settings.WATCHLOG_API_KEY.get();
        return base != null && !base.trim().isEmpty() && key != null && !key.trim().isEmpty();
    }

    private static void accrueLocked() {
        long now = SystemClock.elapsedRealtime();
        if (Settings.WATCHLOG_ENABLED.get() && playing && channelLogin != null && lastMark > 0) {
            long delta = Math.min(Math.max(0, now - lastMark), MAX_DELTA_MS);
            Long cur = pendingMs.get(channelLogin);
            long next = Math.min((cur == null ? 0 : cur) + delta, MAX_PENDING_MS);
            pendingMs.put(channelLogin, next);
        }
        lastMark = now;
    }

    private static synchronized void syncSchedule() {
        boolean needed;
        synchronized (LOCK) {
            if (!Settings.WATCHLOG_ENABLED.get()) {
                pendingMs.clear();
                needed = false;
            } else {
                needed = isConfigured() && (playing || !pendingMs.isEmpty());
            }
        }
        if (!needed) {
            if (tickFuture != null) {
                tickFuture.cancel(false);
                tickFuture = null;
            }
            return;
        }
        if (tickFuture != null) return;
        tickFuture = KizuRuntime.scheduler().scheduleWithFixedDelay(
                () -> KizuRuntime.ioExecutor().execute(WatchTimeTracker::flush),
                TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    private static final class PendingItem {
        final String login;
        final String id;
        final long ms;

        PendingItem(String login, String id, long ms) {
            this.login = login;
            this.id = id;
            this.ms = ms;
        }
    }

    /** 溜まった秒数をサーバーへ送る（ワーカースレッドで実行）。 */
    private static void flush() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            if (!Settings.WATCHLOG_ENABLED.get()) {
                synchronized (LOCK) {
                    accrueLocked();
                    pendingMs.clear();
                }
                syncSchedule();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < retryAfterMs) return;

            String base = Settings.WATCHLOG_SERVER_URL.get().trim();
            String key = Settings.WATCHLOG_API_KEY.get().trim();
            if (base.isEmpty() || key.isEmpty()) return;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            List<PendingItem> batch;
            synchronized (LOCK) {
                accrueLocked();
                if (pendingMs.isEmpty()) {
                    batch = java.util.Collections.emptyList();
                } else {
                    batch = new ArrayList<>(pendingMs.size());
                    for (Map.Entry<String, Long> entry : pendingMs.entrySet()) {
                        batch.add(new PendingItem(
                                entry.getKey(), idByLogin.get(entry.getKey()), entry.getValue()));
                    }
                }
            }
            for (PendingItem entry : batch) {
                long ms = entry.ms;
                while (ms >= 1000L) {
                    long seconds = Math.min(ms / 1000L, 600L);
                    if (!send(base, key, entry.login, entry.id, seconds)) {
                        retryAfterMs = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS;
                        return;
                    }
                    retryAfterMs = 0L;
                    ms -= seconds * 1000L;
                    synchronized (LOCK) {
                        Long cur = pendingMs.get(entry.login);
                        long left = (cur == null ? 0L : cur) - seconds * 1000L;
                        if (left <= 0L) pendingMs.remove(entry.login);
                        else pendingMs.put(entry.login, left);
                    }
                }
            }
            syncSchedule();
        } catch (Throwable error) {
            Utils.logError("WatchLog: flush failed", error);
        } finally {
            flushing.set(false);
        }
    }

    private static boolean send(String base, String key, String login, String id, long seconds) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("platform", "twitch");
            body.put("channel_name", login);
            if (id != null) body.put("channel_id", id);
            body.put("seconds", seconds);
            body.put("source", "android");
            body.put("device", Build.MODEL);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(base + "/api/watch/heartbeat").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-API-Key", key);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                drainQuietly(connection.getInputStream());
                Utils.logInfo("WatchLog: sent " + seconds + "s for " + login);
                return true;
            }
            drainQuietly(connection.getErrorStream());
            Utils.logInfo("WatchLog: server answered " + code);
            return code == 400 || code == 401 || code == 403;
        } catch (Throwable error) {
            Utils.logInfo("WatchLog: send failed (" + error.getClass().getSimpleName() + "), retry later");
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        try (InputStream s = stream) {
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < 4096 && (n = s.read(buf)) != -1) total += n;
        } catch (Throwable ignored) {}
    }

    private static String normalize(String login) {
        if (login == null) return null;
        String value = login.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        return value.isEmpty() ? null : value;
    }
}
