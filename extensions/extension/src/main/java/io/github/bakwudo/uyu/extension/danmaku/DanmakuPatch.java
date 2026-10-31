package io.github.bakwudo.uyu.extension.danmaku;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.Settings;

@SuppressWarnings("unused")
public final class DanmakuPatch {
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile WeakReference<DanmakuOverlay> currentOverlay = new WeakReference<>(null);

    private DanmakuPatch() {
    }

    public static void onTheatreCreated(Object theatreViewDelegate) {
        try {
            View root = ViewDelegates.rootView(theatreViewDelegate);
            if (root == null) {
                Utils.logInfo("Danmaku: theatre root view not found");
                return;
            }
            DanmakuOverlay overlay = DanmakuOverlay.attach(root);
            if (overlay != null) currentOverlay = new WeakReference<>(overlay);
        } catch (Exception ex) {
            Utils.logError("Danmaku: failed to add the overlay", ex);
        }
    }

    public static void onPlayerStateChanged(Object playerViewDelegate, Enum<?> state) {
        app.morphe.extension.watchlog.WatchTimeTracker.onPlayerState(state);
        try {
            if (state == null) return;
            String name = state.name();
            boolean paused;
            if (name.equals("PLAYING")) {
                paused = false;
            } else if (name.equals("PAUSED") || name.equals("STOPPED")) {
                paused = true;
            } else {
                return;
            }

            View playerRoot = ViewDelegates.rootView(playerViewDelegate);
            mainHandler.post(() -> {
                DanmakuOverlay overlay = currentOverlay.get();
                if (overlay != null && overlay.isInPlayer(playerRoot)) overlay.setPaused(paused);
            });
        } catch (Exception ex) {
            Utils.logError("Danmaku: failed to handle the player state", ex);
        }
    }

    public static void onMessagesReceived(String channelId, List<?> messages, boolean fromHistory) {
        try {
            NgWordFilter.filter(messages); // NGワード（完全一致）を取り除く
        } catch (Throwable ignored) {
        }
        if (messages == null || messages.isEmpty()) return;
        try {
            if (!fromHistory) app.morphe.extension.twitch.chat.ChatFlow.record(messages.size());
        } catch (Throwable ignored) {
        }
        try {
            // 過去コメント（名前タップ）の記録。過去ログの一括読み込みも記録する
            app.morphe.extension.twitch.chat.ChatHistory.onMessages(channelId, messages, fromHistory);
        } catch (Throwable ignored) {
        }
        try {
            if (fromHistory) return;
            // 弾幕が出ていないときは変換もしない（以前は常に全件を変換していた）
            DanmakuOverlay overlay0 = currentOverlay.get();
            if (overlay0 == null || !Settings.DANMAKU_ENABLED.get()) return;

            int level = app.morphe.extension.twitch.chat.ChatFlow.level();
            List<?> picked = DanmakuMessages.thin(messages, level);
            if (picked.isEmpty()) return;
            List<DanmakuComment> comments = DanmakuMessages.toComments(picked);
            if (comments.isEmpty()) return;

            mainHandler.post(() -> {
                DanmakuOverlay overlay = currentOverlay.get();
                if (overlay != null) overlay.addComments(comments);
            });
        } catch (Throwable ex) {
            Utils.logError("Danmaku: failed to handle chat messages", ex);
        }
    }
}
