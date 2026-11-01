package app.morphe.extension.channelpoints;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Twitch 本体の Client-Integrity トークン管理
 * (tv.twitch.android.network.clientintegrity.a) を受け取り、今のトークンを読む。
 * パッチがそのコンストラクタの最後で onManager(this) を呼ぶ。
 */
public final class IntegrityBridge {
    private static volatile WeakReference<Object> manager = new WeakReference<>(null);
    private static volatile Method tokenMethod;
    private static volatile boolean reported;

    private IntegrityBridge() {}

    /** パッチから呼ばれる */
    public static void onManager(Object m) {
        if (m == null) return;
        manager = new WeakReference<>(m);
    }

    public static boolean hooked() {
        return manager.get() != null;
    }

    /** 本体が持っている今のトークン。無ければ null（取得の開始も本体に任せる） */
    public static String token() {
        Object m = manager.get();
        if (m == null) return null;
        try {
            Method method = tokenMethod;
            if (method == null) {
                method = m.getClass().getMethod("a");
                tokenMethod = method;
            }
            Object v = method.invoke(m);
            String t = v instanceof String ? ((String) v).trim() : null;
            return t == null || t.isEmpty() ? null : t;
        } catch (Throwable t) {
            if (!reported) {
                reported = true;
                CpLog.add("Integrityの読み取りに失敗: " + t);
            }
            return null;
        }
    }
}
