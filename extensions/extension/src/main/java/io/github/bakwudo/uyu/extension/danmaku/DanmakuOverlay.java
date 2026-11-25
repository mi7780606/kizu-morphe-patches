package io.github.bakwudo.uyu.extension.danmaku;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.List;

import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;
import io.github.bakwudo.uyu.extension.settings.Setting;
import io.github.bakwudo.uyu.extension.settings.Settings;

final class DanmakuOverlay {
    static final String VIEW_TAG = "uyu_danmaku";
    private static final Object TAG_STYLED = new Object();

    private static final String[] TOP_BUTTONS = {
            "audio_and_subtitles", "settings_button", "share_button", "cast_button", "create_clip_text_button", "info",
    };

    private final ViewGroup player;
    private final View video;
    private final ViewGroup controls;
    private final int miniPlayerLayoutId;
    private final int backButtonId;
    private final int settingsButtonId;
    private final int shareButtonId;
    private final int castButtonId;
    private final int clipTextButtonId;
    private final int[] topButtonIds;
    private final DanmakuView danmaku;
    private final ImageView button;
    private final DanmakuToggleIcon icon;
    private final ImageView settingsButton;
    private final ImageView chatButton;
    private final ImageView betButton;
    private final DanmakuQuickSettings quickSettings;
    private final Rect rect = new Rect();
    private final Rect buttonRect = new Rect();
    private final ViewTreeObserver.OnPreDrawListener preDrawSync = this::onControlsPreDraw;
    private ViewTreeObserver controlsObserver;
    private volatile boolean active;
    private int lastValidOffset = -1;
    private int lastAnchorLeft = -1;
    private boolean lastControlsShown;
    private float lastControlsAlpha = -1f;
    private boolean lastQuickSettingsEnabled;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (preferences, key) -> {
        if (Settings.DANMAKU_ENABLED.key.equals(key)
                || Settings.DANMAKU_PORTRAIT.key.equals(key)
                || Settings.DANMAKU_MINI_PLAYER.key.equals(key)
                || Settings.DANMAKU_PICTURE_IN_PICTURE.key.equals(key)
                || KizuSettings.DANMAKU_QUICK_SETTINGS.key.equals(key)
                || app.morphe.extension.settings.Settings.HIDE_PLAYER_LIVE_SHARE_BUTTON.key.equals(key)
                || app.morphe.extension.settings.Settings.HIDE_CAST_BUTTON.key.equals(key)
                || app.morphe.extension.settings.Settings.HIDE_PLAYER_CREATE_CLIP_BUTTON.key.equals(key)) {
            update();
        }
    };

    private DanmakuOverlay(ViewGroup player, View video, ViewGroup controls) {
        this.player = player;
        this.video = video;
        this.controls = controls;
        Context context = player.getContext();
        miniPlayerLayoutId = Utils.getResourceId(context, "draggable_layout", "id");
        backButtonId = Utils.getResourceId(context, "back_button", "id");
        settingsButtonId = Utils.getResourceId(context, "settings_button", "id");
        shareButtonId = Utils.getResourceId(context, "share_button", "id");
        castButtonId = Utils.getResourceId(context, "cast_button", "id");
        clipTextButtonId = Utils.getResourceId(context, "create_clip_text_button", "id");
        topButtonIds = new int[TOP_BUTTONS.length];
        for (int i = 0; i < TOP_BUTTONS.length; i++) {
            topButtonIds[i] = Utils.getResourceId(context, TOP_BUTTONS[i], "id");
        }

        danmaku = new DanmakuView(context);
        danmaku.setTag(VIEW_TAG);
        danmaku.setVisibility(View.INVISIBLE);
        player.addView(danmaku, 1, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));

        quickSettings = new DanmakuQuickSettings(controls);

        int iconSize = Math.round(24 * context.getResources().getDisplayMetrics().density);
        icon = new DanmakuToggleIcon(iconSize);
        button = new ImageView(context);
        button.setImageDrawable(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription("Danmaku comments");
        button.setVisibility(View.INVISIBLE);
        button.setOnClickListener(v -> {
            Settings.DANMAKU_ENABLED.save(!Settings.DANMAKU_ENABLED.get());
            update();
        });
        controls.addView(button, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.RIGHT));

        settingsButton = new ImageView(context);
        settingsButton.setImageDrawable(new DanmakuQuickSettings.Icon(iconSize));
        settingsButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settingsButton.setContentDescription("Danmaku settings");
        settingsButton.setVisibility(View.INVISIBLE);
        settingsButton.setOnClickListener(v -> quickSettings.toggle());
        controls.addView(settingsButton, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.RIGHT));

        // チャット設定を開くボタン（チャット欄の三点リーダーの代わり）
        chatButton = new ImageView(context);
        int chatIcon = Utils.getResourceId(context, "ic_chat_settings", "drawable");
        if (chatIcon != 0) chatButton.setImageResource(chatIcon);
        chatButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chatButton.setContentDescription("Chat settings");
        chatButton.setVisibility(View.INVISIBLE);
        chatButton.setOnClickListener(v -> {
            try {
                app.morphe.extension.appearance.KizuHooks.openChatSettings();
            } catch (Throwable t) {
                Utils.logError("Kizu: chat settings failed", t);
            }
        });
        controls.addView(chatButton, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.RIGHT));

        // Kizu: 予想に手動で賭ける画面を開くボタン
        betButton = new ImageView(context);
        int betIcon = Utils.getResourceId(context, "ic_prediction_event", "drawable");
        if (betIcon != 0) betButton.setImageResource(betIcon);
        betButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        betButton.setContentDescription("予想に賭ける");
        betButton.setVisibility(View.INVISIBLE);
        betButton.setOnClickListener(v -> {
            try {
                app.morphe.extension.channelpoints.TvBet.open(v.getContext());
            } catch (Throwable t) {
                Utils.logError("Kizu: bet failed", t);
            }
        });
        controls.addView(betButton, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.RIGHT));

        player.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update());
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update());
        controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            try { updateButton(isAvailable()); } catch (Exception ex) { Utils.logError("Danmaku: failed to update the button", ex); }
        });
        danmaku.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                Setting.addChangeListener(settingsListener);
                attachControlsObserver();
                update();
            }
            @Override public void onViewDetachedFromWindow(View view) {
                active = false;
                Setting.removeChangeListener(settingsListener);
                detachControlsObserver();
                quickSettings.dismiss();
            }
        });
        if (danmaku.isAttachedToWindow()) {
            Setting.addChangeListener(settingsListener);
            attachControlsObserver();
            active = isShown();
        }
    }

    private void attachControlsObserver() {
        if (!controls.isAttachedToWindow()) return;
        if (controlsObserver != null && controlsObserver.isAlive()) return;
        ViewTreeObserver vto = controls.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            vto.addOnPreDrawListener(preDrawSync);
            controlsObserver = vto;
        }
    }

    private void detachControlsObserver() {
        if (controlsObserver != null) {
            if (controlsObserver.isAlive()) controlsObserver.removeOnPreDrawListener(preDrawSync);
            controlsObserver = null;
        }
    }

    static DanmakuOverlay attach(View theatreRoot) {
        Context context = theatreRoot.getContext();
        View player = theatreRoot.findViewById(Utils.getResourceId(context, "player_view_delegate", "id"));
        View controls = theatreRoot.findViewById(Utils.getResourceId(context, "player_overlay_container", "id"));
        if (!(player instanceof FrameLayout playerLayout) || !(controls instanceof FrameLayout controlsLayout)) {
            Utils.logInfo("Danmaku: player views not found");
            return null;
        }
        View video = player.findViewById(Utils.getResourceId(context, "playback_view_container", "id"));
        if (video == null) {
            Utils.logInfo("Danmaku: video container not found");
            return null;
        }
        return new DanmakuOverlay(playerLayout, video, controlsLayout);
    }

    boolean isInPlayer(View playerRoot) {
        return playerRoot != null && playerRoot.findViewWithTag(VIEW_TAG) == danmaku;
    }

    void addComments(List<DanmakuComment> comments) {
        if (!isShown()) return;
        for (DanmakuComment comment : comments) danmaku.addComment(comment);
    }

    void setPaused(boolean paused) { danmaku.setPaused(paused); }

    boolean isShown() { return Settings.DANMAKU_ENABLED.get() && isAvailable(); }

    boolean isCurrentlyActive() { return active; }

    private boolean isAvailable() {
        if (!danmaku.isAttachedToWindow()) return false;
        Context context = player.getContext();
        Activity activity = Utils.findActivity(context);
        if (activity != null && activity.isInPictureInPictureMode()) {
            return Settings.DANMAKU_PICTURE_IN_PICTURE.get();
        }
        if (isMiniPlayer()) return Settings.DANMAKU_MINI_PLAYER.get();
        if (context.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return Settings.DANMAKU_PORTRAIT.get();
        }
        return true;
    }

    private boolean isMiniPlayer() {
        if (miniPlayerLayoutId == 0) return false;
        for (ViewParent parent = player.getParent(); parent instanceof View view; parent = view.getParent()) {
            if (view.getId() != miniPlayerLayoutId) continue;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            return params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT;
        }
        return false;
    }

    private void update() {
        try { updateViews(); } catch (Exception ex) { Utils.logError("Danmaku: failed to update the overlay", ex); }
    }

    private void updateViews() {
        boolean available = isAvailable();
        boolean shown = Settings.DANMAKU_ENABLED.get() && available;
        active = shown;
        if (!shown) danmaku.clear();
        if (!available || !KizuSettings.DANMAKU_QUICK_SETTINGS.get()) quickSettings.dismiss();
        danmaku.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        icon.setOn(Settings.DANMAKU_ENABLED.get());

        rect.set(video.getLeft(), video.getTop(), video.getRight(), video.getBottom());
        if (!rect.intersect(0, 0, player.getWidth(), player.getHeight())) rect.setEmpty();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) danmaku.getLayoutParams();
        if (params.leftMargin != rect.left || params.topMargin != rect.top
                || params.width != rect.width() || params.height != rect.height()) {
            params.leftMargin = rect.left;
            params.topMargin = rect.top;
            params.width = rect.width();
            params.height = rect.height();
            danmaku.post(() -> danmaku.setLayoutParams(params));
        }
        updateButton(available);
    }

    private boolean onControlsPreDraw() {
        try {
            View backButton = backButtonId != 0 ? controls.findViewById(backButtonId) : null;
            boolean controlsShown = backButton != null && backButton.isShown();
            float controlsAlpha = controlsShown ? resolveControlsAlpha(backButton) : 0f;
            boolean quickSettingsEnabled = KizuSettings.DANMAKU_QUICK_SETTINGS.get();
            int anchorLeft = controlsShown ? computeAnchorLeft() : lastAnchorLeft;
            if (controlsShown != lastControlsShown
                    || controlsAlpha != lastControlsAlpha
                    || anchorLeft != lastAnchorLeft
                    || quickSettingsEnabled != lastQuickSettingsEnabled) {
                if (updateButton(isAvailable())) {
                    return false;
                }
            }
        } catch (Exception ex) {
            Utils.logError("Danmaku: pre-draw button sync failed", ex);
        }
        return true;
    }

    private boolean isSkippedHiddenTopButton(int id) {
        if (id == shareButtonId && app.morphe.extension.settings.Settings.HIDE_PLAYER_LIVE_SHARE_BUTTON.get()) {
            return true;
        }
        if (id == castButtonId && app.morphe.extension.settings.Settings.HIDE_CAST_BUTTON.get()) {
            return true;
        }
        return id == clipTextButtonId
                && app.morphe.extension.settings.Settings.HIDE_PLAYER_CREATE_CLIP_BUTTON.get();
    }

    private int computeAnchorLeft() {
        int left = Integer.MAX_VALUE;
        for (int id : topButtonIds) {
            if (id == 0 || isSkippedHiddenTopButton(id)) continue;
            View view = controls.findViewById(id);
            if (view == null || !view.isShown() || view.getWidth() <= 0) continue;
            buttonRect.set(0, 0, view.getWidth(), view.getHeight());
            controls.offsetDescendantRectToMyCoords(view, buttonRect);
            left = Math.min(left, buttonRect.left);
        }
        return left;
    }

    private float resolveControlsAlpha(View backButton) {
        float alpha = backButton.getAlpha();
        for (ViewParent parent = backButton.getParent();
             parent instanceof View view && parent != controls;
             parent = view.getParent()) {
            alpha *= view.getAlpha();
        }
        return Math.max(0f, Math.min(1f, alpha));
    }

    private void applyControlsAlpha(float alpha) {
        if (button.getAlpha() != alpha) button.setAlpha(alpha);
        if (settingsButton.getAlpha() != alpha) settingsButton.setAlpha(alpha);
        if (chatButton.getAlpha() != alpha) chatButton.setAlpha(alpha);
        if (betButton.getAlpha() != alpha) betButton.setAlpha(alpha);
    }

    private boolean updateButton(boolean available) {
        attachControlsObserver();
        View backButton = backButtonId != 0 ? controls.findViewById(backButtonId) : null;
        boolean controlsShown = backButton != null && backButton.isShown();
        boolean quickSettingsEnabled = KizuSettings.DANMAKU_QUICK_SETTINGS.get();
        float controlsAlpha = controlsShown ? resolveControlsAlpha(backButton) : 0f;

        lastControlsShown = controlsShown;
        lastControlsAlpha = controlsAlpha;
        lastQuickSettingsEnabled = quickSettingsEnabled;

        boolean visible = controlsShown && available;
        boolean settingsVisible = visible && quickSettingsEnabled;
        if (!settingsVisible) quickSettings.dismiss();

        if (!controlsShown) {
            button.setVisibility(View.INVISIBLE);
            settingsButton.setVisibility(View.INVISIBLE);
            chatButton.setVisibility(View.INVISIBLE);
            betButton.setVisibility(View.INVISIBLE);
            return false;
        }

        View template = null;
        int left = Integer.MAX_VALUE;
        for (int id : topButtonIds) {
            if (id == 0) continue;
            View view = controls.findViewById(id);
            if (view == null) continue;
            if (id == settingsButtonId) template = view;
            if (isSkippedHiddenTopButton(id) || !view.isShown() || view.getWidth() <= 0) continue;
            buttonRect.set(0, 0, view.getWidth(), view.getHeight());
            controls.offsetDescendantRectToMyCoords(view, buttonRect);
            left = Math.min(left, buttonRect.left);
        }

        if (left != Integer.MAX_VALUE) {
            lastAnchorLeft = left;
            lastValidOffset = Math.max(0, controls.getWidth() - left);
        }
        if (lastValidOffset < 0) {
            button.setVisibility(View.INVISIBLE);
            settingsButton.setVisibility(View.INVISIBLE);
            chatButton.setVisibility(View.INVISIBLE);
            betButton.setVisibility(View.INVISIBLE);
            return false;
        }

        int size = template != null && template.getLayoutParams().width > 0
                ? template.getLayoutParams().width
                : Math.round(48 * controls.getResources().getDisplayMetrics().density);
        int offset = lastValidOffset;
        boolean layoutChanged = false;
        boolean deferredInitial = false;
        if (visible) {
            int r = layoutButton(button, template, size, offset);
            layoutChanged |= (r & 1) != 0;
            deferredInitial |= (r & 2) != 0;
            offset += size;
            if (quickSettingsEnabled) {
                r = layoutButton(settingsButton, template, size, offset);
                layoutChanged |= (r & 1) != 0;
                deferredInitial |= (r & 2) != 0;
                offset += size;
            }
        }
        int cr = layoutButton(chatButton, template, size, offset);
        layoutChanged |= (cr & 1) != 0;
        deferredInitial |= (cr & 2) != 0;
        offset += size;
        int br = layoutButton(betButton, template, size, offset);
        layoutChanged |= (br & 1) != 0;
        deferredInitial |= (br & 2) != 0;

        applyControlsAlpha(controlsAlpha);
        if (deferredInitial) {
            button.setVisibility(View.INVISIBLE);
            settingsButton.setVisibility(View.INVISIBLE);
            chatButton.setVisibility(View.INVISIBLE);
            betButton.setVisibility(View.INVISIBLE);
        } else {
            button.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            settingsButton.setVisibility(settingsVisible ? View.VISIBLE : View.INVISIBLE);
            chatButton.setVisibility(View.VISIBLE);
            betButton.setVisibility(View.VISIBLE);
        }
        return layoutChanged;
    }

    /** Returns bit 1 if layout params were applied synchronously, bit 2 if initial placement was deferred via post. */
    private int layoutButton(ImageView target, View template, int size, int rightMargin) {
        int topMargin = template != null && template.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                ? mlp.topMargin : 0;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
        boolean wasUnmeasured = params.width == 0;
        boolean styledNow = false;
        if (target.getTag() != TAG_STYLED && template instanceof ImageView templateButton) {
            target.setTag(TAG_STYLED);
            target.setPadding(template.getPaddingLeft(), template.getPaddingTop(),
                    template.getPaddingRight(), template.getPaddingBottom());
            target.setImageTintList(templateButton.getImageTintList());
            Drawable background = template.getBackground();
            if (background != null && background.getConstantState() != null) {
                target.setBackground(background.getConstantState().newDrawable().mutate());
            }
            styledNow = true;
        }
        if (!styledNow && params.width == size && params.height == size
                && params.rightMargin == rightMargin && params.topMargin == topMargin) {
            return 0;
        }
        params.width = size;
        params.height = size;
        params.rightMargin = rightMargin;
        params.topMargin = topMargin;
        if (!controls.isInLayout()) {
            target.setLayoutParams(params);
            return 1;
        }
        target.post(() -> {
            target.setLayoutParams(params);
            if (wasUnmeasured) {
                try { updateButton(isAvailable()); } catch (Exception ignored) {}
            }
        });
        return wasUnmeasured ? 2 : 0;
    }
}