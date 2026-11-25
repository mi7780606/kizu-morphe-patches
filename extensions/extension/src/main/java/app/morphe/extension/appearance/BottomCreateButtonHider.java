package app.morphe.extension.appearance;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;

/**
 * Hides Twitch's home bottom-center "+" (Create / Go Live) navigation button while keeping
 * its visibility restorable when {@link Settings#HIDE_CREATE_BUTTON} is turned off.
 */
public final class BottomCreateButtonHider {
    private static final String[] KNOWN_IDS = {
            "create_button",
            "following_nav_rail_create_button",
            "bottom_nav_create_button",
            "nav_create_button",
            "create_nav_item",
            "navigation_create",
            "creator_mode_button",
    };

    private static final long[] RETRY_DELAYS_MS = {0L, 300L, 1000L};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> SCHEDULED = new WeakHashMap<>();

    private static volatile int[] resolvedIds;

    private BottomCreateButtonHider() {}

    public static void onActivityStarted(Activity activity) {
        if (activity == null) return;
        synchronized (SCHEDULED) {
            if (SCHEDULED.containsKey(activity)) return;
            SCHEDULED.put(activity, Boolean.TRUE);
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
                View decor = current.getWindow() == null ? null : current.getWindow().getDecorView();
                if (decor != null && bindInTree(decor)) {
                    done[0] = true;
                }
            }, delay);
        }
    }

    public static boolean bindInTree(View root) {
        if (root == null) return false;
        Context context = root.getContext();
        int[] ids = ensureIds(context);
        boolean found = false;
        for (int id : ids) {
            if (id == 0) continue;
            View target = root.findViewById(id);
            if (target != null) {
                HiddenView.attach(target, v -> Settings.HIDE_CREATE_BUTTON.get(), true);
                found = true;
            }
        }
        if (found) return true;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        if (screenW <= 0 || screenH <= 0) return false;
        int maxBarH = Math.round(110f * dm.density);
        Rect rect = new Rect();
        return scanBottomBar(root, screenW, screenH, maxBarH, rect, 0);
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

    private static boolean scanBottomBar(
            View view,
            int screenW,
            int screenH,
            int maxBarH,
            Rect rect,
            int depth
    ) {
        if (!(view instanceof ViewGroup group) || depth > 14 || !view.isShown()) return false;

        int childCount = group.getChildCount();
        if (childCount >= 3 && childCount <= 6 && isBottomNavContainer(group, screenW, screenH, maxBarH, rect)) {
            if (bindMatchingTab(group, screenW, rect)) {
                return true;
            }
        }
        for (int i = 0; i < childCount; i++) {
            if (scanBottomBar(group.getChildAt(i), screenW, screenH, maxBarH, rect, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBottomNavContainer(
            ViewGroup group,
            int screenW,
            int screenH,
            int maxBarH,
            Rect rect
    ) {
        if (!group.getGlobalVisibleRect(rect)) return false;
        int w = rect.width();
        int h = rect.height();
        if (w < screenW * 0.70f || h <= 0 || h > maxBarH) return false;
        if (rect.bottom >= screenH * 0.82f) return true;
        String cls = group.getClass().getName().toLowerCase(Locale.ROOT);
        return cls.contains("bottomnavigation") || cls.contains("navigationbar");
    }

    private static boolean bindMatchingTab(ViewGroup bar, int screenW, Rect rect) {
        int count = bar.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = bar.getChildAt(i);
            if (child == null) continue;
            if (isCreateResourceId(child)) {
                HiddenView.attach(child, v -> Settings.HIDE_CREATE_BUTTON.get(), true);
                return true;
            }
        }
        if (count % 2 == 1) {
            int mid = count / 2;
            View centerChild = bar.getChildAt(mid);
            if (centerChild != null && centerChild.getGlobalVisibleRect(rect)) {
                float childCenter = (rect.left + rect.right) * 0.5f;
                float screenCenter = screenW * 0.5f;
                if (Math.abs(childCenter - screenCenter) <= screenW * 0.15f) {
                    if (hasCreateLabel(centerChild) || isUnlabeledCenterCreateTab(bar, mid, centerChild)) {
                        HiddenView.attach(centerChild, v -> Settings.HIDE_CREATE_BUTTON.get(), true);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isCreateResourceId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            if (name.contains("clip")) return false;
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
                || text.contains("stream")
                || text.contains("作成")
                || text.contains("配信開始")
                || text.contains("ライブ配信")
                || text.equals("配信")
                || text.equals("+")
                || text.equals("＋");
    }

    private static boolean isUnlabeledCenterCreateTab(ViewGroup bar, int mid, View centerChild) {
        if (hasVisibleText(centerChild)) return false;
        View left = mid > 0 ? bar.getChildAt(mid - 1) : null;
        View right = mid + 1 < bar.getChildCount() ? bar.getChildAt(mid + 1) : null;
        return hasVisibleText(left) || hasVisibleText(right);
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
}
