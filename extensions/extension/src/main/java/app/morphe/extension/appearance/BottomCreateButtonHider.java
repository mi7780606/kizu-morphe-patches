package app.morphe.extension.appearance;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Setting;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.SettingsUi;

/**
 * Hides Twitch's home bottom-center "+" (Create / Go Live) navigation button while keeping
 * its visibility restorable when {@link Settings#HIDE_CREATE_BUTTON} is turned off.
 *
 * Strategy:
 * 1) Bind any known/heuristic create-button resource IDs in the tree WITHOUT stopping early.
 * 2) Locate the real XML bottom-bar child via left/right nav-tab LCA or bottom-row matching
 *    so the slot collapses cleanly with View.GONE.
 * 3) If Twitch renders the bottom navigation bar inside a Jetpack ComposeView, align an
 *    opaque tap-blocking mask strictly over the center 1/5 slot of that ComposeView.
 */
public final class BottomCreateButtonHider {
    private static final String TAG = "KizuBottomCreate";
    private static final String COMPOSE_MASK_TAG = "kizu_compose_create_mask";

    private static final String[] KNOWN_IDS = {
            "create_button",
            "following_nav_rail_create_button",
            "bottom_nav_create_button",
            "nav_create_button",
            "create_nav_item",
            "navigation_create",
            "creator_mode_button",
            "go_live_button",
            "create_tab",
            "nav_bar_create_button",
            "action_create",
            "tab_create",
            "bottom_bar_create",
    };

    private static final String[] MAIN_SCREEN_MARKERS = {
            "following_list_recycler_view",
            "tab_layout",
            "tab_layout_no_tab_width",
            "navigation_host",
            "main_activity_root",
            "bottom_navigation",
    };

    private static final long[] RETRY_DELAYS_MS = {0L, 250L, 700L, 1500L, 3000L, 5000L};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> IN_FLIGHT = new WeakHashMap<>();
    private static final WeakHashMap<Activity, WeakReference<View>> BOUND_BOTTOM_TARGET = new WeakHashMap<>();
    private static final WeakHashMap<Activity, ComposeMaskController> COMPOSE_MASKS = new WeakHashMap<>();

    private static volatile int[] resolvedIds;
    private static volatile boolean prefListenerRegistered;

    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || Settings.HIDE_CREATE_BUTTON.key.equals(key)) {
                    MAIN.post(BottomCreateButtonHider::syncAllComposeMasks);
                }
            };

    private BottomCreateButtonHider() {}

    public static void onActivityStarted(Activity activity) {
        scheduleBind(activity);
    }

    public static void onActivityResumed(Activity activity) {
        syncAllComposeMasks();
        scheduleBind(activity);
    }

    private static void scheduleBind(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ensurePrefListener();

        synchronized (BOUND_BOTTOM_TARGET) {
            WeakReference<View> existingRef = BOUND_BOTTOM_TARGET.get(activity);
            View existing = existingRef == null ? null : existingRef.get();
            if (existing != null && existing.isAttachedToWindow()) {
                syncComposeMaskForActivity(activity);
                return;
            }
        }

        synchronized (IN_FLIGHT) {
            if (IN_FLIGHT.containsKey(activity)) return;
            IN_FLIGHT.put(activity, Boolean.TRUE);
        }

        final WeakReference<Activity> ref = new WeakReference<>(activity);
        final boolean[] done = {false};
        for (long delay : RETRY_DELAYS_MS) {
            MAIN.postDelayed(() -> {
                if (done[0]) return;
                Activity current = ref.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) {
                    done[0] = true;
                    return;
                }
                try {
                    View decor = current.getWindow() == null ? null : current.getWindow().getDecorView();
                    if (decor != null && bindInTreeInternal(decor, current)) {
                        done[0] = true;
                        synchronized (IN_FLIGHT) {
                            IN_FLIGHT.remove(current);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "bind retry failed", t);
                }
            }, delay);
        }

        MAIN.postDelayed(() -> {
            Activity current = ref.get();
            if (current != null) {
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.remove(current);
                }
            }
        }, RETRY_DELAYS_MS[RETRY_DELAYS_MS.length - 1] + 200L);
    }

    public static boolean bindInTree(View root) {
        if (root == null) return false;
        ensurePrefListener();
        Activity activity = Utils.findActivity(root.getContext());
        return bindInTreeInternal(root, activity);
    }

    private static boolean bindInTreeInternal(View root, Activity activity) {
        if (root == null) return false;
        Context context = root.getContext();

        // Always bind any known or heuristic IDs without short-circuiting the bottom-bar scan.
        bindKnownAndHeuristicIds(root, context);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        if (screenW <= 0 || screenH <= 0) return false;

        // Priority 1: XML bottom-bar child View (View.GONE collapses slot cleanly).
        View xmlTarget = bindViaNeighborLca(root, screenW, screenH, dm.density);
        if (xmlTarget == null) {
            xmlTarget = bindViaBottomRow(root, screenW, screenH, dm);
        }
        if (xmlTarget != null) {
            recordBoundTarget(activity, xmlTarget);
            return true;
        }

        // Priority 2: Jetpack Compose bottom bar mask (when bar is drawn in one ComposeView).
        if (activity != null && bindViaComposeMask(root, activity, screenW, screenH, dm.density)) {
            return true;
        }
        return false;
    }

    private static void bindKnownAndHeuristicIds(View root, Context context) {
        int[] ids = ensureIds(context);
        for (int id : ids) {
            if (id == 0) continue;
            View target = root.findViewById(id);
            if (target != null) {
                HiddenView.attach(target, v -> Settings.HIDE_CREATE_BUTTON.get(), true, View.INVISIBLE);
            }
        }
        scanHeuristicCreateIds(root, 0);
    }

    private static void scanHeuristicCreateIds(View view, int depth) {
        if (view == null || depth > 24) return;
        if (isHeuristicCreateId(view)) {
            HiddenView.attach(view, v -> Settings.HIDE_CREATE_BUTTON.get(), true, View.INVISIBLE);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                scanHeuristicCreateIds(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static void recordBoundTarget(Activity activity, View target) {
        if (activity == null || target == null) return;
        synchronized (BOUND_BOTTOM_TARGET) {
            BOUND_BOTTOM_TARGET.put(activity, new WeakReference<>(target));
        }
    }

    private static int[] ensureIds(Context context) {
        int[] cached = resolvedIds;
        if (cached != null) return cached;
        int[] ids = new int[KNOWN_IDS.length];
        for (int i = 0; i < KNOWN_IDS.length; i++) {
            ids[i] = Utils.getResourceId(context, KNOWN_IDS[i], "id");
        }
        resolvedIds = ids;
        return ids;
    }

    private static View bindViaNeighborLca(View root, int screenW, int screenH, float density) {
        List<TextView> leftLabels = new ArrayList<>(4);
        List<TextView> rightLabels = new ArrayList<>(4);
        Rect rect = new Rect();
        collectBottomNavLabels(root, screenW, screenH, rect, leftLabels, rightLabels, 0);
        if (leftLabels.isEmpty() || rightLabels.isEmpty()) return null;

        int maxDy = Math.round(36f * density);
        int minItemW = Math.round(24f * density);
        int minItemH = Math.round(20f * density);
        Rect leftRect = new Rect();
        Rect rightRect = new Rect();
        Rect candRect = new Rect();

        for (TextView leftTv : leftLabels) {
            if (!leftTv.getGlobalVisibleRect(leftRect)) continue;
            float leftCy = (leftRect.top + leftRect.bottom) * 0.5f;
            for (TextView rightTv : rightLabels) {
                if (!rightTv.getGlobalVisibleRect(rightRect)) continue;
                float rightCy = (rightRect.top + rightRect.bottom) * 0.5f;
                if (Math.abs(leftCy - rightCy) > maxDy) continue;

                ViewGroup lca = lowestCommonAncestor(leftTv, rightTv);
                if (lca == null) continue;
                View leftChild = directChildContaining(lca, leftTv);
                View rightChild = directChildContaining(lca, rightTv);

                for (int i = 0; i < lca.getChildCount(); i++) {
                    View child = lca.getChildAt(i);
                    if (child == null || child == leftChild || child == rightChild) continue;
                    if (child.getVisibility() != View.VISIBLE || !child.getGlobalVisibleRect(candRect)) continue;
                    int cw = candRect.width();
                    int ch = candRect.height();
                    if (cw < minItemW || cw >= screenW * 0.42f || ch < minItemH) continue;
                    float cx = (candRect.left + candRect.right) * 0.5f;
                    if (Math.abs(cx - screenW * 0.5f) > screenW * 0.14f) continue;
                    if (cx <= leftRect.left || cx >= rightRect.right) continue;
                    if (hasAnyNavLabelText(child)) continue;

                    HiddenView.attach(child, v -> Settings.HIDE_CREATE_BUTTON.get(), true, View.INVISIBLE);
                    Log.i(TAG, "Bound bottom create button via LCA: " + describeView(child));
                    return child;
                }
            }
        }
        return null;
    }

    private static void collectBottomNavLabels(
            View view,
            int screenW,
            int screenH,
            Rect rect,
            List<TextView> leftOut,
            List<TextView> rightOut,
            int depth
    ) {
        if (view == null || depth > 28 || !view.isShown()) return;
        if (view instanceof TextView tv) {
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0 && tv.getGlobalVisibleRect(rect)
                    && rect.bottom >= screenH * 0.75f && rect.height() > 0) {
                float cx = (rect.left + rect.right) * 0.5f;
                String s = cs.toString().trim().toLowerCase(Locale.ROOT);
                if (cx < screenW * 0.48f && isLeftNavLabel(s)) {
                    leftOut.add(tv);
                } else if (cx > screenW * 0.52f && isRightNavLabel(s)) {
                    rightOut.add(tv);
                }
            }
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectBottomNavLabels(group.getChildAt(i), screenW, screenH, rect, leftOut, rightOut, depth + 1);
            }
        }
    }

    private static boolean isLeftNavLabel(String s) {
        return s.startsWith("ホーム") || s.equals("home")
                || s.startsWith("ブラウズ") || s.equals("browse")
                || s.startsWith("フォロー") || s.startsWith("following")
                || s.startsWith("ディスカバー") || s.equals("discover");
    }

    private static boolean isRightNavLabel(String s) {
        return s.startsWith("アクティビ") || s.startsWith("activity")
                || s.startsWith("通知") || s.startsWith("notification")
                || s.startsWith("プロフィール") || s.equals("profile")
                || s.startsWith("受信トレイ") || s.equals("inbox");
    }

    private static boolean hasAnyNavLabelText(View view) {
        if (view instanceof TextView tv && tv.getVisibility() == View.VISIBLE) {
            CharSequence cs = tv.getText();
            if (cs != null) {
                String s = cs.toString().trim().toLowerCase(Locale.ROOT);
                if (isLeftNavLabel(s) || isRightNavLabel(s)) return true;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasAnyNavLabelText(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static ViewGroup lowestCommonAncestor(View a, View b) {
        List<ViewGroup> ancestorsA = new ArrayList<>(16);
        ViewParent p = a.getParent();
        while (p instanceof ViewGroup vg) {
            ancestorsA.add(vg);
            p = vg.getParent();
        }
        p = b.getParent();
        while (p instanceof ViewGroup vg) {
            if (ancestorsA.contains(vg)) return vg;
            p = vg.getParent();
        }
        return null;
    }

    private static View directChildContaining(ViewGroup parent, View descendant) {
        View cur = descendant;
        while (cur != null) {
            ViewParent p = cur.getParent();
            if (p == parent) return cur;
            cur = p instanceof View ? (View) p : null;
        }
        return null;
    }

    private static View bindViaBottomRow(View view, int screenW, int screenH, DisplayMetrics dm) {
        int maxBarH = Math.round(160f * dm.density);
        int minItemW = Math.round(24f * dm.density);
        int minItemH = Math.round(20f * dm.density);
        Rect rect = new Rect();
        return scanBottomRow(view, screenW, screenH, maxBarH, minItemW, minItemH, rect, 0);
    }

    private static View scanBottomRow(
            View view,
            int screenW,
            int screenH,
            int maxBarH,
            int minItemW,
            int minItemH,
            Rect rect,
            int depth
    ) {
        if (!(view instanceof ViewGroup group) || depth > 28 || !view.isShown()) return null;
        String cls = group.getClass().getName().toLowerCase(Locale.ROOT);
        if (cls.contains("composeview")) return null;

        if (isBottomNavContainer(group, cls, screenW, screenH, maxBarH, rect)) {
            View matched = bindMatchingRowItems(group, rect, screenW, minItemW, minItemH);
            if (matched != null) return matched;
        }
        int childCount = group.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View matched = scanBottomRow(
                    group.getChildAt(i), screenW, screenH, maxBarH, minItemW, minItemH, rect, depth + 1);
            if (matched != null) return matched;
        }
        return null;
    }

    private static boolean isBottomNavContainer(
            ViewGroup group,
            String cls,
            int screenW,
            int screenH,
            int maxBarH,
            Rect rect
    ) {
        if (!group.getGlobalVisibleRect(rect)) return false;
        int w = rect.width();
        int h = rect.height();
        if (w < screenW * 0.70f || h <= 0 || h > maxBarH) return false;
        if (rect.bottom >= screenH * 0.78f) return true;
        return cls.contains("bottomnavigation") || cls.contains("navigationbar");
    }

    private static final class RowItem {
        final View view;
        final int left;
        final float centerX;

        RowItem(View view, int left, float centerX) {
            this.view = view;
            this.left = left;
            this.centerX = centerX;
        }
    }

    private static View bindMatchingRowItems(
            ViewGroup bar,
            Rect barRect,
            int screenW,
            int minItemW,
            int minItemH
    ) {
        int barW = barRect.width();
        int count = bar.getChildCount();
        List<RowItem> items = new ArrayList<>(count);
        Rect itemRect = new Rect();
        for (int i = 0; i < count; i++) {
            View child = bar.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (!child.getGlobalVisibleRect(itemRect)) continue;
            if (itemRect.width() < minItemW || itemRect.width() > barW * 0.45f
                    || itemRect.height() < minItemH) {
                continue;
            }
            if (isHeuristicCreateId(child) || hasCreateLabel(child)) {
                HiddenView.attach(child, v -> Settings.HIDE_CREATE_BUTTON.get(), true, View.INVISIBLE);
                Log.i(TAG, "Bound bottom create button via row label/id: " + describeView(child));
                return child;
            }
            items.add(new RowItem(child, itemRect.left, (itemRect.left + itemRect.right) * 0.5f));
        }
        if (items.size() == 5 || items.size() == 3) {
            items.sort(Comparator.comparingInt(item -> item.left));
            int mid = items.size() / 2;
            RowItem centerItem = items.get(mid);
            if (Math.abs(centerItem.centerX - screenW * 0.5f) <= screenW * 0.14f) {
                View center = centerItem.view;
                View left = items.get(mid - 1).view;
                View right = items.get(mid + 1).view;
                if (!hasVisibleText(center)
                        && (hasVisibleText(left) || hasClickableDescendant(left))
                        && (hasVisibleText(right) || hasClickableDescendant(right))) {
                    HiddenView.attach(center, v -> Settings.HIDE_CREATE_BUTTON.get(), true, View.INVISIBLE);
                    Log.i(TAG, "Bound bottom create button via center slot: " + describeView(center));
                    return center;
                }
            }
        }
        return null;
    }

    // Priority 2: Jetpack Compose bottom bar mask.

    private static boolean bindViaComposeMask(
            View root,
            Activity activity,
            int screenW,
            int screenH,
            float density
    ) {
        if (root.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return false;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout decorFrame)) return false;
        if (hasVisibleMessageInput(decorFrame)) return false;

        boolean activityIsMain = isMainActivityClass(activity);
        boolean hasMainMarker = activityIsMain || hasAnyMainScreenMarker(decorFrame);
        View composeBar = findBottomComposeBar(decorFrame, screenW, screenH, density, hasMainMarker, 0);
        if (composeBar == null) return false;

        ComposeMaskController controller;
        synchronized (COMPOSE_MASKS) {
            controller = COMPOSE_MASKS.get(activity);
            if (controller == null) {
                controller = new ComposeMaskController(activity, decorFrame, composeBar);
                COMPOSE_MASKS.put(activity, controller);
            } else {
                controller.bindComposeBar(composeBar);
            }
        }
        controller.sync();
        recordBoundTarget(activity, composeBar);
        Log.i(TAG, "Bound bottom create button via Compose mask: " + describeView(composeBar));
        return true;
    }

    private static View findBottomComposeBar(
            View view,
            int screenW,
            int screenH,
            float density,
            boolean hasMainMarker,
            int depth
    ) {
        if (view == null || depth > 24 || !view.isShown()) return null;
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        if (cls.contains("composeview") && !cls.contains("androidcomposeview")) {
            Rect rect = new Rect();
            int minH = Math.round(44f * density);
            int maxH = Math.round(150f * density);
            if (view.getGlobalVisibleRect(rect)
                    && rect.width() >= screenW * 0.90f
                    && rect.height() >= minH && rect.height() <= maxH
                    && rect.bottom >= screenH * 0.85f
                    && !hasVisibleMessageInput(view)
                    && (hasMainMarker || hasNavRelatedIdInAncestors(view))) {
                return view;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findBottomComposeBar(
                        group.getChildAt(i), screenW, screenH, density, hasMainMarker, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isMainActivityClass(Activity activity) {
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("main") || name.contains("home") || name.contains("landing") || name.contains("nav");
    }

    private static boolean hasAnyMainScreenMarker(View root) {
        Context context = root.getContext();
        for (String name : MAIN_SCREEN_MARKERS) {
            int id = Utils.getResourceId(context, name, "id");
            if (id != 0 && root.findViewById(id) != null) return true;
        }
        return false;
    }

    private static boolean hasNavRelatedIdInAncestors(View view) {
        View cur = view;
        for (int i = 0; i < 4 && cur != null; i++) {
            int id = cur.getId();
            if (id != View.NO_ID) {
                try {
                    String entry = cur.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
                    if (entry.contains("nav") || entry.contains("bottom")
                            || entry.contains("tab") || entry.contains("bar") || entry.contains("main")) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static boolean hasVisibleMessageInput(View root) {
        int id = Utils.getResourceId(root.getContext(), "message_input", "id");
        if (id == 0) return false;
        View v = root.findViewById(id);
        return v != null && v.isShown();
    }

    private static void ensurePrefListener() {
        if (prefListenerRegistered) return;
        prefListenerRegistered = true;
        Setting.addChangeListener(PREF_LISTENER);
    }

    private static void syncAllComposeMasks() {
        List<ComposeMaskController> snapshot;
        synchronized (COMPOSE_MASKS) {
            snapshot = new ArrayList<>(COMPOSE_MASKS.values());
        }
        for (ComposeMaskController controller : snapshot) {
            if (controller != null) controller.sync();
        }
    }

    private static void syncComposeMaskForActivity(Activity activity) {
        ComposeMaskController controller;
        synchronized (COMPOSE_MASKS) {
            controller = COMPOSE_MASKS.get(activity);
        }
        if (controller != null) controller.sync();
    }

    private static final class ComposeMaskController
            implements ViewTreeObserver.OnPreDrawListener, View.OnLayoutChangeListener {
        private final WeakReference<Activity> activityRef;
        private final FrameLayout decor;
        private final int[] cvLoc = new int[2];
        private final int[] decorLoc = new int[2];
        private WeakReference<View> composeBarRef;
        private View maskView;
        private ViewTreeObserver preDrawObserver;
        private int lastLeft = Integer.MIN_VALUE;
        private int lastTop = Integer.MIN_VALUE;
        private int lastWidth = Integer.MIN_VALUE;
        private int lastHeight = Integer.MIN_VALUE;
        private int lastColor = 0;

        ComposeMaskController(Activity activity, FrameLayout decor, View composeBar) {
            this.activityRef = new WeakReference<>(activity);
            this.decor = decor;
            bindComposeBar(composeBar);
        }

        void bindComposeBar(View composeBar) {
            View current = composeBarRef == null ? null : composeBarRef.get();
            if (current != composeBar) {
                composeBarRef = new WeakReference<>(composeBar);
                composeBar.addOnLayoutChangeListener(this);
                ViewParent p = composeBar.getParent();
                if (p instanceof View pv) pv.addOnLayoutChangeListener(this);
                decor.addOnLayoutChangeListener(this);
            }
        }

        private View ensureMaskView(Context context) {
            if (maskView != null && maskView.getParent() == decor) return maskView;
            View existing = decor.findViewWithTag(COMPOSE_MASK_TAG);
            if (existing != null) {
                maskView = existing;
                return existing;
            }
            View mask = new View(context);
            mask.setTag(COMPOSE_MASK_TAG);
            mask.setClickable(true);
            mask.setFocusable(false);
            mask.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT);
            decor.addView(mask, lp);
            maskView = mask;
            return mask;
        }

        void sync() {
            Activity activity = activityRef.get();
            View cv = composeBarRef == null ? null : composeBarRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || cv == null) {
                hideMask();
                detachPreDraw();
                return;
            }

            boolean settingOn = Settings.HIDE_CREATE_BUTTON.get();
            if (!settingOn || !cv.isAttachedToWindow()) {
                hideMask();
                detachPreDraw();
                return;
            }

            attachPreDraw();

            int screenW = cv.getResources().getDisplayMetrics().widthPixels;
            float density = cv.getResources().getDisplayMetrics().density;
            boolean portrait = cv.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
            int cvW = cv.getWidth();
            int cvH = cv.getHeight();
            int minH = Math.round(44f * density);
            int maxH = Math.round(150f * density);

            if (!portrait || !cv.isShown() || cv.getAlpha() < 0.5f
                    || cvW < screenW * 0.90f || cvH < minH || cvH > maxH
                    || hasVisibleMessageInput(decor)) {
                hideMask();
                return;
            }

            cv.getLocationInWindow(cvLoc);
            decor.getLocationInWindow(decorLoc);
            int relLeft = cvLoc[0] - decorLoc[0];
            int relTop = cvLoc[1] - decorLoc[1];
            int decorH = decor.getHeight();
            if (decorH > 0 && relTop + cvH < Math.round(decorH * 0.85f)) {
                hideMask();
                return;
            }

            int topBorderPx = Math.round(2f * density);
            int left = relLeft + Math.round(cvW * 0.402f);
            int width = Math.round(cvW * 0.196f);
            int top = relTop + cv.getPaddingTop() + topBorderPx;
            int height = Math.max(0, cvH - cv.getPaddingTop() - cv.getPaddingBottom() - topBorderPx);
            if (width <= 0 || height <= 0) {
                hideMask();
                return;
            }

            View mask = ensureMaskView(cv.getContext());
            int color = resolveBarColor(cv);
            if (color != lastColor) {
                lastColor = color;
                mask.setBackgroundColor(color);
            }
            if (left != lastLeft || top != lastTop || width != lastWidth || height != lastHeight) {
                lastLeft = left;
                lastTop = top;
                lastWidth = width;
                lastHeight = height;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mask.getLayoutParams();
                if (lp == null) {
                    lp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
                } else {
                    lp.gravity = Gravity.TOP | Gravity.LEFT;
                    lp.width = width;
                    lp.height = height;
                }
                lp.leftMargin = left;
                lp.topMargin = top;
                mask.setLayoutParams(lp);
            }
            float tx = cv.getTranslationX();
            float ty = cv.getTranslationY();
            float elev = Math.max(cv.getElevation() + 1f, 8f * density);
            if (mask.getTranslationX() != tx) mask.setTranslationX(tx);
            if (mask.getTranslationY() != ty) mask.setTranslationY(ty);
            if (mask.getElevation() != elev) mask.setElevation(elev);
            if (mask.getVisibility() != View.VISIBLE) {
                mask.setVisibility(View.VISIBLE);
            }
            if (decor.indexOfChild(mask) != decor.getChildCount() - 1) {
                mask.bringToFront();
            }
        }

        private void hideMask() {
            if (maskView != null && maskView.getVisibility() != View.GONE) {
                maskView.setVisibility(View.GONE);
            }
        }

        private void attachPreDraw() {
            if (!decor.isAttachedToWindow()) return;
            if (preDrawObserver != null && preDrawObserver.isAlive()) return;
            ViewTreeObserver vto = decor.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.addOnPreDrawListener(this);
                preDrawObserver = vto;
            }
        }

        private void detachPreDraw() {
            if (preDrawObserver != null) {
                if (preDrawObserver.isAlive()) {
                    preDrawObserver.removeOnPreDrawListener(this);
                }
                preDrawObserver = null;
            }
        }

        @Override
        public boolean onPreDraw() {
            sync();
            return true;
        }

        @Override
        public void onLayoutChange(
                View v, int l, int t, int r, int b, int ol, int ot, int or, int ob
        ) {
            sync();
        }
    }

    private static int resolveBarColor(View composeBar) {
        Integer bg = opaqueBackgroundColor(composeBar);
        if (bg != null) return bg;
        if (composeBar.getParent() instanceof View parentView) {
            bg = opaqueBackgroundColor(parentView);
            if (bg != null) return bg;
        }
        return SettingsUi.isDark(composeBar.getContext()) ? 0xFF0E0E10 : Color.WHITE;
    }

    private static Integer opaqueBackgroundColor(View view) {
        Drawable d = view.getBackground();
        if (d instanceof ColorDrawable cd) {
            int color = cd.getColor();
            if (Color.alpha(color) >= 240) return color;
        }
        return null;
    }

    private static boolean isHeuristicCreateId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            if (name.contains("clip") || name.contains("account") || name.contains("channel")
                    || name.contains("poll") || name.contains("prediction")) {
                return false;
            }
            return name.contains("create") || name.contains("go_live") || name.contains("broadcast");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasCreateLabel(View view) {
        if (matchesCreateText(view.getContentDescription())) return true;
        if (view instanceof TextView tv && matchesCreateText(tv.getText())) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasCreateLabel(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean matchesCreateText(CharSequence cs) {
        if (cs == null || cs.length() == 0) return false;
        String text = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (text.contains("clip") || text.contains("クリップ")) return false;
        return text.contains("create")
                || text.contains("go live")
                || text.contains("broadcast")
                || text.contains("作成")
                || text.contains("配信開始")
                || text.contains("ライブ配信")
                || text.equals("配信")
                || text.equals("+")
                || text.equals("＋");
    }

    private static boolean hasVisibleText(View view) {
        if (view == null) return false;
        if (view instanceof TextView tv) {
            CharSequence t = tv.getText();
            if (t != null && t.toString().trim().length() > 0 && tv.getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleText(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean hasClickableDescendant(View view) {
        if (view == null) return false;
        if (view.isClickable() && view.getVisibility() == View.VISIBLE) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasClickableDescendant(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static String describeView(View view) {
        String idName = "no_id";
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                idName = view.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {}
        }
        return view.getClass().getSimpleName() + "(" + idName + ")";
    }
}
