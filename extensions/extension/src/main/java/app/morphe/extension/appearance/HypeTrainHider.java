package app.morphe.extension.appearance;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;

import app.morphe.extension.CrashLog;
import app.morphe.extension.Utils;
import app.morphe.extension.twitch.chat.ChatHistoryStarter;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * ハイプトレイン非表示。
 * 本命は KizuHooksPatch によるイベント破棄、横画面枠は HidePromotionsPatch.onViewCreated の
 * HiddenView 直接バインド＋起動時フォールバックで処理し、DecorView 常時監視は行わない。
 */
public final class HypeTrainHider {
    private static final String TAG = "KizuHypeTrain";
    public static final boolean ENABLED = true;

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
            if (decor == null) return;
            int id = Utils.getResourceId(activity, "one_chat_hype_train_landscape_container", "id");
            if (id == 0) return;
            WeakReference<View> decorRef = new WeakReference<>(decor);
            decor.post(() -> {
                View root = decorRef.get();
                if (root == null) return;
                View v = root.findViewById(id);
                if (v != null) {
                    HiddenView.attach(v, view -> KizuSettings.HIDE_HYPE_TRAIN.get(), true);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "fallback bind failed", t);
        }
    }
}
