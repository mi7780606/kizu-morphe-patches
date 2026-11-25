package app.morphe.extension.appearance;

import android.content.Context;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.twitch.chat.ChatHistory;
import app.morphe.extension.twitch.emotes.EmoteAutocomplete;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

@SuppressWarnings("unused")
public final class HidePromotionsPatch {
    private static final Set<String> PROMOTION_HIGHLIGHTS = new HashSet<>(Arrays.asList(
            "subtember",
            "gift_promotion"
    ));

    private static final Target LEADERBOARD_BUTTON =
            new Target("leaderboards_icon", view -> Settings.HIDE_GIFT_LEADERBOARD.get(), false);

    private static final Target[] OTHER_CHAT_HEADER_ITEMS = {
            new Target("chat_name", null, false),
            LEADERBOARD_BUTTON,
            new Target("extension_button_container", null, false),
    };

    private static final Target[] TARGETS = {
            new Target("chat_header_buttons_container",
                    view -> Settings.HIDE_SUBSCRIBE_BUTTONS.get(), false),
            new Target("chat_header_container", HidePromotionsPatch::isChatHeaderEmpty, true),
            new Target("bit_picker", view -> Settings.HIDE_CHAT_BITS_BUTTON.get(), false),
            new Target("leaderboards_container", view -> Settings.HIDE_GIFT_LEADERBOARD.get(), false),
            LEADERBOARD_BUTTON,
            new Target("extension_button_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("extension_icons_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("leaderboards_extensions_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("extension_detail_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("extension_overlay_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("extension_webview_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("extensions_container",
                    view -> Settings.HIDE_STREAM_EXTENSIONS.get(), true),
            new Target("promo_banner_container",
                    view -> Settings.HIDE_SUBSCRIPTION_PROMOTIONS.get(), false),
            new Target("turbo_upsell_container",
                    view -> Settings.HIDE_SUBSCRIPTION_PROMOTIONS.get(), false),
            new Target("stories_shelf",
                    view -> Settings.HIDE_STORIES.get(), true),
    };

    private static final int MAX_RETAINED_LISTS = 8;

    private static final class RetainedList {
        final WeakReference<List<?>> liveRef;
        final List<?> original;

        RetainedList(List<?> live, List<?> original) {
            this.liveRef = new WeakReference<>(live);
            this.original = original;
        }
    }

    private static final List<RetainedList> RESUME_ORIGINALS = new ArrayList<>();
    private static final List<RetainedList> OFFLINE_ORIGINALS = new ArrayList<>();
    private static final Map<View, WeakReference<View>[]> HEADER_ITEMS_CACHE = new WeakHashMap<>();
    private static WeakReference<View> FOLLOWING_RECYCLER = new WeakReference<>(null);

    public static void filterResumeWatchingList(List<?> items) {
        rememberAndFilter(items, RESUME_ORIGINALS, Settings.HIDE_RESUME_WATCHING.get());
    }

    public static void filterOfflineChannelsList(List<?> items) {
        rememberAndFilter(items, OFFLINE_ORIGINALS, Settings.HIDE_OFFLINE_CHANNELS.get());
    }

    private static void rememberAndFilter(
            List<?> items,
            List<RetainedList> originals,
            boolean hide
    ) {
        if (items == null) return;
        synchronized (originals) {
            boolean found = false;
            Iterator<RetainedList> it = originals.iterator();
            while (it.hasNext()) {
                List<?> live = it.next().liveRef.get();
                if (live == null) {
                    it.remove();
                } else if (live == items) {
                    found = true;
                }
            }
            if (!found) {
                while (originals.size() >= MAX_RETAINED_LISTS) {
                    originals.remove(0);
                }
                originals.add(new RetainedList(items, new ArrayList<>(items)));
            }
            if (hide) items.clear();
        }
    }

    public static void onHomeSectionSettingChanged() {
        applyListSetting(RESUME_ORIGINALS, Settings.HIDE_RESUME_WATCHING.get());
        applyListSetting(OFFLINE_ORIGINALS, Settings.HIDE_OFFLINE_CHANNELS.get());
        refreshFollowingAdapter();
    }

    private static void applyListSetting(List<RetainedList> originals, boolean hide) {
        synchronized (originals) {
            Iterator<RetainedList> it = originals.iterator();
            while (it.hasNext()) {
                RetainedList entry = it.next();
                List<?> items = entry.liveRef.get();
                if (items == null || entry.original == null) {
                    it.remove();
                    continue;
                }
                if (hide) {
                    items.clear();
                } else {
                    @SuppressWarnings("unchecked")
                    List<Object> mutable = (List<Object>) items;
                    @SuppressWarnings("unchecked")
                    List<Object> saved = (List<Object>) entry.original;
                    mutable.clear();
                    mutable.addAll(saved);
                }
            }
        }
    }

    private static void refreshFollowingAdapter() {
        try {
            View recycler = FOLLOWING_RECYCLER.get();
            if (recycler == null || !recycler.isAttachedToWindow()) return;

            try {
                java.lang.reflect.Method getAdapter = recycler.getClass().getMethod("getAdapter");
                Object adapter = getAdapter.invoke(recycler);
                if (adapter != null) {
                    java.lang.reflect.Method notify = adapter.getClass().getMethod("notifyDataSetChanged");
                    notify.invoke(adapter);
                }
            } catch (Throwable ignored) {}

            recycler.invalidate();
            recycler.requestLayout();
        } catch (Throwable t) {
            Utils.logError("Failed to refresh Following feed", t);
        }
    }

    public static void bindGoAdFree(View root) {
        if (root == null) return;
        try {
            if (bindGoAdFreeNow(root)) return;
            WeakReference<View> ref = new WeakReference<>(root);
            root.postDelayed(() -> {
                View r = ref.get();
                if (r != null && !bindGoAdFreeNow(r)) {
                    r.postDelayed(() -> {
                        View r2 = ref.get();
                        if (r2 != null) bindGoAdFreeNow(r2);
                    }, 500L);
                }
            }, 150L);
        } catch (Exception ex) {
            Utils.logError("Failed to bind Go Ad-Free", ex);
        }
    }

    private static boolean bindGoAdFreeNow(View root) {
        Context context = root.getContext();
        int id = Utils.getResourceId(context, "following_tab_turbo_button", "id");
        if (id == 0) return false;
        View button = root.findViewById(id);
        if (button != null) {
            HiddenView.attach(button, view -> Settings.HIDE_TURBO_UPSELL.get(), true);
            return true;
        }
        return false;
    }

    public static void onViewCreated(View root) {
        if (root == null) return;
        try {
            Context context = root.getContext();
            int recyclerId = Utils.getResourceId(context, "following_list_recycler_view", "id");
            View recycler = recyclerId == 0 ? null : root.findViewById(recyclerId);
            if (recycler != null) {
                FOLLOWING_RECYCLER = new WeakReference<>(recycler);
                try {
                    KizuRecommended.bindFollowingRoot(root, recycler);
                } catch (Throwable ignored) {}
            }

            // Direct binding of Kizu views inside Twitch ViewDelegates avoids DecorView polling.
            int chatListId = Utils.getResourceId(context, "chat_message_recycler_view", "id");
            if (chatListId != 0) {
                View chatList = root.findViewById(chatListId);
                if (chatList != null) ChatHistory.attach(chatList);
            }
            int hypeLandscapeId = Utils.getResourceId(context, "one_chat_hype_train_landscape_container", "id");
            if (hypeLandscapeId != 0) {
                View hype = root.findViewById(hypeLandscapeId);
                if (hype != null) {
                    HiddenView.attach(hype, view -> KizuSettings.HIDE_HYPE_TRAIN.get(), true);
                }
            }
            int chatMenuId = Utils.getResourceId(context, "view_chat_settings", "id");
            if (chatMenuId != 0) {
                View chatMenu = root.findViewById(chatMenuId);
                if (chatMenu != null) KizuUiTweaks.bindChatMenuButton(chatMenu);
            }
            int cpOuterId = Utils.getResourceId(context, "community_points_outer_container", "id");
            if (cpOuterId != 0) {
                View cpOuter = root.findViewById(cpOuterId);
                if (cpOuter != null) KizuUiTweaks.bindCommunityPoints(cpOuter);
            }
            int msgInputId = Utils.getResourceId(context, "message_input", "id");
            if (msgInputId != 0) {
                View msgInput = root.findViewById(msgInputId);
                if (msgInput != null) EmoteAutocomplete.attachDirectly(msgInput);
            }
            LandscapeChatInputHider.bindInViewTree(root);
            BottomCreateButtonHider.bindInTree(root);

            for (Target target : TARGETS) {
                int id = target.id(context);
                if (id == 0) continue;
                View view = root.findViewById(id);
                if (view != null) HiddenView.attach(view, target.condition, target.restore);
            }
        } catch (Exception ex) {
            Utils.logError("Failed to hide promotions", ex);
        }
    }

    public static boolean hideCommunityHighlight(Object event) {
        try {
            String type = highlightType(event);
            if (type == null || !PROMOTION_HIGHLIGHTS.contains(type)
                    || !Settings.HIDE_SUBSCRIPTION_PROMOTIONS.get()) {
                return false;
            }
            Utils.logInfo("Hid community highlight " + type);
            return true;
        } catch (Exception ex) {
            Utils.logError("Failed to check a community highlight", ex);
            return false;
        }
    }

    private static String highlightType(Object event) {
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean isChatHeaderEmpty(View header) {
        if (!Settings.HIDE_SUBSCRIBE_BUTTONS.get()) return false;
        boolean leaderboardHidden = Settings.HIDE_GIFT_LEADERBOARD.get();
        WeakReference<View>[] cached = HEADER_ITEMS_CACHE.get(header);
        if (cached == null) {
            cached = new WeakReference[OTHER_CHAT_HEADER_ITEMS.length];
            Context ctx = header.getContext();
            for (int i = 0; i < OTHER_CHAT_HEADER_ITEMS.length; i++) {
                int id = OTHER_CHAT_HEADER_ITEMS[i].id(ctx);
                View found = id == 0 ? null : header.findViewById(id);
                cached[i] = found == null ? null : new WeakReference<>(found);
            }
            HEADER_ITEMS_CACHE.put(header, cached);
        }
        for (int i = 0; i < OTHER_CHAT_HEADER_ITEMS.length; i++) {
            if (leaderboardHidden && OTHER_CHAT_HEADER_ITEMS[i] == LEADERBOARD_BUTTON) continue;
            WeakReference<View> ref = cached[i];
            View view = ref == null ? null : ref.get();
            if (view != null && view.getVisibility() == View.VISIBLE) return false;
        }
        return true;
    }

    private static final class Target {
        final String name;
        final HiddenView.Condition condition;
        final boolean restore;
        private int id = -1;

        Target(String name, HiddenView.Condition condition, boolean restore) {
            this.name = name;
            this.condition = condition;
            this.restore = restore;
        }

        int id(Context context) {
            if (id == -1) {
                id = Utils.getResourceId(context, name, "id");
                if (id == 0) Utils.logInfo("View " + name + " not found, it is not hidden");
            }
            return id;
        }
    }
}
