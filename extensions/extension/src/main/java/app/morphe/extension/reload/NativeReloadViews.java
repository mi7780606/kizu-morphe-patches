package app.morphe.extension.reload;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.player.NetworkQualityManager;
import app.morphe.extension.settings.Setting;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.videostats.NativeVideoStatsViews;

public final class NativeReloadViews {
    private static final String TAG = "twitchpatches_reload_button";
    private static final String STATS_TAG = "twitchpatches_video_stats_button";
    private static final String LOG_TAG = "KizuReload";
    private static final WeakHashMap<View, Binding> bindings = new WeakHashMap<>();
    private static boolean prefListenerRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || Settings.SHOW_REFRESH_BUTTON.key.equals(key)) {
                    refreshPreferences();
                }
            };

    private NativeReloadViews() {}

    public static synchronized void install(View root, Object owner, boolean live, int volumeId) {
        if (live && owner != null) {
            try { NetworkQualityManager.bindNativeReloadCandidate(owner); } catch (Throwable ignored) {}
        }
        if (root == null || !live || !(owner instanceof NativeReloadOwner)) return;
        ensurePrefListener();

        View volume = root.findViewById(volumeId);
        ViewGroup parent = volume != null && volume.getParent() instanceof ViewGroup
                ? (ViewGroup) volume.getParent() : null;
        int id = Utils.getResourceId(root.getContext(), TAG, "id");
        View button = parent == null || id == 0 ? null : parent.findViewById(id);

        int statsId = Utils.getResourceId(root.getContext(), STATS_TAG, "id");
        View statsButton = parent == null || statsId == 0 ? null : parent.findViewById(statsId);
        if (statsButton != null && volume != null) {
            NativeVideoStatsViews.install(root, statsButton, volume);
        }

        if (button == null || volume == null) return;

        Binding binding = bindings.get(button);
        if (binding == null) {
            binding = new Binding(root, button, volume, (NativeReloadOwner) owner);
            bindings.put(button, binding);
            button.setOnClickListener(binding);
            button.addOnAttachStateChangeListener(binding);
            if (button.isAttachedToWindow()) binding.onViewAttachedToWindow(button);
            Log.i(LOG_TAG, "reload view binding installed");
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
        for (Binding binding : bindings.values()) {
            if (binding != null) binding.refresh();
        }
    }

    private static final class Binding implements View.OnClickListener,
            View.OnAttachStateChangeListener, View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> button;
        private final WeakReference<View> volume;
        private final NativeReloadHost host;
        private final NativeReloadAction action;
        private int lastVisibility = -1;
        private float lastAlpha = -1f;
        private boolean lastEnabled;
        private boolean listenersAttached;
        private ViewTreeObserver observer;

        Binding(View root, View button, View volume, NativeReloadOwner owner) {
            this.button = new WeakReference<>(button);
            this.volume = new WeakReference<>(volume);
            this.host = new ViewReloadHost(root, owner);
            this.action = NativeReloadAction.create(host);
        }

        synchronized void refresh() {
            View target = button.get();
            View original = volume.get();
            if (target == null || original == null) return;
            int visibility = ReloadRuntime.enabled() ? original.getVisibility() : View.GONE;
            float alpha = original.getAlpha();
            boolean enabled = visibility == View.VISIBLE && original.isEnabled() && action.available();
            if (lastVisibility != visibility || target.getVisibility() != visibility) {
                lastVisibility = visibility;
                target.setVisibility(visibility);
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
            int expectedVis = ReloadRuntime.enabled() ? original.getVisibility() : View.GONE;
            if (expectedVis != lastVisibility
                    || original.getAlpha() != lastAlpha
                    || original.isEnabled() != lastEnabled) {
                refresh();
            }
            return true;
        }

        @Override public void onClick(View view) {
            if (view.isShown() && view.isEnabled()) {
                Log.i(LOG_TAG, "reload button tapped");
                action.invoke();
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
