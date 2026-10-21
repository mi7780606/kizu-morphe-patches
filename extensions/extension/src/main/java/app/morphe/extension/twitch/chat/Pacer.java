package app.morphe.extension.twitch.chat;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * まとめて届いたものを1件ずつ流す。どれも届いてから MAX_DELAY_MS 以内に必ず出す。
 * チャット欄と弾幕の両方で使う。メインスレッド専用。
 */
public final class Pacer<T> {
    public interface Sink<T> {
        void deliver(List<T> items);
    }

    /** 最大遅延（ミリ秒）。チャット欄と弾幕の両方に効く */
    public static final long MAX_DELAY_MS = 300;
    private static final long MIN_INTERVAL_MS = 16;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Sink<T> sink;
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    private final Runnable drainTask = this::drain;
    private long lastBatchAt = -1;
    private float avgGapMs = 500f;
    private boolean scheduled;

    public Pacer(Sink<T> sink) {
        this.sink = sink;
    }

    public boolean isIdle() {
        return queue.isEmpty();
    }

    /** 素通しするときもバッチ間隔だけは学習する */
    public void noteArrival() {
        learn(SystemClock.uptimeMillis());
    }

    public void submit(List<? extends T> items) {
        if (items == null || items.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        learn(now);
        for (T item : items) {
            queue.add(item);
            times.add(now);
        }
        if (!scheduled) {
            scheduled = true;
            MAIN.post(drainTask); // 1件目は即表示
        }
    }

    /** 溜まっている分を全部すぐ出す */
    public void flush() {
        MAIN.removeCallbacks(drainTask);
        scheduled = false;
        if (queue.isEmpty()) return;
        List<T> out = new ArrayList<>(queue);
        queue.clear();
        times.clear();
        safeDeliver(out);
    }

    public void clear() {
        MAIN.removeCallbacks(drainTask);
        scheduled = false;
        queue.clear();
        times.clear();
    }

    private void learn(long now) {
        if (lastBatchAt >= 0) {
            long gap = Math.max(50, Math.min(2000, now - lastBatchAt));
            avgGapMs = avgGapMs * 0.8f + gap * 0.2f;
        }
        lastBatchAt = now;
    }

    private void drain() {
        scheduled = false;
        if (queue.isEmpty()) return;
        long now = SystemClock.uptimeMillis();

        List<T> out = new ArrayList<>();
        out.add(poll());
        // 0.3秒に達したものは待たせずまとめて出す（取りこぼしなし）
        while (!times.isEmpty() && now - times.peekFirst() >= MAX_DELAY_MS) out.add(poll());
        safeDeliver(out);

        if (queue.isEmpty()) return;
        long window = Math.min((long) avgGapMs, MAX_DELAY_MS);
        long interval = Math.max(MIN_INTERVAL_MS, window / (queue.size() + 1));
        long left = times.peekFirst() + MAX_DELAY_MS - SystemClock.uptimeMillis();
        scheduled = true;
        MAIN.postDelayed(drainTask, Math.max(0, Math.min(interval, left)));
    }

    private T poll() {
        times.pollFirst();
        return queue.pollFirst();
    }

    private void safeDeliver(List<T> items) {
        try {
            sink.deliver(items);
        } catch (Throwable t) {
            Log.e("KizuPacer", "deliver failed", t);
        }
    }
}
