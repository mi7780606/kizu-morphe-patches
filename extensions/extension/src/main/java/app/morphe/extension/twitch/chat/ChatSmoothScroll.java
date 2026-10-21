package app.morphe.extension.twitch.chat;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.WeakHashMap;

/**
 * チャット欄が新しいコメントで一瞬で跳ね上がる動きを、すべるようなアニメーションに変える。
 * 跳ねた分だけ各行を下にずらして描き、毎フレーム少しずつ 0 に戻す。
 * 一番下を表示中で、コメント追加の直後に上へ動いたときだけ働く（手でスクロールしたときは何もしない）。
 */
@SuppressWarnings("unused")
public final class ChatSmoothScroll {
    private static final String TAG = "KizuSmoothScroll";
    /** コメント追加から何ms以内の動きをなめらかにするか */
    private static final long RECENT_MS = 150;
    /** 追いつく速さ。小さいほどキビキビ、大きいほどゆったり（ミリ秒） */
    private static final float TAU_MS = 80f;

    private static final WeakHashMap<View, Boolean> ATTACHED = new WeakHashMap<>();
    static volatile long lastDeliverAt = -1;

    private ChatSmoothScroll() {
    }

    /** ChatPacer がコメントを一覧に渡す直前に呼ぶ */
    public static void markDelivered() {
        lastDeliverAt = SystemClock.uptimeMillis();
    }

    /** 注入先: チャット画面 hu6 のコンストラクタの最後。引数はチャットの RecyclerView */
    public static void attach(View view) {
        try {
            if (!(view instanceof ViewGroup) || ATTACHED.containsKey(view)) return;
            ATTACHED.put(view, Boolean.TRUE);
            new Smoother((ViewGroup) view);
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private static final class Smoother
            implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final ViewGroup list;
        private View anchor;
        private int anchorTop;
        private float offset;
        private long lastFrame = -1;
        private boolean broken;

        Smoother(ViewGroup list) {
            this.list = list;
            list.addOnAttachStateChangeListener(this);
            if (list.isAttachedToWindow()) listen(list);
        }

        private void listen(View v) {
            ViewTreeObserver observer = v.getViewTreeObserver();
            observer.removeOnPreDrawListener(this);
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            lastFrame = -1;
            listen(v);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            v.getViewTreeObserver().removeOnPreDrawListener(this);
            reset();
        }

        @Override
        public boolean onPreDraw() {
            if (broken) return true;
            try {
                step();
            } catch (Throwable t) {
                broken = true;
                reset();
                Log.e(TAG, "smooth scroll disabled", t);
            }
            return true;
        }

        private void step() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame < 0 ? 16f : Math.min(50f, now - lastFrame);
            lastFrame = now;

            int count = list.getChildCount();
            if (count == 0) {
                anchor = null;
                offset = 0f;
                return;
            }

            // 一番下にある行
            View bottom = list.getChildAt(0);
            for (int i = 1; i < count; i++) {
                View c = list.getChildAt(i);
                if (c.getBottom() > bottom.getBottom()) bottom = c;
            }
            boolean atBottom = bottom.getBottom() <= list.getHeight() - list.getPaddingBottom() + 2;

            // 前のフレームの一番下の行が、コメント追加で上に跳ねた分を足す
            if (anchor != null && anchor.getParent() == list && atBottom
                    && now - lastDeliverAt < RECENT_MS) {
                int dy = anchorTop - anchor.getTop();
                if (dy > 0 && dy <= list.getHeight() / 2) offset += dy;
            }

            // 少しずつ 0 に戻す
            if (offset != 0f) {
                offset *= (float) Math.exp(-dt / TAU_MS);
                if (offset < 0.5f) offset = 0f;
                float max = list.getHeight() * 0.5f;
                if (offset > max) offset = max;
            }

            for (int i = 0; i < count; i++) {
                View c = list.getChildAt(i);
                if (c.getTranslationY() != offset) c.setTranslationY(offset);
            }

            anchor = bottom;
            anchorTop = bottom.getTop();
            if (offset != 0f) list.postInvalidateOnAnimation();
        }

        private void reset() {
            offset = 0f;
            anchor = null;
            for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setTranslationY(0f);
        }
    }
}
