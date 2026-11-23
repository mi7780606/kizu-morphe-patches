package io.github.bakwudo.uyu.extension.danmaku;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.WeakHashMap;

import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.Settings;

/**
 * Keeps Twitch's landscape chat off and adjusts custom landscape chat width/opacity.
 */
@SuppressWarnings("unused")
public final class LandscapeChatPatch {
    private static final String LANDSCAPE_CHAT_MODE_KEY = "pref_landscape_chat_mode";
    private static final String CHAT_HIDDEN = "Hidden";
    private static final String ONE_CHAT_INTRODUCED_KEY = "pref_onechat_education_dialog_shown";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object STATE_LOCK = new Object();
    private static final WeakHashMap<View, Integer> ORIGINAL_WIDTHS = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> ORIGINAL_ALPHAS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> SCHEDULED = new WeakHashMap<>();
    private static final long[] RETRIES_MS = {0L, 400L, 1200L, 2800L};
    private static final String[] CHAT_PANEL_IDS = {"chat_wrapper", "one_chat_overlay_container"};

    private LandscapeChatPatch() {}

    /** Apply the landscape chat controls after Twitch has finished building the theatre view. */
    public static void onActivityStarted(Activity activity) {
        if (activity == null) return;
        boolean sizeOn = Settings.LANDSCAPE_CHAT_SIZE_ENABLED.get();
        boolean opacityOn = Settings.LANDSCAPE_CHAT_OPACITY_ENABLED.get();
        if (!sizeOn && !opacityOn) {
            restoreLandscapeControls();
            return;
        }
        if (activity.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            restoreLandscapeControls();
            return;
        }
        synchronized (SCHEDULED) {
            if (SCHEDULED.containsKey(activity)) return;
            SCHEDULED.put(activity, Boolean.TRUE);
        }
        final WeakReference<Activity> ref = new WeakReference<>(activity);
        final boolean[] done = {false};
        for (long delay : RETRIES_MS) {
            MAIN.postDelayed(() -> {
                if (done[0]) return;
                Activity current = ref.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) {
                    done[0] = true;
                    return;
                }
                try {
                    if (applyLandscapeControls(current)) done[0] = true;
                } catch (Throwable ex) {
                    Utils.logError("Landscape chat adjustment failed", ex);
                }
            }, delay);
        }
        MAIN.postDelayed(() -> {
            Activity current = ref.get();
            if (current != null) {
                synchronized (SCHEDULED) { SCHEDULED.remove(current); }
            }
        }, RETRIES_MS[RETRIES_MS.length - 1] + 200L);
    }

    private static boolean applyLandscapeControls(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return true;
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return false;
        boolean landscape = root.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean sizeOn = Settings.LANDSCAPE_CHAT_SIZE_ENABLED.get();
        boolean opacityOn = Settings.LANDSCAPE_CHAT_OPACITY_ENABLED.get();
        if (!landscape || (!sizeOn && !opacityOn)) {
            restoreLandscapeControls();
            return !landscape || (!sizeOn && !opacityOn);
        }
        View chat = findChatPanel(root);
        if (chat == null) return false;
        applyToChatView(root, chat, sizeOn, opacityOn);
        return true;
    }

    private static void applyToChatView(View root, View chat, boolean sizeOn, boolean opacityOn) {
        synchronized (STATE_LOCK) {
            if (!ORIGINAL_WIDTHS.containsKey(chat)) {
                ViewGroup.LayoutParams lp = chat.getLayoutParams();
                if (lp != null) ORIGINAL_WIDTHS.put(chat, lp.width);
                ORIGINAL_ALPHAS.put(chat, chat.getAlpha());
            }
        }
        if (sizeOn) {
            ViewGroup.LayoutParams lp = chat.getLayoutParams();
            if (lp != null && root.getWidth() > 0) {
                int targetWidth = Math.max(1, Math.round(root.getWidth()
                        * Settings.LANDSCAPE_CHAT_SIZE.get() / 100f));
                if (lp.width != targetWidth) {
                    lp.width = targetWidth;
                    if (lp instanceof android.widget.LinearLayout.LayoutParams llp) {
                        llp.weight = 0f;
                    }
                    chat.setLayoutParams(lp);
                }
            }
        }
        if (opacityOn) {
            float alpha = Math.max(0f, Math.min(1f, Settings.LANDSCAPE_CHAT_OPACITY.get() / 100f));
            if (chat.getAlpha() != alpha) chat.setAlpha(alpha);
        }
    }

    private static void restoreLandscapeControls() {
        synchronized (STATE_LOCK) {
            if (ORIGINAL_WIDTHS.isEmpty()) return;
            for (View chat : ORIGINAL_WIDTHS.keySet()) {
                try {
                    Integer width = ORIGINAL_WIDTHS.get(chat);
                    ViewGroup.LayoutParams lp = chat.getLayoutParams();
                    if (width != null && lp != null) {
                        lp.width = width;
                        chat.setLayoutParams(lp);
                    }
                    Float alpha = ORIGINAL_ALPHAS.get(chat);
                    if (alpha != null) chat.setAlpha(alpha);
                } catch (Throwable ignored) {}
            }
            ORIGINAL_WIDTHS.clear();
            ORIGINAL_ALPHAS.clear();
        }
    }

    private static View findChatPanel(View root) {
        if (!(root instanceof ViewGroup) || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        int rootWidth = root.getWidth();
        int rootHeight = root.getHeight();
        Rect rect = new Rect();
        for (String name : CHAT_PANEL_IDS) {
            int id = Utils.getResourceId(root.getContext(), name, "id");
            if (id == 0) continue;
            View direct = root.findViewById(id);
            if (direct != null && direct.isShown() && direct.getGlobalVisibleRect(rect)
                    && rect.width() >= rootWidth * 0.15f && rect.width() <= rootWidth * 0.75f
                    && rect.height() >= rootHeight * 0.35f && rect.right >= rootWidth * 0.80f) {
                return direct;
            }
        }
        Candidate best = new Candidate();
        collectCandidates((ViewGroup) root, rootWidth, rootHeight, rect, best);
        return best.view;
    }

    private static void collectCandidates(
            ViewGroup group, int rootWidth, int rootHeight, Rect r, Candidate best) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ViewGroup childGroup) {
                if (child.getGlobalVisibleRect(r)
                        && r.width() >= rootWidth * 0.15f && r.width() <= rootWidth * 0.75f
                        && r.height() >= rootHeight * 0.35f && r.right >= rootWidth * 0.80f
                        && containsChatSignal(child, 0)) {
                    float score = (r.right / (float) rootWidth) * 4f
                            + r.width() / (float) rootWidth
                            + r.height() / (float) rootHeight;
                    if (score > best.score) {
                        best.score = score;
                        best.view = child;
                    }
                    continue;
                }
                collectCandidates(childGroup, rootWidth, rootHeight, r, best);
            }
        }
    }

    private static boolean containsChatSignal(View view, int depth) {
        if (view == null || depth > 12) return false;
        String className = view.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("chat")) return true;
        if (containsChatWord(view.getContentDescription())) return true;
        if (view instanceof TextView tv) {
            if (containsChatWord(tv.getText()) || containsChatWord(tv.getHint())) return true;
        }
        if (view instanceof EditText et && containsChatWord(et.getHint())) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsChatSignal(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean containsChatWord(CharSequence value) {
        if (value == null) return false;
        String text = value.toString().toLowerCase(Locale.ROOT);
        return text.contains("send a message") || text.contains("send message")
                || text.equals("chat") || text.contains("chat message")
                || text.contains("chat input");
    }

    private static final class Candidate {
        View view;
        float score = -Float.MAX_VALUE;
    }

    public static String overridePreference(String key) {
        if (!LANDSCAPE_CHAT_MODE_KEY.equals(key)) return null;
        return Settings.DANMAKU_HIDE_LANDSCAPE_CHAT.get() ? CHAT_HIDDEN : null;
    }

    public static boolean overrideBooleanPreference(String key, boolean value) {
        if (ONE_CHAT_INTRODUCED_KEY.equals(key) && Settings.DANMAKU_HIDE_LANDSCAPE_CHAT.get()) return true;
        return value;
    }

    public static boolean isLandscapeChatHidden() {
        return Settings.DANMAKU_HIDE_LANDSCAPE_CHAT.get();
    }

    public static void onBottomControlsRendered(Object bottomControlsViewDelegate) {
        try {
            View root = ViewDelegates.rootView(bottomControlsViewDelegate);
            if (root == null) return;
            boolean landscape = root.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            if (!landscape) {
                restoreLandscapeControls();
                return;
            }
            if (Settings.DANMAKU_HIDE_LANDSCAPE_CHAT.get()) {
                View button = root.findViewById(Utils.getResourceId(root.getContext(), "chat_mode_button", "id"));
                if (button != null) button.setVisibility(View.GONE);
            }
            boolean sizeOn = Settings.LANDSCAPE_CHAT_SIZE_ENABLED.get();
            boolean opacityOn = Settings.LANDSCAPE_CHAT_OPACITY_ENABLED.get();
            if (sizeOn || opacityOn) {
                View decor = root.getRootView();
                if (decor != null) {
                    View chat = findChatPanel(decor);
                    if (chat != null) applyToChatView(decor, chat, sizeOn, opacityOn);
                }
            }
        } catch (Exception ex) {
            Utils.logError("Failed to handle landscape bottom controls", ex);
        }
    }
}
