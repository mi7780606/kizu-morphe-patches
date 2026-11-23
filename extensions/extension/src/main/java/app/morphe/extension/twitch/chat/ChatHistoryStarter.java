package app.morphe.extension.twitch.chat;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;

import app.morphe.extension.Utils;

/** 画面が開いたらチャット欄を探して ChatHistory を付ける（フォールバック単発チェック） */
public final class ChatHistoryStarter {
    private static final String TAG = "KizuChatHistory";
    private static final long FALLBACK_DELAY_MS = 450;

    private ChatHistoryStarter() {}

    public static void onActivityStarted(Activity activity) {
        if (!ChatHistory.ENABLED || activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) return;
            int listId = Utils.getResourceId(activity, "chat_message_recycler_view", "id");
            if (listId == 0) return;
            WeakReference<View> decorRef = new WeakReference<>(decor);
            decor.postDelayed(() -> {
                View root = decorRef.get();
                if (root == null) return;
                try {
                    View list = root.findViewById(listId);
                    if (list != null) ChatHistory.attach(list);
                } catch (Throwable t) {
                    Log.e(TAG, "find failed", t);
                }
            }, FALLBACK_DELAY_MS);
        } catch (Throwable t) {
            Log.e(TAG, "starter failed", t);
        }
    }
}
