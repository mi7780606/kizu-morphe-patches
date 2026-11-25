package app.morphe.extension.appearance;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.settings.Setting;

/**
 * Hides a target view based on a setting condition, attaching its PreDraw guard ONLY while
 * hiding is active and automatically re-evaluating immediately when any preference changes.
 */
final class HiddenView implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    interface Condition {
        boolean shouldHide(View view);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, HiddenView> INSTANCES = new WeakHashMap<>();
    private static volatile boolean listenerRegistered;
    private static boolean recheckPosted;

    private static final Runnable RECHECK_ALL = () -> {
        recheckPosted = false;
        ArrayList<HiddenView> snapshot;
        synchronized (INSTANCES) {
            snapshot = new ArrayList<>(INSTANCES.values());
        }
        for (HiddenView hv : snapshot) {
            if (hv != null) hv.update();
        }
    };

    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (!recheckPosted) {
                        recheckPosted = true;
                        MAIN.post(RECHECK_ALL);
                    }
                } else {
                    MAIN.post(RECHECK_ALL);
                }
            };

    private final View view;
    private final Condition condition;
    private final boolean restore;
    private boolean hiddenByUs;
    private boolean enforceQueued;
    private int visibilityBeforeHide = View.VISIBLE;
    private float alphaBeforeHide = 1f;
    private ViewTreeObserver observer;

    private HiddenView(View view, Condition condition, boolean restore) {
        this.view = view;
        this.condition = condition;
        this.restore = restore;
    }

    static void attach(View view, Condition condition, boolean restore) {
        if (view == null || condition == null) return;
        ensurePrefListener();
        HiddenView existing;
        synchronized (INSTANCES) {
            existing = INSTANCES.get(view);
            if (existing == null) {
                existing = new HiddenView(view, condition, restore);
                INSTANCES.put(view, existing);
            } else {
                existing.scheduleEnforce();
                return;
            }
        }
        view.addOnAttachStateChangeListener(existing);
        if (view.isAttachedToWindow()) {
            existing.onViewAttachedToWindow(view);
        } else {
            existing.update();
        }
    }

    private void scheduleEnforce() {
        if (!condition.shouldHide(view)) {
            update();
            return;
        }
        if (enforceQueued) return;
        enforceQueued = true;
        MAIN.postAtFrontOfQueue(() -> {
            enforceQueued = false;
            update();
        });
    }

    private static void ensurePrefListener() {
        if (listenerRegistered) return;
        synchronized (INSTANCES) {
            if (!listenerRegistered) {
                listenerRegistered = true;
                Setting.addChangeListener(PREF_LISTENER);
            }
        }
    }

    @Override
    public void onViewAttachedToWindow(View attached) {
        update();
    }

    @Override
    public void onViewDetachedFromWindow(View detached) {
        detachObserver();
    }

    @Override
    public boolean onPreDraw() {
        return !update();
    }

    private void attachObserver() {
        if (!view.isAttachedToWindow()) return;
        if (observer != null && observer.isAlive()) return;
        ViewTreeObserver vto = view.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            vto.addOnPreDrawListener(this);
            observer = vto;
        }
    }

    private void detachObserver() {
        if (observer != null) {
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }
    }

    private boolean update() {
        if (condition.shouldHide(view)) {
            attachObserver();
            boolean changed = false;
            if (!hiddenByUs) {
                visibilityBeforeHide = view.getVisibility();
                alphaBeforeHide = view.getAlpha();
            }
            if (view.getAlpha() != 0f) {
                view.setAlpha(0f);
                changed = true;
            }
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                changed = true;
            }
            hiddenByUs = true;
            return changed;
        }
        detachObserver();
        if (!hiddenByUs) return false;
        hiddenByUs = false;
        if (!restore) return false;
        boolean changed = false;
        if (view.getAlpha() != alphaBeforeHide) {
            view.setAlpha(alphaBeforeHide);
            changed = true;
        }
        if (view.getVisibility() == View.GONE && view.getVisibility() != visibilityBeforeHide) {
            view.setVisibility(visibilityBeforeHide);
            changed = true;
        }
        return changed;
    }
}
