package io.github.bakwudo.uyu.extension.danmaku;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.twitch.chat.ChatFlow;
import io.github.bakwudo.uyu.extension.Utils;

/**
 * Downloads and caches Twitch emote images without UI-thread locks or per-frame String allocations.
 */
final class EmoteImages {
    private static final String URL_FORMAT = "https://static-cdn.jtvnw.net/emoticons/v1/%s/%s";
    private static final long RETRY_DELAY_MS = 60_000;
    private static final int MAX_FAILED_CACHE = 512;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> failedAt = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor executor;

    static {
        executor = new ThreadPoolExecutor(
                2, 2, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(() -> {
                        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                        catch (Throwable ignored) {}
                        r.run();
                    }, "kizu-danmaku-emote");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    private EmoteImages() {}

    static int tierForSize(float size) {
        return size <= 28f ? 0 : size <= 56f ? 1 : 2;
    }

    static String keyFor(DanmakuComment.Emote emote, int tier) {
        if (emote.cachedTier == tier && emote.cachedKey != null) {
            return emote.cachedKey;
        }
        String scale = tier == 0 ? "1.0" : tier == 1 ? "2.0" : "3.0";
        String key = emote.id + "/" + scale;
        emote.cachedTier = tier;
        emote.cachedKey = key;
        return key;
    }

    static Bitmap get(DanmakuComment.Emote emote, int tier) {
        String key = keyFor(emote, tier);
        Bitmap bitmap = cache.get(key);
        if (bitmap != null) return bitmap;
        if (ChatFlow.level() >= ChatFlow.FLOOD) return null;

        Long failed = failedAt.get(key);
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_DELAY_MS) return null;
        if (!loading.add(key)) return null;

        String scale = tier == 0 ? "1.0" : tier == 1 ? "2.0" : "3.0";
        String url = String.format(URL_FORMAT, emote.id, scale);
        executor.execute(() -> load(key, url));
        return null;
    }

    static Bitmap get(String emoteId, float size) {
        return get(new DanmakuComment.Emote(emoteId), tierForSize(size));
    }

    private static void load(String key, String url) {
        Bitmap bitmap = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ex) {
            Utils.logError("Failed to load emote " + url, ex);
        } finally {
            if (connection != null) connection.disconnect();
        }

        if (bitmap != null) {
            cache.put(key, bitmap);
            failedAt.remove(key);
        } else {
            if (failedAt.size() >= MAX_FAILED_CACHE) failedAt.clear();
            failedAt.put(key, SystemClock.elapsedRealtime());
        }
        loading.remove(key);
    }
}
