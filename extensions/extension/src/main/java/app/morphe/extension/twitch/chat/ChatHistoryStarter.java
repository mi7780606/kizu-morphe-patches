package app.morphe.extension.twitch.chat;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** 画面が開いたらチャット欄を探して ChatHistory を付ける */
public final class ChatHistoryStarter {
    private static final String TAG = "KizuChatHistory";
    private static final long SCAN_DELAY_MS = 500;

    private static final Map<View, Boolean> ROOTS = new WeakHashMap<>();
    private static int listId = -1;

    private ChatHistoryStarter() {}

    public static void onActivityStarted(Activity activity) {
        if (!ChatHistory.ENABLED || activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || ROOTS.containsKey(decor)) return;
            ROOTS.put(decor, Boolean.TRUE);

            if (listId == -1) {
                listId = activity.getResources().getIdentifier(
                        "chat_message_recycler_view", "id", activity.getPackageName());
            }
            if (listId == 0) {
                Log.i(TAG, "chat list id not found");
                return;
            }

            Finder f = new Finder(decor);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(f);
            f.schedule();
        } catch (Throwable t) {
            Log.e(TAG, "starter failed", t);
        }
    }

    private static final class Finder implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        final WeakReference<View> ref;
        boolean posted;

        Finder(View root) { ref = new WeakReference<>(root); }

        @Override public void onGlobalLayout() { schedule(); }

        void schedule() {
            View root = ref.get();
            if (root == null || posted) return;
            posted = true;
            root.postDelayed(this, SCAN_DELAY_MS);
        }

        @Override public void run() {
            posted = false;
            View root = ref.get();
            if (root == null) return;
            try {
                View list = root.findViewById(listId);
                if (list != null) ChatHistory.attach(list);
            } catch (Throwable t) {
                Log.e(TAG, "find failed", t);
            }
        }
    }
}