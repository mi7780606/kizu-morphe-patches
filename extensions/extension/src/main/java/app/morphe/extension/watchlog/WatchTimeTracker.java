package app.morphe.extension.watchlog;

import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;

/**
 * 視聴ログ連携: Twitchで再生している間だけ、見ているチャンネルと秒数を
 * 1分ごとに視聴ログのサーバー（/api/watch/heartbeat）へ送る。
 *
 * チャンネル: ChannelChatConnectionKey(String id, String login) のフック（Emotes / Channel Points）から。
 * 再生状態: プレーヤー状態のフック（Danmaku）から。
 */
@SuppressWarnings("unused")
public final class WatchTimeTracker {
    private static final Object LOCK = new Object();
    private static final long TICK_SECONDS = 60;
    /** 1回の計上の上限（端末スリープ明けなどの異常値よけ）。 */
    private static final long MAX_DELTA_MS = 3 * 60_000L;
    /** 送れなかった分を溜めておく上限（チャンネルごと）。 */
    private static final long MAX_PENDING_MS = 6 * 60 * 60_000L;

    private static String channelId;
    private static String channelLogin;
    private static boolean playing;
    private static long lastMark;
    /** login -> [pending ms, channel id(hash持ち回り用ダミー)] */
    private static final Map<String, Long> pendingMs = new LinkedHashMap<>();
    private static final Map<String, String> idByLogin = new LinkedHashMap<>();

    private static ScheduledExecutorService executor;

    private WatchTimeTracker() {
    }

    /** チャットの接続先が変わったとき（配信を開いた・レイドで移動したなど）。 */
    public static void onChannelChanged(String id, String login) {
        try {
            String newLogin = normalize(login);
            if (newLogin == null) return;
            synchronized (LOCK) {
                if (newLogin.equals(channelLogin)) {
                    if (id != null && !id.isEmpty()) channelId = id;
                    return;
                }
                accrueLocked();
                channelLogin = newLogin;
                channelId = id;
                if (id != null && !id.isEmpty()) idByLogin.put(newLogin, id);
            }
            ensureStarted();
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
            else nowPlaying = null; // BUFFERING など: 状態を変えない
            if (nowPlaying == null) return;

            boolean stopped;
            synchronized (LOCK) {
                if (nowPlaying == playing) return;
                accrueLocked();
                playing = nowPlaying;
                lastMark = SystemClock.elapsedRealtime();
                stopped = !nowPlaying;
            }
            ensureStarted();
            if (stopped) executor.execute(WatchTimeTracker::flush);
        } catch (Throwable error) {
            Utils.logError("WatchLog: player state failed", error);
        }
    }

    private static void accrueLocked() {
        long now = SystemClock.elapsedRealtime();
        if (playing && channelLogin != null && lastMark > 0) {
            long delta = Math.min(Math.max(0, now - lastMark), MAX_DELTA_MS);
            Long cur = pendingMs.get(channelLogin);
            long next = Math.min((cur == null ? 0 : cur) + delta, MAX_PENDING_MS);
            pendingMs.put(channelLogin, next);
        }
        lastMark = now;
    }

    private static synchronized void ensureStarted() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kizu-watchlog");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(WatchTimeTracker::flush, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** 溜まった秒数をサーバーへ送る（ワーカースレッドで実行）。 */
    private static void flush() {
        try {
            if (!Settings.WATCHLOG_ENABLED.get()) {
                synchronized (LOCK) {
                    accrueLocked();
                    pendingMs.clear();
                }
                return;
            }
            String base = Settings.WATCHLOG_SERVER_URL.get().trim();
            String key = Settings.WATCHLOG_API_KEY.get().trim();
            if (base.isEmpty() || key.isEmpty()) return;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            List<Map.Entry<String, Long>> batch;
            synchronized (LOCK) {
                accrueLocked();
                batch = new ArrayList<>(pendingMs.entrySet());
            }
            for (Map.Entry<String, Long> entry : batch) {
                long ms = entry.getValue();
                while (ms >= 1000) {
                    long seconds = Math.min(ms / 1000, 600);
                    if (!send(base, key, entry.getKey(), idByLogin.get(entry.getKey()), seconds)) return; // 次回に再送
                    ms -= seconds * 1000;
                    synchronized (LOCK) {
                        Long cur = pendingMs.get(entry.getKey());
                        long left = (cur == null ? 0 : cur) - seconds * 1000;
                        if (left <= 0) pendingMs.remove(entry.getKey());
                        else pendingMs.put(entry.getKey(), left);
                    }
                }
            }
        } catch (Throwable error) {
            Utils.logError("WatchLog: flush failed", error);
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
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-API-Key", key);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                Utils.logInfo("WatchLog: sent " + seconds + "s for " + login);
                return true;
            }
            Utils.logInfo("WatchLog: server answered " + code);
            // 401/400 は再送しても通らないので捨てる（キー間違いで溜まり続けないように）
            return code == 400 || code == 401 || code == 403;
        } catch (Throwable error) {
            Utils.logInfo("WatchLog: send failed (" + error.getClass().getSimpleName() + "), retry later");
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalize(String login) {
        if (login == null) return null;
        String value = login.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        return value.isEmpty() ? null : value;
    }
}
