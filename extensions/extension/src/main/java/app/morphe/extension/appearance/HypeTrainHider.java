package app.morphe.extension.appearance;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.twitch.chat.ChatHistoryStarter;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

public final class HypeTrainHider {
    private static final String TAG = "KizuHypeTrain";

    public static final boolean ENABLED = true;
    private static final String[] ID_KEYWORDS = {"hype_train", "hypetrain"};
    private static final String[] TEXT_KEYWORDS = {"ハイプトレイン", "hype train", "hype-train"};
    private static final long SCAN_DELAY_MS = 300;
    private static final int MAX_CLIMB = 15;
    private static final float MAX_HEIGHT_RATIO = 0.75f;
    private static final String CHAT_LIST = "chat_message_recycler_view";

    private static final Map<View, Scanner> SCANNERS = new WeakHashMap<>();
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();
    private static int chatListId = -1;

    private HypeTrainHider() {}

    public static void onActivityStarted(Activity activity) {
        try {
            ChatHistoryStarter.onActivityStarted(activity);
        } catch (Throwable t) {
            Log.e(TAG, "chat history start failed", t);
        }
        if (!ENABLED || activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || SCANNERS.containsKey(decor)) return;
            if (chatListId == -1) {
                chatListId = activity.getResources().getIdentifier(CHAT_LIST, "id", activity.getPackageName());
            }
            Scanner s = new Scanner(decor);
            SCANNERS.put(decor, s);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(s);
            s.schedule();
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private static final class Scanner implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        final WeakReference<View> rootRef;
        boolean posted;

        Scanner(View root) { rootRef = new WeakReference<>(root); }

        @Override public void onGlobalLayout() { schedule(); }

        void schedule() {
            View root = rootRef.get();
            if (root == null || posted) return;
            posted = true;
            root.postDelayed(this, SCAN_DELAY_MS);
        }

        @Override public void run() {
            posted = false;
            View root = rootRef.get();
            if (root == null) return;
            try {
                if (!KizuSettings.HIDE_HYPE_TRAIN.get()) return;
                scan(root, root);
            } catch (Throwable t) {
                Log.e(TAG, "scan failed", t);
            }
        }
    }

    private static void scan(View v, View root) {
        if (v.getVisibility() == View.GONE) return;
        String name = idName(v);
        if (name != null) {
            if (name.equals(CHAT_LIST)) return;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String k : ID_KEYWORDS) {
                if (lower.contains(k)) { hide(v, "id=" + name); return; }
            }
        }
        if (v instanceof TextView) {
            if (v instanceof EditText) return;
            CharSequence text = ((TextView) v).getText();
            if (text != null && containsKeyword(text.toString())) hideCard(v, root);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null) scan(c, root);
            }
        }
    }

    private static boolean containsKeyword(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (String k : TEXT_KEYWORDS) if (lower.contains(k)) return true;
        return false;
    }

    private static void hideCard(View textView, View root) {
        View cur = textView;
        for (int i = 0; i < MAX_CLIMB; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup parent = (ViewGroup) p;
            String name = idName(cur);
            if (cur != textView && name != null && name.toLowerCase(Locale.ROOT).contains("highlight")) {
                hideSafely(cur, root, "highlight=" + name); return;
            }
            if (chatListId != 0 && chatListId != -1 && parent.findViewById(chatListId) != null) {
                hideSafely(cur, root, "chat sibling"); return;
            }
            cur = parent;
        }
        Log.i(TAG, "card not found: " + chain(textView));
    }

    private static void hideSafely(View v, View root, String reason) {
        if (root.getHeight() > 0 && v.getHeight() > root.getHeight() * MAX_HEIGHT_RATIO) {
            Log.i(TAG, "too big, skipped: " + chain(v)); return;
        }
        hide(v, reason);
    }

    private static void hide(View v, String reason) {
        if (v.getVisibility() == View.GONE) return;
        v.setVisibility(View.GONE);
        Log.i(TAG, "hidden (" + reason + "): " + chain(v));
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return null;
        String cached = ID_NAMES.get(id);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = "";
        try { name = v.getResources().getResourceEntryName(id); } catch (Throwable ignored) {}
        ID_NAMES.put(id, name);
        return name.isEmpty() ? null : name;
    }

    private static String chain(View v) {
        StringBuilder sb = new StringBuilder();
        View cur = v;
        for (int i = 0; i < 8 && cur != null; i++) {
            String n = idName(cur);
            sb.append(cur.getClass().getSimpleName()).append(n != null ? "#" + n : "").append(" < ");
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return sb.toString();
    }
}