package app.morphe.extension.appearance;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.twitch.emotes.EmoteAutocomplete;

/** パッチから直接呼ばれる入口（チャット入力欄・チャンネルポイント画面が作られたとき） */
public final class KizuHooks {
    private static final String TAG = "KizuHooks";
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Field MISSING_FIELD;

    static {
        Field sentinel = null;
        try {
            sentinel = KizuHooks.class.getDeclaredField("TAG");
        } catch (NoSuchFieldException ignored) {}
        MISSING_FIELD = sentinel;
    }

    private static WeakReference<ImageView> chatSettings = new WeakReference<>(null);

    private KizuHooks() {}

    /** i76 のコンストラクタ末尾から呼ばれる */
    public static void onChatInputCreated(Object delegate) {
        try {
            ImageView v = findViewById(delegate, ImageView.class, "view_chat_settings");
            if (v != null) {
                chatSettings = new WeakReference<>(v);
                KizuUiTweaks.bindChatMenuButton(v);
            }
            EditText input = findViewById(delegate, EditText.class, "message_input");
            if (input != null) {
                EmoteAutocomplete.attachDirectly(input);
            }
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
            ImageView more = findViewById(delegate, ImageView.class, "more_button");
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
            ImageView more = findViewById(delegate, ImageView.class, "reward_options");
            if (more == null) return;
            ViewParent p = more.getParent();
            if (p instanceof LinearLayout) KizuUiTweaks.addPredictionButtonTo((LinearLayout) p, more);
        } catch (Throwable t) {
            Log.e(TAG, "rewards hook failed", t);
        }
    }

    private static <T extends View> T findViewById(Object delegate, Class<T> viewClass, String idName)
            throws IllegalAccessException {
        if (delegate == null) return null;
        Class<?> delegateClass = delegate.getClass();
        String cacheKey = delegateClass.getName() + "#" + idName;
        Field cached = FIELD_CACHE.get(cacheKey);
        if (cached != null) {
            if (cached == MISSING_FIELD) return null;
            Object val = cached.get(delegate);
            return viewClass.isInstance(val) ? viewClass.cast(val) : null;
        }

        Context c = null;
        int id = 0;
        for (Class<?> k = delegateClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!viewClass.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object o = f.get(delegate);
                if (!viewClass.isInstance(o)) continue;
                T view = viewClass.cast(o);
                if (c == null) {
                    c = view.getContext();
                    id = Utils.getResourceId(c, idName, "id");
                }
                if (id != 0 && view.getId() == id) {
                    FIELD_CACHE.putIfAbsent(cacheKey, f);
                    return view;
                }
            }
        }
        if (id != 0 && MISSING_FIELD != null) {
            FIELD_CACHE.putIfAbsent(cacheKey, MISSING_FIELD);
        }
        return null;
    }
}
