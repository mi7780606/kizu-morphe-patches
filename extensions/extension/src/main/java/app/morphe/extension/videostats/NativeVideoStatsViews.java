package app.morphe.extension.videostats;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Setting;
import app.morphe.extension.settings.Settings;

/**
 * Keeps Kizu's custom Video Stats button synchronized with the volume control's local layout
 * (instead of a window-wide OnGlobalLayoutListener) and forwards clicks to Twitch's native
 * video_debug_info_button.
 */
public final class NativeVideoStatsViews {
    private static final String TAG = "KizuVideoStats";
    private static final String NATIVE_STATS_BUTTON = "video_debug_info_button";
    private static final Map<View, Binding> BINDINGS = new WeakHashMap<>();
    private static boolean prefListenerRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || Settings.SHOW_VIDEO_STATS_BUTTON.key.equals(key)) {
                    refreshPreferences();
                }
            };

    private NativeVideoStatsViews() {}

    public static synchronized void install(View root, View button, View volume) {
        if (root == null || button == null || volume == null) return;
        ensurePrefListener();
        Binding binding = BINDINGS.get(button);
        if (binding == null) {
            binding = new Binding(root, button, volume);
            BINDINGS.put(button, binding);
            button.setOnClickListener(binding);
            button.addOnAttachStateChangeListener(binding);
            if (button.isAttachedToWindow()) binding.onViewAttachedToWindow(button);
        } else {
            binding.refresh();
        }
    }

    private static void ensurePrefListener() {
        if (prefListenerRegistered) return;
        prefListenerRegistered = true;
        Setting.addChangeListener(PREF_LISTENER);
    }

    public static synchronized void refreshPreferences() {
        for (Binding binding : new ArrayList<>(BINDINGS.values())) {
            if (binding != null) binding.refresh();
        }
    }

    private static final class Binding implements View.OnClickListener,
            View.OnAttachStateChangeListener, View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> root;
        private final WeakReference<View> button;
        private final WeakReference<View> volume;
        private int lastVisibility = -1;
        private float lastAlpha = -1f;
        private boolean lastEnabled;
        private boolean listenersAttached;
        private ViewTreeObserver observer;

        Binding(View root, View button, View volume) {
            this.root = new WeakReference<>(root);
            this.button = new WeakReference<>(button);
            this.volume = new WeakReference<>(volume);
        }

        synchronized void refresh() {
            View target = button.get();
            View original = volume.get();
            if (target == null || original == null) return;
            boolean featureEnabled = VideoStatsRuntime.enabled();
            int next = featureEnabled ? original.getVisibility() : View.GONE;
            float alpha = original.getAlpha();
            boolean enabled = featureEnabled && next == View.VISIBLE && original.isEnabled();
            if (lastVisibility != next || target.getVisibility() != next) {
                lastVisibility = next;
                target.setVisibility(next);
            }
            if (lastAlpha != alpha || target.getAlpha() != alpha) {
                lastAlpha = alpha;
                target.setAlpha(alpha);
            }
            if (lastEnabled != enabled || target.isEnabled() != enabled) {
                lastEnabled = enabled;
                target.setEnabled(enabled);
            }
        }

        @Override public boolean onPreDraw() {
            View original = volume.get();
            if (original == null) return true;
            int expectedVis = VideoStatsRuntime.enabled() ? original.getVisibility() : View.GONE;
            if (expectedVis != lastVisibility
                    || original.getAlpha() != lastAlpha
                    || original.isEnabled() != lastEnabled) {
                refresh();
            }
            return true;
        }

        @Override public void onClick(View view) {
            if (!view.isShown() || !view.isEnabled()) return;

            View base = root.get();
            if (base == null) {
                Log.w(TAG, "Cannot open native video stats: player view is unavailable.");
                return;
            }

            int id = Utils.getResourceId(base.getContext(), NATIVE_STATS_BUTTON, "id");
            if (id == 0) {
                Log.w(TAG, "Twitch's native video stats button resource was not found.");
                return;
            }

            View nativeButton = base.findViewById(id);
            if (nativeButton == null) {
                View tree = base.getRootView();
                if (tree != null && tree != base) nativeButton = tree.findViewById(id);
            }
            if (nativeButton == null) {
                Log.w(TAG, "Twitch's native video stats button is not attached to this player.");
                return;
            }

            if (!nativeButton.performClick()) {
                Log.w(TAG, "Twitch's native video stats click handler was not ready.");
            } else {
                Log.i(TAG, "Opened Twitch's native video stats panel.");
            }
        }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            refresh();
        }

        @Override public void onViewAttachedToWindow(View view) {
            if (!listenersAttached) {
                listenersAttached = true;
                View original = volume.get();
                if (original != null) {
                    original.addOnLayoutChangeListener(this);
                    if (original.getParent() instanceof View parent) {
                        parent.addOnLayoutChangeListener(this);
                    }
                    ViewTreeObserver vto = original.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.addOnPreDrawListener(this);
                        observer = vto;
                    }
                }
            }
            refresh();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            if (listenersAttached) {
                listenersAttached = false;
                View original = volume.get();
                if (original != null) {
                    original.removeOnLayoutChangeListener(this);
                    if (original.getParent() instanceof View parent) {
                        parent.removeOnLayoutChangeListener(this);
                    }
                }
                if (observer != null) {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    observer = null;
                }
            }
        }
    }
}
