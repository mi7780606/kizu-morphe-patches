package app.morphe.extension.player;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioManager;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;

/**
 * Shared PurpleTV-style landscape brightness/volume swipe controller for Twitch player wrappers.
 */
final class PlayerGestureController {
    private static final float GESTURE_SCALE = 0.7f;
    private static final int EDGE_IGNORE_DP = 30;
    private static final ConcurrentHashMap<Class<?>, Boolean> SCROLL_CONTAINER_CACHE =
            new ConcurrentHashMap<>();
    private static volatile int cachedSystemMaxBrightness = -1;

    private final ViewGroup host;
    private final GestureOsdView osdView;
    private final int touchSlop;
    private final int edgeIgnorePx;
    private final int[] wrapperLocation = new int[2];
    private final int[] viewLocation = new int[2];
    private final SparseBooleanArray menuIdCache = new SparseBooleanArray();

    private ViewGroup playerOverlay;
    private ViewGroup debugPanel;
    private ViewGroup oneChatOverlay;
    private View chatWrapper;
    private View debugList;
    private AudioManager cachedAudioManager;

    private boolean hasWrapperLocation;
    private boolean candidate;
    private boolean intercepted;
    private boolean brightnessSide;
    private boolean scrollMode;
    private float downY = -1f;
    private int startingVolume;
    private int lastRequestedVolume = -1;
    private int lastActualVolume = -1;
    private int startingBrightness;
    private int lastBrightnessStep = -1;
    private int maxVolume;

    PlayerGestureController(ViewGroup host) {
        this.host = host;
        Context context = host.getContext();
        this.touchSlop = Math.max(1, ViewConfiguration.get(context).getScaledTouchSlop());
        this.edgeIgnorePx = Math.round(EDGE_IGNORE_DP * context.getResources().getDisplayMetrics().density);
        this.osdView = new GestureOsdView(context);
    }

    void onFinishInflate(String overlayViewName) {
        playerOverlay = findGroup(overlayViewName);
        debugPanel = findGroup("player_debug_stats_container");
        oneChatOverlay = findGroup("one_chat_overlay_container");
        chatWrapper = findNamedView("chat_wrapper");
        debugList = findNamedView("video_debug_list");
        if (playerOverlay != null && osdView.getParent() == null) {
            playerOverlay.addView(osdView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    boolean shouldSuppressDisallowIntercept() {
        return Settings.DISABLE_LANDSCAPE_SWIPE_TO_PORTRAIT.get()
                && host.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginCandidate(event);
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                candidate = false;
                return intercepted;
            case MotionEvent.ACTION_MOVE:
                if (intercepted) return true;
                if (!candidate) return false;
                if (event.getPointerCount() > 1) {
                    candidate = false;
                    return false;
                }
                if (Math.abs(downY - event.getY()) > touchSlop
                        && (gestureEnabled() || Settings.DISABLE_LANDSCAPE_SWIPE_TO_PORTRAIT.get())) {
                    intercepted = true;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (intercepted) return true;
                resetTouchState();
                return false;
            default:
                return intercepted;
        }
    }

    boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginCandidate(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!intercepted) return false;
                if (!candidate || event.getPointerCount() > 1) return true;
                scrollMode = true;
                updateGesture(downY - event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrollMode) osdView.scheduleHide();
                resetTouchState();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                candidate = false;
                return intercepted;
            default:
                return intercepted;
        }
    }

    private View findNamedView(String name) {
        int id = host.getResources().getIdentifier(name, "id", host.getContext().getPackageName());
        return id == 0 ? null : host.findViewById(id);
    }

    private ViewGroup findGroup(String name) {
        View view = findNamedView(name);
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    private void beginCandidate(MotionEvent event) {
        resetTouchState();
        if (event.getPointerCount() > 1 || playerOverlay == null
                || host.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        host.getLocationOnScreen(wrapperLocation);
        hasWrapperLocation = true;

        downY = event.getY();
        brightnessSide = isOnBrightnessSide(event.getX());
        boolean adjustGesture = gestureEnabled();
        boolean blockCollapse = Settings.DISABLE_LANDSCAPE_SWIPE_TO_PORTRAIT.get();
        // When collapse prevention is enabled, also own vertical swipes that begin at the
        // player's top/bottom edge. Twitch otherwise gets those edge gestures before Kizu.
        if ((!adjustGesture && !blockCollapse)
                || !isTouchAreaAllowed(event.getX(), event.getY(), blockCollapse)) {
            return;
        }
        try {
            if (adjustGesture && brightnessSide) {
                Activity activity = Utils.getCurrentActivity();
                if (activity == null) {
                    if (!blockCollapse) return;
                } else {
                    startingBrightness = brightnessPercent(activity);
                }
            } else if (adjustGesture) {
                AudioManager audio = audioManager();
                if (audio == null) {
                    if (!blockCollapse) return;
                } else {
                    startingVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if (maxVolume <= 0 && !blockCollapse) return;
                }
            }
            candidate = true;
        } catch (Throwable ignored) {
            candidate = blockCollapse;
        }
    }

    /** Split gestures across the actual video pane, not the entire theatre window. */
    private boolean isOnBrightnessSide(float x) {
        if (playerOverlay == null || playerOverlay.getWidth() <= 0) {
            return x < host.getWidth() / 2.0f;
        }
        ensureWrapperLocation();
        playerOverlay.getLocationOnScreen(viewLocation);
        float playerLeft = viewLocation[0] - wrapperLocation[0];
        return x < playerLeft + (playerOverlay.getWidth() / 2.0f);
    }

    private boolean gestureEnabled() {
        return brightnessSide ? Settings.BRIGHTNESS_GESTURE.get() : Settings.VOLUME_GESTURE.get();
    }

    private boolean isTouchAreaAllowed(float x, float y, boolean includeEdges) {
        if (!isPointInside(playerOverlay, x, y)) return false;
        if (!includeEdges && (y <= edgeIgnorePx || y >= localBottom(playerOverlay) - edgeIgnorePx)) return false;
        if (oneChatOverlay != null && oneChatOverlay.isShown() && oneChatOverlay.getChildCount() > 0) return false;
        if (chatWrapper != null && chatWrapper.isShown() && isPointInside(chatWrapper, x, y)) return false;
        if (debugPanel != null && debugPanel.getChildCount() > 0 && debugList != null
                && debugList.isShown() && isPointInside(debugList, x, y)) return false;
        // Never steal vertical swipes that start on Twitch's own settings/quality menus,
        // scroll containers, sliders, or compact player controls.
        return !isInteractiveControlAt(host, x, y, true);
    }

    private boolean isInteractiveControlAt(View view, float x, float y, boolean root) {
        if (view == null || !view.isShown() || view.getAlpha() <= 0.01f
                || !isPointInside(view, x, y)) {
            return false;
        }

        if (!root && view != osdView && view != playerOverlay && isSettingsOrQualityMenuView(view)) {
            return true;
        }

        // Twitch's quality list contains blank/padded regions that do not have their own
        // resource ID. If a scroll container contains settings/quality menu rows, leave the
        // whole container's touch stream to Twitch instead of intercepting only over the rows.
        if (!root && isMenuScrollContainer(view) && containsSettingsOrQualityMenu(view)) {
            return true;
        }

        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                if (isInteractiveControlAt(group.getChildAt(i), x, y, false)) return true;
            }
        }
        return false;
    }

    private boolean isSettingsOrQualityMenuView(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        int cachedIndex = menuIdCache.indexOfKey(id);
        if (cachedIndex >= 0) return menuIdCache.valueAt(cachedIndex);
        boolean isMenu = false;
        try {
            String idName = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            isMenu = idName.startsWith("settings_menu")
                    || idName.startsWith("quality_options")
                    || idName.startsWith("simple_quality")
                    || idName.startsWith("quality_config");
        } catch (Throwable ignored) {
        }
        menuIdCache.put(id, isMenu);
        return isMenu;
    }

    private boolean containsSettingsOrQualityMenu(View view) {
        if (view == null || !view.isShown() || view.getAlpha() <= 0.01f) return false;
        if (isSettingsOrQualityMenuView(view)) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsSettingsOrQualityMenu(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean isMenuScrollContainer(View view) {
        Class<?> cls = view.getClass();
        Boolean cached = SCROLL_CONTAINER_CACHE.get(cls);
        if (cached != null) return cached;
        String className = cls.getName().toLowerCase(Locale.ROOT);
        boolean result = className.contains("recyclerview")
                || className.contains("scrollview")
                || className.contains("nestedscrollview")
                || className.contains("abslistview")
                || className.contains("listview");
        SCROLL_CONTAINER_CACHE.put(cls, result);
        return result;
    }

    private void ensureWrapperLocation() {
        if (!hasWrapperLocation) {
            host.getLocationOnScreen(wrapperLocation);
            hasWrapperLocation = true;
        }
    }

    private float localBottom(View view) {
        ensureWrapperLocation();
        view.getLocationOnScreen(viewLocation);
        return viewLocation[1] + view.getHeight() - wrapperLocation[1];
    }

    private boolean isPointInside(View view, float x, float y) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        ensureWrapperLocation();
        view.getLocationOnScreen(viewLocation);
        float sx = wrapperLocation[0] + x;
        float sy = wrapperLocation[1] + y;
        return sx >= viewLocation[0] && sx < viewLocation[0] + view.getWidth()
                && sy >= viewLocation[1] && sy < viewLocation[1] + view.getHeight();
    }

    private void updateGesture(float delta) {
        // The swipe can still be consumed to block Twitch's collapse gesture when level control
        // is disabled; in that case it must not alter device settings or show a fake progress bar.
        if (!gestureEnabled()) {
            osdView.hideImmediately();
            return;
        }
        if (brightnessSide) {
            Activity activity = Utils.getCurrentActivity();
            if (activity == null) return;
            int value = calculate(delta, startingBrightness, 100, playerOverlay.getHeight());
            if (value != lastBrightnessStep) {
                try {
                    Window window = activity.getWindow();
                    WindowManager.LayoutParams attributes = window.getAttributes();
                    attributes.screenBrightness = Math.max(0.01f, Math.min(1.0f, value / 100.0f));
                    window.setAttributes(attributes);
                    lastBrightnessStep = value;
                } catch (Throwable ignored) {
                    return;
                }
            }
            showOsd(lastBrightnessStep >= 0 ? lastBrightnessStep : value, 100, true);
        } else {
            AudioManager audio = audioManager();
            if (audio == null || maxVolume <= 0) return;
            int value = calculate(delta, startingVolume, maxVolume, playerOverlay.getHeight());
            if (value != lastRequestedVolume) {
                try {
                    // Absolute target based on touch-down level so downward swipes can cross it
                    // to zero, emitted only when the integer step actually changes.
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    lastRequestedVolume = value;
                    lastActualVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                } catch (Throwable ignored) {
                    return;
                }
            }
            int shown = lastActualVolume >= 0 ? lastActualVolume : value;
            showOsd(shown, maxVolume, false);
        }
    }

    private void showOsd(int value, int max, boolean brightness) {
        if (!Settings.GESTURE_OSD.get()) {
            osdView.hideImmediately();
            return;
        }
        osdView.show(value, max, brightness);
    }

    private static int calculate(float delta, int oldStep, int max, int height) {
        if (max <= 0 || height <= 0) return oldStep;
        float step = (height * GESTURE_SCALE) / max;
        if (step <= 0f) return oldStep;
        // Round instead of truncating toward zero so downward swipes can reduce volume
        // smoothly even when the starting media level is low.
        return Math.max(0, Math.min(max, oldStep + Math.round(delta / step)));
    }

    private void resetTouchState() {
        hasWrapperLocation = false;
        candidate = false;
        intercepted = false;
        brightnessSide = false;
        scrollMode = false;
        downY = -1f;
        lastRequestedVolume = -1;
        lastActualVolume = -1;
        lastBrightnessStep = -1;
    }

    private AudioManager audioManager() {
        if (cachedAudioManager == null) {
            cachedAudioManager = (AudioManager) host.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return cachedAudioManager;
    }

    private static int brightnessPercent(Activity activity) {
        float brightness = activity.getWindow().getAttributes().screenBrightness;
        if (brightness >= 0f) {
            return Math.max(0, Math.min(100, Math.round(brightness * 100f)));
        }
        try {
            int sys = android.provider.Settings.System.getInt(
                    activity.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    -1);
            if (sys < 0) return 50;
            int max = 255;
            if (sys > 255) {
                int sysMax = systemMaxBrightness();
                if (sysMax >= sys) max = sysMax;
            }
            return Math.max(1, Math.min(100, Math.round(sys * 100f / max)));
        } catch (Throwable ignored) {
            return 50;
        }
    }

    private static int systemMaxBrightness() {
        int cached = cachedSystemMaxBrightness;
        if (cached > 0) return cached;
        int resolved = 255;
        try {
            Resources res = Resources.getSystem();
            int id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            if (id != 0) {
                int val = res.getInteger(id);
                if (val > 0) resolved = val;
            }
        } catch (Throwable ignored) {
        }
        cachedSystemMaxBrightness = resolved;
        return resolved;
    }
}
