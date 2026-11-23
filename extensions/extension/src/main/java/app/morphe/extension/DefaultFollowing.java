package app.morphe.extension;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.morphe.extension.settings.Settings;

/**
 * Selects Twitch's native Home -> Following tab when a Home activity opens.
 * When "following" is selected, Twitch's native DefaultHomeTabPatch bytecode hook handles it
 * directly, so zero view scanning or delayed polling runs.
 */
public final class DefaultFollowing {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> SCHEDULED = new WeakHashMap<>();

    private DefaultFollowing() {}

    public static boolean useNativeFollowing() {
        try {
            String value = Settings.DEFAULT_HOME_TAB.get();
            return !"live".equalsIgnoreCase(value) && !"clips".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void onActivityStarted(Activity activity) {
        if (useNativeFollowing()) return;
        schedule(activity);
    }

    private static void schedule(final Activity activity) {
        if (activity == null) return;
        synchronized (SCHEDULED) {
            if (SCHEDULED.containsKey(activity)) return;
            SCHEDULED.put(activity, Boolean.TRUE);
        }

        final WeakReference<Activity> ref = new WeakReference<>(activity);
        final boolean[] done = {false};
        long[] delays = {500L, 1000L, 1800L, 3000L, 5000L, 8000L};
        for (final long delay : delays) {
            MAIN.postDelayed(() -> {
                if (done[0]) return;
                try {
                    Activity current = ref.get();
                    if (current == null || current.isFinishing() || current.isDestroyed()) {
                        done[0] = true;
                        return;
                    }
                    if (selectFollowing(current)) {
                        done[0] = true;
                    }
                } catch (Throwable ignored) {}
            }, delay);
        }
    }

    private static boolean selectFollowing(Activity activity) {
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        View strip = findTabStrip(root);
        if (!(strip instanceof ViewGroup stripGroup) || stripGroup.getChildCount() == 0) return false;

        View rowView = stripGroup.getChildAt(0);
        if (!(rowView instanceof ViewGroup row)) return false;

        String tabEntryName = Settings.DEFAULT_HOME_TAB.get();
        if (!"live".equalsIgnoreCase(tabEntryName) && !"clips".equalsIgnoreCase(tabEntryName)) {
            tabEntryName = "following";
        }

        String desiredLabel = getStringByEntryName(activity, tabEntryName);
        if (desiredLabel == null) desiredLabel = tabEntryName;

        View desiredTab = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            String caption = firstCaption(tab);
            if (caption != null && desiredLabel.equalsIgnoreCase(caption.trim())) {
                desiredTab = tab;
                break;
            }
        }

        if (desiredTab == null) return false;
        if (isSelected(desiredTab)) return true;

        try {
            View target = nearestClickable(desiredTab);
            if (target != null && target.isEnabled() && target.performClick()) return true;
            if (desiredTab.isEnabled() && desiredTab.performClick()) return true;
        } catch (Throwable ignored) {}
        return isSelected(desiredTab);
    }

    private static View findTabStrip(View root) {
        if (root == null) return null;
        int id1 = Utils.getResourceId(root.getContext(), "tab_layout", "id");
        if (id1 != 0) {
            View v = root.findViewById(id1);
            if (v != null && v.isShown()) return v;
        }
        int id2 = Utils.getResourceId(root.getContext(), "tab_layout_no_tab_width", "id");
        if (id2 != 0) {
            View v = root.findViewById(id2);
            if (v != null && v.isShown()) return v;
        }
        return null;
    }

    private static String firstCaption(View view) {
        if (view instanceof TextView tv) {
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0) return text.toString();
        }

        CharSequence contentDescription = view.getContentDescription();
        if (contentDescription != null && contentDescription.length() > 0) {
            return contentDescription.toString();
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                String nested = firstCaption(group.getChildAt(i));
                if (nested != null && !nested.trim().isEmpty()) return nested;
            }
        }
        return null;
    }

    private static boolean isSelected(View view) {
        View current = view;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isSelected()) return true;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return false;
    }

    private static View nearestClickable(View view) {
        View current = view;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable()) return current;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return null;
    }

    private static String getStringByEntryName(Activity activity, String entryName) {
        try {
            int id = Utils.getResourceId(activity, entryName, "string");
            if (id != 0) return activity.getResources().getString(id).trim();
        } catch (Throwable ignored) {}
        return null;
    }
}
