package app.morphe.extension.appearance;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import io.github.bakwudo.uyu.extension.danmaku.ViewDelegates;

/**
 * Hides the chat message input bar while watching fullscreen (landscape) chat,
 * automatically restoring it when rotating back to portrait or turning the setting off.
 */
public final class LandscapeChatInputHider {
    private static final String[] INPUT_CONTAINER_IDS = {
            "chat_message_input_view",
            "chat_input_container",
            "message_input_container",
            "one_chat_input_container",
            "landscape_chat_input_container",
            "chat_input_bar",
            "chat_input_layout",
            "chat_footer",
            "chat_input_overlay",
    };

    private static final String[] INPUT_DESCENDANT_IDS = {
            "view_chat_settings",
            "emote_picker",
            "emote_picker_button",
            "bit_picker",
            "send_message_button",
            "chat_send_button",
    };

    private static final WeakHashMap<View, Integer> WATCHED_PARENTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> ATTACH_WATCHED = new WeakHashMap<>();

    private LandscapeChatInputHider() {}

    public static boolean shouldHide(View view) {
        if (view == null) return false;
        try {
            return Settings.HIDE_LANDSCAPE_CHAT_INPUT.get()
                    && view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void bindFromDelegate(Object delegate, View messageInput) {
        try {
            View delegateRoot = delegate == null ? null : ViewDelegates.rootView(delegate);
            View target = resolveInputContainer(delegateRoot, messageInput);
            if (target != null) attach(target);
        } catch (Throwable ignored) {}
    }

    public static void bindInViewTree(View root) {
        if (root == null) return;
        try {
            Context context = root.getContext();
            int msgInputId = Utils.getResourceId(context, "message_input", "id");
            if (msgInputId != 0) {
                View msgInput = root.findViewById(msgInputId);
                if (msgInput != null && !hasRecyclerViewAncestor(msgInput)) {
                    View target = resolveInputContainer(null, msgInput);
                    if (target != null) attach(target);
                }
            }
            for (String name : INPUT_CONTAINER_IDS) {
                int id = Utils.getResourceId(context, name, "id");
                if (id == 0) continue;
                View container = root.findViewById(id);
                if (container != null && !containsChatList(container) && !hasRecyclerViewAncestor(container)) {
                    attach(container);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void bindInChatPanel(View chatPanel) {
        if (chatPanel == null) return;
        try {
            bindInViewTree(chatPanel);
            Rect panelRect = new Rect();
            if (!chatPanel.getGlobalVisibleRect(panelRect)) {
                panelRect.setEmpty();
            }
            int bottomThresholdPx = Math.round(140f * chatPanel.getResources().getDisplayMetrics().density);
            Rect itemRect = new Rect();
            scanForInputControls(chatPanel, panelRect, itemRect, bottomThresholdPx, 0);
        } catch (Throwable ignored) {}
    }

    private static void scanForInputControls(
            View view,
            Rect panelRect,
            Rect itemRect,
            int bottomThresholdPx,
            int depth
    ) {
        if (view == null || depth > 14 || view.getVisibility() != View.VISIBLE) return;
        if (isRecyclerView(view)) return;

        boolean matched = false;
        if (view instanceof EditText) {
            matched = true;
        } else if (view instanceof TextView tv && isBottomInputPrompt(tv, view, panelRect, itemRect, bottomThresholdPx)) {
            matched = true;
        } else if (isPromptText(view.getContentDescription())
                && isBottomAnchored(view, panelRect, itemRect, bottomThresholdPx)) {
            matched = true;
        }

        if (matched) {
            View target = resolveInputContainer(null, view);
            if (target != null) attach(target);
            return;
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                scanForInputControls(group.getChildAt(i), panelRect, itemRect, bottomThresholdPx, depth + 1);
            }
        }
    }

    private static boolean isBottomInputPrompt(
            TextView tv,
            View view,
            Rect panelRect,
            Rect itemRect,
            int bottomThresholdPx
    ) {
        if (!isPromptText(tv.getText()) && !isPromptText(tv.getHint())
                && !isPromptText(view.getContentDescription())) {
            return false;
        }
        return isBottomAnchored(view, panelRect, itemRect, bottomThresholdPx);
    }

    private static boolean isBottomAnchored(View view, Rect panelRect, Rect itemRect, int bottomThresholdPx) {
        if (panelRect.isEmpty() || !view.getGlobalVisibleRect(itemRect)) {
            return view.isClickable() || view instanceof EditText;
        }
        return itemRect.bottom >= panelRect.bottom - bottomThresholdPx;
    }

    private static boolean isPromptText(CharSequence cs) {
        if (cs == null || cs.length() == 0) return false;
        String text = cs.toString().trim().toLowerCase(Locale.ROOT);
        return text.contains("send a message")
                || text.contains("send message")
                || text.contains("chat input")
                || text.contains("メッセージを送信")
                || text.contains("チャットを送信")
                || text.contains("コメントを入力")
                || text.contains("コメントを送信");
    }

    private static View resolveInputContainer(View delegateRoot, View anchor) {
        if (delegateRoot != null && !containsChatList(delegateRoot) && !hasRecyclerViewAncestor(delegateRoot)) {
            return delegateRoot;
        }
        if (anchor == null) return null;

        View best = anchor;
        View current = anchor;
        for (int i = 0; i < 8; i++) {
            ViewParent p = current.getParent();
            if (!(p instanceof ViewGroup parent)) break;
            if (isRecyclerView(parent) || containsChatList(parent) || isStopContainer(parent)) {
                break;
            }
            if (isNamedInputContainer(parent) || containsInputSibling(parent)) {
                best = parent;
            }
            current = parent;
        }

        if (best == anchor && anchor.getParent() instanceof ViewGroup parent) {
            if (!isRecyclerView(parent) && !containsChatList(parent) && !isStopContainer(parent)) {
                int maxDirectH = Math.round(140f * parent.getResources().getDisplayMetrics().density);
                int h = parent.getHeight();
                if ((h > 0 && h <= maxDirectH) || parent.getChildCount() <= 8) {
                    best = parent;
                }
            }
        }
        return best;
    }

    private static void attach(View container) {
        HiddenView.attach(container, LandscapeChatInputHider::shouldHide, true);
        watchParentOrientation(container);
        synchronized (ATTACH_WATCHED) {
            if (ATTACH_WATCHED.containsKey(container)) return;
            ATTACH_WATCHED.put(container, Boolean.TRUE);
        }
        container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                watchParentOrientation(v);
                HiddenView.recheck(v);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });
    }

    private static void watchParentOrientation(View container) {
        ViewParent parent = container.getParent();
        if (!(parent instanceof ViewGroup parentView)) return;
        final int currentOrientation = parentView.getResources().getConfiguration().orientation;
        synchronized (WATCHED_PARENTS) {
            if (WATCHED_PARENTS.containsKey(parentView)) return;
            WATCHED_PARENTS.put(parentView, currentOrientation);
        }
        parentView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int orientation = v.getResources().getConfiguration().orientation;
            boolean changed = false;
            synchronized (WATCHED_PARENTS) {
                Integer last = WATCHED_PARENTS.get(v);
                if (last == null || last != orientation) {
                    WATCHED_PARENTS.put(v, orientation);
                    changed = true;
                }
            }
            if (changed) {
                HiddenView.recheckAll();
            }
        });
    }

    private static boolean containsChatList(View v) {
        int chatListId = Utils.getResourceId(v.getContext(), "chat_message_recycler_view", "id");
        return chatListId != 0 && v.findViewById(chatListId) != null;
    }

    private static boolean containsInputSibling(ViewGroup group) {
        Context context = group.getContext();
        for (String name : INPUT_DESCENDANT_IDS) {
            int id = Utils.getResourceId(context, name, "id");
            if (id != 0 && group.findViewById(id) != null) return true;
        }
        return false;
    }

    private static boolean hasRecyclerViewAncestor(View v) {
        ViewParent p = v.getParent();
        for (int i = 0; i < 12 && p != null; i++) {
            if (p instanceof View pv && isRecyclerView(pv)) return true;
            p = p.getParent();
        }
        return false;
    }

    private static boolean isRecyclerView(View v) {
        return v.getClass().getName().toLowerCase(Locale.ROOT).contains("recyclerview");
    }

    private static boolean isStopContainer(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        try {
            String entry = v.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            return "chat_wrapper".equals(entry)
                    || "one_chat_overlay_container".equals(entry)
                    || "content".equals(entry)
                    || entry.contains("coordinator");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNamedInputContainer(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        try {
            String entry = v.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
            return entry.contains("input")
                    || entry.contains("composer")
                    || entry.contains("footer")
                    || entry.contains("message_input");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
