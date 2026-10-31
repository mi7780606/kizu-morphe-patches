package app.morphe.extension.appearance;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.CrashLog;
import app.morphe.extension.twitch.chat.ChatHistoryStarter;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * ハイプトレイン非表示。
 *
 * 本命: Twitch 31.3.1 のハイプトレイン → コミュニティハイライト（チャット上部のカード）への
 * イベント中継（"hypetrain" を扱うクラス）の入口をパッチし、追加・更新イベントを捨てる。
 * カード自体が作られないので、一瞬見える・重い・誤爆するが無くなる。終了（削除）イベントだけは通す。
 *
 * 予備: 上の経路を通らない表示（横画面チャットのハイプトレイン枠など）のため、
 * ハイプトレイン専用の入れ物（one_chat_hype_train_landscape_container）を IDで直接引いて、
 * 2秒に1回まで確認して消す（画面全体の走査も、文字での判定もしない）。
 */
public final class HypeTrainHider {
    private static final String TAG = "KizuHypeTrain";

    public static final boolean ENABLED = true;
    private static final long MIN_SCAN_INTERVAL_MS = 2000;

    private static final Map<View, Scanner> SCANNERS = new WeakHashMap<>();
    private static int blocked;

    private HypeTrainHider() {}

    /** パッチから呼ばれる: true ならハイプトレインのイベントを捨てる */
    public static boolean hideHypeTrain() {
        try {
            if (!ENABLED || !KizuSettings.HIDE_HYPE_TRAIN.get()) return false;
            if (blocked++ == 0) Log.i(TAG, "hype train event blocked");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void onActivityStarted(Activity activity) {
        try { CrashLog.install(activity); } catch (Throwable ignored) {}
        try {
            ChatHistoryStarter.onActivityStarted(activity);
        } catch (Throwable t) {
            Log.e(TAG, "chat history start failed", t);
        }
        if (!ENABLED || activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || SCANNERS.containsKey(decor)) return;
            Scanner s = new Scanner(decor);
            SCANNERS.put(decor, s);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(s);
            s.schedule();
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private static final class Scanner implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        final WeakReference<View> rootRef;
        boolean posted;
        long lastScan;

        Scanner(View root) { rootRef = new WeakReference<>(root); }

        @Override public void onGlobalLayout() { schedule(); }

        void schedule() {
            View root = rootRef.get();
            if (root == null || posted) return;
            posted = true;
            long wait = Math.max(300, MIN_SCAN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastScan));
            root.postDelayed(this, wait);
        }

        @Override public void run() {
            posted = false;
            View root = rootRef.get();
            if (root == null) return;
            try {
                if (!KizuSettings.HIDE_HYPE_TRAIN.get()) return;
                lastScan = SystemClock.uptimeMillis();
                scan(root);
            } catch (Throwable t) {
                Log.e(TAG, "scan failed", t);
            }
        }
    }

    /** 予備で消す入れ物のID（Twitch 31.3.1 のリソース名） */
    private static final String[] FALLBACK_IDS = {"one_chat_hype_train_landscape_container"};
    private static int[] fallbackIds;

    private static void scan(View root) {
        if (fallbackIds == null) {
            fallbackIds = new int[FALLBACK_IDS.length];
            for (int i = 0; i < FALLBACK_IDS.length; i++) {
                fallbackIds[i] = root.getResources().getIdentifier(FALLBACK_IDS[i], "id", root.getContext().getPackageName());
            }
        }
        for (int i = 0; i < fallbackIds.length; i++) {
            if (fallbackIds[i] == 0) continue;
            View v = root.findViewById(fallbackIds[i]);
            if (v != null && v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                Log.i(TAG, "hidden view id=" + FALLBACK_IDS[i]);
            }
        }
    }
}
