package app.morphe.extension;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.twitch.chat.ChatFlow;
import app.morphe.extension.twitch.chat.ChatHistory;

/**
 * メモリの使い方を30秒ごとに記録し、落ちたときの記録（CrashLog）に添える。
 * 使用量が上限の85%を超えたら、Kizu側のキャッシュ（過去コメ・弾幕のスタンプ画像）を減らす。
 */
public final class MemoryWatch {
    private static final String TAG = "KizuMemory";
    private static final long INTERVAL_MS = 30_000L;
    private static final int MAX_SAMPLES = 20;
    private static final float TRIM_RATIO = 0.85f;
    private static final long TRIM_COOLDOWN_MS = 60_000L;

    private static final ArrayDeque<String> SAMPLES = new ArrayDeque<>();
    private static volatile boolean started;
    private static long lastTrimAt;
    private static int trims;

    private MemoryWatch() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            while (true) {
                try { sample(); } catch (Throwable e) { Log.e(TAG, "sample failed", e); }
                try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }, "kizu-memory");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void sample() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        String history = "?";
        try { history = ChatHistory.stats(); } catch (Throwable ignored) {}
        String emotes = "?";
        try { emotes = io.github.bakwudo.uyu.extension.danmaku.EmoteImages.stats(); } catch (Throwable ignored) {}
        int flow = 0;
        try { flow = Math.round(ChatFlow.rate()); } catch (Throwable ignored) {}
        String line = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date())
                + " " + (used >> 20) + "MB 流速" + flow + " 過去コメ" + history + " 弾幕画像" + emotes
                + " スレッド" + Thread.activeCount();
        long now = System.currentTimeMillis();
        boolean trim = max > 0 && used > max * TRIM_RATIO && now - lastTrimAt > TRIM_COOLDOWN_MS;
        if (trim) {
            lastTrimAt = now;
            trims++;
            try { ChatHistory.trimForMemory(); } catch (Throwable ignored) {}
            try { io.github.bakwudo.uyu.extension.danmaku.EmoteImages.trimForMemory(); } catch (Throwable ignored) {}
            line += " → 縮小" + trims + "回目";
            Log.w(TAG, line);
        }
        synchronized (SAMPLES) {
            SAMPLES.addFirst(line);
            while (SAMPLES.size() > MAX_SAMPLES) SAMPLES.pollLast();
        }
    }

    /** 新しい順の記録 */
    public static String summary() {
        synchronized (SAMPLES) {
            StringBuilder sb = new StringBuilder();
            for (String s : SAMPLES) sb.append("  ").append(s).append('\n');
            return sb.toString();
        }
    }
}
