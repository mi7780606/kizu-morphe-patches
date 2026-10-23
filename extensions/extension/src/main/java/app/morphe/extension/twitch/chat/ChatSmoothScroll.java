package app.morphe.extension.twitch.chat;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

public final class ChatSmoothScroll {
    private static final String TAG = "KizuSmoothScroll";

    private static final long RECENT_MS = 150;
    private static final float TAU_MS = 80f;
    private static final float MAX_OFFSET_DP = 96f;

    private static volatile long lastDeliverAt;
    private static volatile boolean suspended;
    private static volatile boolean broken;
    private static final Set<View> ATTACHED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private ChatSmoothScroll() {}

    public static void markDelivered() { lastDeliverAt = SystemClock.uptimeMillis(); }

    public static void setSuspended(boolean value) { suspended = value; }

    public static void attach(View view) {
        try {
            ChatHistory.attach(view);
        } catch (Throwable ignored) {}
        if (broken || !(view instanceof ViewGroup)) return;
        try {
            if (!ATTACHED.add(view)) return;
            Tracker t = new Tracker((ViewGroup) view);
            view.addOnAttachStateChangeListener(t);
            if (view.isAttachedToWindow()) t.onViewAttachedToWindow(view);
        } catch (Throwable e) {
            disable(e);
        }
    }

    private static boolean enabled() {
        try { return KizuSettings.SMOOTH_SCROLL.get(); } catch (Throwable t) { return true; }
    }

    private static void disable(Throwable e) { broken = true; Log.e(TAG, "disabled", e); }

    private static final class Tracker
            implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final ViewGroup rv;
        final float maxOffset;
        ViewTreeObserver vto;
        View tracked;
        int trackedTop;
        float offset;
        long lastFrame;

        Tracker(ViewGroup rv) {
            this.rv = rv;
            this.maxOffset = MAX_OFFSET_DP * rv.getResources().getDisplayMetrics().density;
        }

        @Override public void onViewAttachedToWindow(View v) {
            if (vto != null && vto.isAlive()) return;
            vto = v.getViewTreeObserver();
            vto.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View v) {
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = null; reset();
        }
        @Override public boolean onPreDraw() {
            if (broken) { if (offset != 0f) reset(); return true; }
            try {
                long now = SystemClock.uptimeMillis();
                int n = rv.getChildCount();
                View last = n > 0 ? rv.getChildAt(n - 1) : null;

                if (suspended || rv.getHeight() == 0 || !enabled()) {
                    if (offset != 0f) reset();
                    remember(last); lastFrame = now; return true;
                }
                if (tracked != null && tracked.getParent() == rv) {
                    int d = trackedTop - tracked.getTop();
                    if (d > 0 && d < rv.getHeight() / 2 && now - lastDeliverAt <= RECENT_MS) {
                        offset = Math.min(offset + d, maxOffset);
                    }
                }
                if (offset > 0.5f) {
                    long dt = lastFrame == 0 ? 16 : Math.min(64, Math.max(1, now - lastFrame));
                    offset *= (float) Math.exp(-dt / TAU_MS);
                    rv.setTranslationY(offset);
                    rv.postInvalidateOnAnimation();
                } else if (offset != 0f) {
                    offset = 0f; rv.setTranslationY(0f);
                }
                lastFrame = now; remember(last);
            } catch (Throwable e) { disable(e); reset(); }
            return true;
        }
        private void remember(View v) { tracked = v; trackedTop = v != null ? v.getTop() : 0; }
        private void reset() {
            offset = 0f; tracked = null; lastFrame = 0;
            try { rv.setTranslationY(0f); } catch (Throwable ignored) {}
        }
    }
}