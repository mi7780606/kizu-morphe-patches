package app.morphe.extension.appearance;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/** パッチから直接呼ばれる入口（チャット入力欄・チャンネルポイント画面が作られたとき） */
public final class KizuHooks {
    private static final String TAG = "KizuHooks";
    private static WeakReference<ImageView> chatSettings = new WeakReference<>(null);

    private KizuHooks() {}

    /** i76 のコンストラクタ末尾から呼ばれる */
    public static void onChatInputCreated(Object delegate) {
        try {
            ImageView v = findImageView(delegate, "view_chat_settings");
            if (v != null) chatSettings = new WeakReference<>(v);
        } catch (Throwable t) {
            Log.e(TAG, "chat input hook failed", t);
        }
    }

    /** プレイヤーのボタンから呼ぶ。隠した三点リーダーと同じ動き（チャット設定を開く） */
    public static boolean openChatSettings() {
        try {
            ImageView v = chatSettings.get();
            if (v == null || !v.isAttachedToWindow()) return false;
            return v.performClick();
        } catch (Throwable t) {
            Log.e(TAG, "open chat settings failed", t);
            return false;
        }
    }

    /** tb9 のコンストラクタ末尾から呼ばれる。予想ボタンを常に置く */
    public static void onCommunityPointsCreated(Object delegate) {
        try {
            ImageView more = findImageView(delegate, "more_button");
            if (more == null) return;
            ViewParent p = more.getParent();
            if (p instanceof LinearLayout) KizuUiTweaks.addPredictionButtonTo((LinearLayout) p, more);
        } catch (Throwable t) {
            Log.e(TAG, "community points hook failed", t);
        }
    }

    /** en9（報酬一覧）のコンストラクタ末尾から呼ばれる。「〇〇の報酬」の行の︙の左に置く */
    public static void onRewardsCreated(Object delegate) {
        try {
            ImageView more = findImageView(delegate, "reward_options");
            if (more == null) return;
            ViewParent p = more.getParent();
            if (p instanceof LinearLayout) KizuUiTweaks.addPredictionButtonTo((LinearLayout) p, more);
        } catch (Throwable t) {
            Log.e(TAG, "rewards hook failed", t);
        }
    }

    private static ImageView findImageView(Object delegate, String idName) throws IllegalAccessException {
        if (delegate == null) return null;
        Context c = null;
        int id = 0;
        for (Class<?> k = delegate.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!ImageView.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object o = f.get(delegate);
                if (!(o instanceof ImageView)) continue;
                ImageView iv = (ImageView) o;
                if (c == null) {
                    c = iv.getContext();
                    id = c.getResources().getIdentifier(idName, "id", c.getPackageName());
                }
                if (id != 0 && iv.getId() == id) return iv;
            }
        }
        return null;
    }
}
