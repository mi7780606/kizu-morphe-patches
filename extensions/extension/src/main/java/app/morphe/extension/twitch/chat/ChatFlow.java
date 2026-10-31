package app.morphe.extension.twitch.chat;

import android.os.SystemClock;
import android.util.Log;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * チャットの流速メーター。受信した時点（画面に出る前）で1か所だけ数え、
 * なめらかチャット・弾幕・スタンプ画像の読み込みが同じ段階を見て動きを変える。
 *
 * 通常 → 混雑 → 激流。上げるときは即、下げるときは落ち着いた状態が3秒続いてから。
 */
public final class ChatFlow {
    private static final String TAG = "KizuChatFlow";

    public static final int NORMAL = 0;
    public static final int BUSY = 1;
    public static final int FLOOD = 2;

    private static final long WINDOW_MS = 500;
    private static final long IDLE_RESET_MS = 2000;
    private static final long CALM_HOLD_MS = 3000;
    private static final long SETTINGS_REFRESH_MS = 2000;

    private static final Object LOCK = new Object();
    private static long windowStart;
    private static int windowCount;
    private static float rate;
    private static volatile int level = NORMAL;
    private static long calmSince;

    private static int busyRate = 30;
    private static int floodRate = 80;
    private static long settingsReadAt;

    private ChatFlow() {}

    private static volatile long lastDataAt;

    /** 受信したコメント数を記録する（どのスレッドからでも可）。過去ログの一括読み込みは数えない。 */
    public static void record(int count) {
        lastDataAt = SystemClock.uptimeMillis();
        add(count);
    }

    /** 予備: 弾幕パッチ（受信フック）が無いときだけ、チャット欄に届いた数で数える */
    public static void recordFromUi(int count) {
        if (SystemClock.uptimeMillis() - lastDataAt < 5000) return;
        add(count);
    }

    private static void add(int count) {
        if (count <= 0) return;
        long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            refreshSettings(now);
            if (windowStart == 0 || now - windowStart > IDLE_RESET_MS) {
                if (windowStart != 0) rate = 0f;
                windowStart = now;
                windowCount = count;
            } else {
                windowCount += count;
                long span = now - windowStart;
                if (span >= WINDOW_MS) {
                    float inst = windowCount * 1000f / span;
                    rate = rate * 0.5f + inst * 0.5f;
                    windowStart = now;
                    windowCount = 0;
                }
            }
            update(now);
        }
    }

    /** 今の段階。コメントが途切れたときにも下がるよう、読むたびに経過時間を見る。 */
    public static int level() {
        int l = level;
        if (l == NORMAL) return l;
        long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            if (windowStart != 0 && now - windowStart > IDLE_RESET_MS) rate = 0f;
            update(now);
            return level;
        }
    }

    public static float rate() {
        synchronized (LOCK) { return rate; }
    }

    private static void update(long now) {
        int target = rate >= floodRate ? FLOOD : rate >= busyRate ? BUSY : NORMAL;
        int cur = level;
        if (target > cur) {
            level = target;
            calmSince = 0;
            Log.i(TAG, "level " + cur + " -> " + target + " (rate=" + rate + ")");
        } else if (target < cur) {
            if (calmSince == 0) calmSince = now;
            else if (now - calmSince >= CALM_HOLD_MS) {
                level = cur - 1; // 1段ずつ下げる
                calmSince = now;
                Log.i(TAG, "level " + cur + " -> " + (cur - 1) + " (rate=" + rate + ")");
            }
        } else {
            calmSince = 0;
        }
    }

    private static void refreshSettings(long now) {
        if (settingsReadAt != 0 && now - settingsReadAt < SETTINGS_REFRESH_MS) return;
        settingsReadAt = now;
        try {
            int b = KizuSettings.FLOW_BUSY_RATE.get();
            int f = KizuSettings.FLOW_FLOOD_RATE.get();
            if (f <= b) f = b + 1;
            busyRate = b;
            floodRate = f;
        } catch (Throwable ignored) {}
    }
}
