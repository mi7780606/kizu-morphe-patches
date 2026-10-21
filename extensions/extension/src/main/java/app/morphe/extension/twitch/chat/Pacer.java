package app.morphe.extension.twitch.chat;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * まとめて届いたものに表示時刻を割り振り、一定のテンポで1件ずつ流す。
 * 1つのまとまりは「次のまとまりが届くまでの時間」いっぱいに均等に散らす。
 * どれも届いてから maxDelayMs 以内に必ず出す（取りこぼしなし）。
 * チャット欄と弾幕の両方で使う。メインスレッド専用。
 */
public final class Pacer<T> {
    public interface Sink<T> {
        void deliver(List<T> items);
    }

    /** 最大遅延の初期値（ミリ秒）。小さくすると遅延は減るが、まとめて流れやすくなる */
    public static final long MAX_DELAY_MS = 1000;
    /** 1件ごとの最小間隔（ほぼ1フレーム） */
    private static final long MIN_SPACING_MS = 16;
    /** 前のまとまりの最後と、次のまとまりの最初の間をあける最大時間 */
    private static final long BATCH_JOIN_MS = 150;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Sink<T> sink;
    private final long maxDelayMs;
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final ArrayDeque<Long> due = new ArrayDeque<>();
    private final Runnable drainTask = this::drain;
    private long lastBatchAt = -1;
    /** まとまりが届く平均間隔（自動で学習） */
    private float avgGapMs = 500f;
    /** 最後に割り振った表示時刻 */
    private long lastDue;

    public Pacer(Sink<T> sink) {
        this(sink, MAX_DELAY_MS);
    }

    public Pacer(Sink<T> sink, long maxDelayMs) {
        this.sink = sink;
        this.maxDelayMs = Math.max(MIN_SPACING_MS, maxDelayMs);
    }

    public boolean isIdle() {
        return queue.isEmpty();
    }

    /** 互換用（以前のコードから呼ばれても問題ないように残す） */
    public void noteArrival() {
        learn(SystemClock.uptimeMillis());
    }

    public void submit(List<? extends T> items) {
        if (items == null || items.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        learn(now);

        int n = items.size();
        // このまとまりを流しきる時間 = まとまりが届く間隔（上限 maxDelayMs）
        long window = Math.min((long) avgGapMs, maxDelayMs);
        long spacing = Math.max(MIN_SPACING_MS, window / n);
        long deadline = now + maxDelayMs;
        // 前のまとまりがまだ流れている途中なら、同じテンポで続ける
        long base = Math.max(now, lastDue + Math.min(spacing, BATCH_JOIN_MS));

        int i = 0;
        for (T item : items) {
            long t = Math.min(base + spacing * i, deadline);
            queue.add(item);
            due.add(t);
            lastDue = t;
            i++;
        }
        schedule();
    }

    /** 溜まっている分を全部すぐ出す */
    public void flush() {
        MAIN.removeCallbacks(drainTask);
        lastDue = 0;
        if (queue.isEmpty()) return;
        List<T> out = new ArrayList<>(queue);
        queue.clear();
        due.clear();
        safeDeliver(out);
    }

    public void clear() {
        MAIN.removeCallbacks(drainTask);
        queue.clear();
        due.clear();
        lastDue = 0;
    }

    private void learn(long now) {
        if (lastBatchAt >= 0) {
            long gap = Math.max(50, Math.min(2000, now - lastBatchAt));
            avgGapMs = avgGapMs * 0.7f + gap * 0.3f;
        }
        lastBatchAt = now;
    }

    private void schedule() {
        MAIN.removeCallbacks(drainTask);
        if (due.isEmpty()) return;
        long delay = due.peekFirst() - SystemClock.uptimeMillis();
        if (delay <= 0) MAIN.post(drainTask);
        else MAIN.postDelayed(drainTask, delay);
    }

    private void drain() {
        long now = SystemClock.uptimeMillis();
        List<T> out = new ArrayList<>();
        while (!due.isEmpty() && due.peekFirst() <= now + 2) {
            due.pollFirst();
            out.add(queue.pollFirst());
        }
        if (!out.isEmpty()) safeDeliver(out);
        schedule();
    }

    private void safeDeliver(List<T> items) {
        try {
            sink.deliver(items);
        } catch (Throwable t) {
            Log.e("KizuPacer", "deliver failed", t);
        }
    }
}
