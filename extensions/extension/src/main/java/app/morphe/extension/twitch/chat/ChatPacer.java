package app.morphe.extension.twitch.chat;

import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * チャット欄アダプタ c35.w(List) に届いたコメントを、0.3秒以内に1件ずつ流し直す。
 * 何か失敗したら元の動作に戻る。
 */
@SuppressWarnings("unused")
public final class ChatPacer {
    private static final String TAG = "KizuChatPacer";
    /** これ以上の件数は過去ログ読み込みとみなして一括表示 */
    private static final int HISTORY_BATCH = 30;

    private static final WeakHashMap<Object, Pacer<Object>> PACERS = new WeakHashMap<>();
    private static Method addMethod;
    private static boolean broken;
    private static boolean bypass;

    private ChatPacer() {
    }

    /**
     * 注入先: c35.w(List) の先頭。
     * @return true なら元の処理をスキップ（あとで1件ずつ渡す）
     */
    public static boolean intercept(Object adapter, List<?> list) {
        if (bypass || broken || adapter == null || list == null || list.isEmpty()) return false;
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return false;
            if (resolve(adapter) == null) return false;

            Pacer<Object> pacer = PACERS.get(adapter);
            if (list.size() >= HISTORY_BATCH) {
                if (pacer != null) pacer.flush(); // 順番を守るため先に溜まり分を出す
                return false;
            }
            if (pacer == null) {
                WeakReference<Object> ref = new WeakReference<>(adapter);
                pacer = new Pacer<>(items -> {
                    Object a = ref.get();
                    if (a != null) deliver(a, items);
                });
                PACERS.put(adapter, pacer);
            }
            if (list.size() == 1 && pacer.isIdle()) {
                pacer.noteArrival();
                return false; // 1件だけで待ちもない → 遅延ゼロで即表示
            }
            pacer.submit(new ArrayList<Object>(list));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "intercept failed", t);
            return false;
        }
    }

    private static void deliver(Object adapter, List<Object> items) {
        Method m = resolve(adapter);
        if (m == null) return;
        bypass = true;
        try {
            m.invoke(adapter, new ArrayList<>(items));
        } catch (Throwable t) {
            broken = true; // 以後は元の動作に戻す
            Log.e(TAG, "deliver failed", t);
        } finally {
            bypass = false;
        }
    }

    private static Method resolve(Object adapter) {
        if (addMethod != null && addMethod.getDeclaringClass().isInstance(adapter)) return addMethod;
        try {
            addMethod = adapter.getClass().getMethod("w", List.class);
            return addMethod;
        } catch (Throwable t) {
            broken = true;
            Log.e(TAG, "c35.w not found", t);
            return null;
        }
    }
}
