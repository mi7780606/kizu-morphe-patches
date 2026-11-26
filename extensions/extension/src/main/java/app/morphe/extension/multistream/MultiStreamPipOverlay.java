package app.morphe.extension.multistream;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.danmaku.DanmakuView;

/**
 * Mode A: Draggable, resizable 16:9 floating sub-stream PiP overlay docked over Twitch's normal
 * playback/chat screen, complete with proportional {@link DanmakuView} matching standard PiP scaling.
 */
final class MultiStreamPipOverlay {
    private static final String TAG_VIEW = "kizu_multi_pip_overlay";
    private static final float[] WIDTH_RATIOS = {0.38f, 0.52f, 0.68f};
    private static final String[] SIZE_LABELS = {"小", "中", "大"};
    private static final long HUD_AUTO_HIDE_MS = 3500L;

    private final WeakReference<Activity> activityRef;
    private final FrameLayout decor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private final FrameLayout card;
    private final TextureView textureView;
    private final DanmakuView danmakuView;
    private final ProgressBar loadingBar;
    private final TextView errorLabel;
    private final FrameLayout hudOverlay;
    private final TextView titleBadge;
    private final TextView muteBtn;
    private final TextView danmakuBtn;
    private final TextView sizeBtn;

    private final MultiStreamPlayer player;
    private final MultiStreamIrcClient ircClient;
    private final View.OnLayoutChangeListener decorLayoutListener;
    private final Runnable hideHudRunnable = this::hideHud;

    private String channelLogin;
    private String displayName;
    private int sizeIndex = 1; // Medium (52%) default
    private boolean danmakuEnabled = true;
    private boolean dismissed;
    private boolean positionInitialized;

    MultiStreamPipOverlay(Activity activity, String login, String displayName) {
        this.activityRef = new WeakReference<>(activity);
        this.decor = (FrameLayout) activity.getWindow().getDecorView();
        this.channelLogin = login;
        this.displayName = displayName != null && !displayName.isEmpty() ? displayName : login;
        this.touchSlop = Math.max(4, ViewConfiguration.get(activity).getScaledTouchSlop());

        // Remove any stale overlay left on this decor view.
        View stale = decor.findViewWithTag(TAG_VIEW);
        if (stale != null) {
            try { decor.removeView(stale); } catch (Throwable ignored) {}
        }

        card = new FrameLayout(activity);
        card.setTag(TAG_VIEW);
        card.setBackground(TwitchUi.roundedRect(
                0xFF0E0E10,
                TwitchUi.dp(activity, 10f),
                Math.max(1, TwitchUi.dp(activity, 1.5f)),
                0xFF9147FF));
        card.setClipToOutline(true);
        card.setElevation(TwitchUi.dp(activity, 14f));
        card.setClickable(true);

        textureView = new TextureView(activity);
        card.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        // Proportional DanmakuView: lineHeight = getHeight() / style.rows (matches normal PiP ratio).
        danmakuView = new DanmakuView(activity);
        danmakuView.setClickable(false);
        danmakuView.setFocusable(false);
        card.addView(danmakuView, new FrameLayout.LayoutParams(-1, -1));

        loadingBar = new ProgressBar(activity);
        int spinnerSize = TwitchUi.dp(activity, 26f);
        card.addView(loadingBar, new FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER));

        errorLabel = new TextView(activity);
        errorLabel.setTextColor(Color.WHITE);
        errorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        errorLabel.setGravity(Gravity.CENTER);
        errorLabel.setPadding(TwitchUi.dp(activity, 8f), TwitchUi.dp(activity, 4f),
                TwitchUi.dp(activity, 8f), TwitchUi.dp(activity, 4f));
        errorLabel.setBackground(TwitchUi.roundedRect(0xCC0E0E10, TwitchUi.dp(activity, 6f), 0, 0));
        errorLabel.setVisibility(View.GONE);
        errorLabel.setOnClickListener(v -> reloadStream());
        card.addView(errorLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Mini HUD overlay (auto-hides after 3.5s)
        hudOverlay = new FrameLayout(activity);
        hudOverlay.setBackgroundColor(0x55000000);
        hudOverlay.setVisibility(View.VISIBLE);

        // Top bar inside PiP
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(TwitchUi.dp(activity, 6f), TwitchUi.dp(activity, 4f),
                TwitchUi.dp(activity, 4f), TwitchUi.dp(activity, 4f));

        titleBadge = new TextView(activity);
        titleBadge.setTextColor(Color.WHITE);
        titleBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        titleBadge.setTypeface(Typeface.DEFAULT_BOLD);
        titleBadge.setSingleLine(true);
        titleBadge.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(titleBadge, new LinearLayout.LayoutParams(0, -2, 1f));

        muteBtn = miniChip(activity, "🔇", v -> {
            player.setMuted(!player.isMuted());
            updateButtons();
            scheduleHudHide();
        });
        topRow.addView(muteBtn);

        danmakuBtn = miniChip(activity, "弾", v -> {
            danmakuEnabled = !danmakuEnabled;
            applyDanmakuState();
            updateButtons();
            scheduleHudHide();
        });
        topRow.addView(danmakuBtn);

        TextView closeBtn = miniChip(activity, "✕", v -> MultiStreamManager.closePip());
        topRow.addView(closeBtn);
        hudOverlay.addView(topRow, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom bar inside PiP
        LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bottomRow.setPadding(TwitchUi.dp(activity, 4f), TwitchUi.dp(activity, 4f),
                TwitchUi.dp(activity, 4f), TwitchUi.dp(activity, 4f));

        sizeBtn = miniChip(activity, SIZE_LABELS[sizeIndex], v -> {
            sizeIndex = (sizeIndex + 1) % WIDTH_RATIOS.length;
            applySizeAndClamp();
            updateButtons();
            scheduleHudHide();
        });
        bottomRow.addView(sizeBtn);

        TextView qualityBtn = miniChip(activity, "⚙", v -> {
            showQualityPicker();
            scheduleHudHide();
        });
        bottomRow.addView(qualityBtn);

        TextView switchBtn = miniChip(activity, "切替", v -> {
            Activity act = activityRef.get();
            if (act == null) return;
            ChannelPickerSheet.show(act, "サブ小窓の配信を変更",
                    Collections.singleton(channelLogin),
                    (newLogin, newDisplay) -> switchChannel(newLogin, newDisplay));
        });
        bottomRow.addView(switchBtn);

        TextView expandBtn = miniChip(activity, "⊞ 拡大", v -> {
            Activity act = activityRef.get();
            if (act != null) {
                MultiStreamManager.expandPipToMultiView(act, channelLogin, displayName);
            }
        });
        expandBtn.setBackground(TwitchUi.roundedRect(0xCC9147FF, TwitchUi.dp(activity, 4f), 0, 0));
        bottomRow.addView(expandBtn);

        hudOverlay.addView(bottomRow, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.addView(hudOverlay, new FrameLayout.LayoutParams(-1, -1));

        setupDragAndTap(card);

        player = new MultiStreamPlayer(activity, new MultiStreamPlayer.Listener() {
            @Override
            public void onStateChanged(boolean loading, String errorMessage) {
                if (dismissed) return;
                loadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
                if (errorMessage != null) {
                    errorLabel.setText(errorMessage + "\nタップで再読込");
                    errorLabel.setVisibility(View.VISIBLE);
                } else {
                    errorLabel.setVisibility(View.GONE);
                }
            }

            @Override
            public void onQualityChanged(String qualityLabel) {
                if (!dismissed) updateTitleBadge();
            }
        });
        player.setMuted(true);
        player.attachTextureView(textureView);

        ircClient = new MultiStreamIrcClient(activity, comment -> {
            if (!dismissed && danmakuEnabled) {
                danmakuView.addComment(comment);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT);
        decor.addView(card, lp);
        applySizeAndClamp();
        updateTitleBadge();
        updateButtons();
        applyDanmakuState();

        decorLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (!dismissed && (r - l != or - ol || b - t != ob - ot)) {
                applySizeAndClamp();
            }
        };
        decor.addOnLayoutChangeListener(decorLayoutListener);

        reloadStream();
        ircClient.joinChannel(channelLogin);
        scheduleHudHide();
    }

    String getChannelLogin() {
        return channelLogin;
    }

    String getDisplayName() {
        return displayName;
    }

    boolean isShowing() {
        return !dismissed && card.getParent() == decor;
    }

    boolean belongsTo(Activity activity) {
        return activity != null && activityRef.get() == activity;
    }

    void switchChannel(String newLogin, String newDisplay) {
        if (dismissed || newLogin == null || newLogin.isEmpty()) return;
        this.channelLogin = newLogin;
        this.displayName = newDisplay != null && !newDisplay.isEmpty() ? newDisplay : newLogin;
        danmakuView.clear();
        updateTitleBadge();
        reloadStream();
        ircClient.joinChannel(channelLogin);
        showHud();
    }

    void pause() {
        if (dismissed) return;
        player.pause();
        danmakuView.setPaused(true);
    }

    void resume() {
        if (dismissed) return;
        player.resume();
        danmakuView.setPaused(!danmakuEnabled);
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        main.removeCallbacks(hideHudRunnable);
        try { decor.removeOnLayoutChangeListener(decorLayoutListener); } catch (Throwable ignored) {}
        player.release();
        ircClient.disconnect();
        danmakuView.clear();
        try { decor.removeView(card); } catch (Throwable ignored) {}
    }

    private void reloadStream() {
        if (dismissed) return;
        Activity act = activityRef.get();
        if (act == null) return;
        loadingBar.setVisibility(View.VISIBLE);
        errorLabel.setVisibility(View.GONE);
        String targetLogin = channelLogin;
        Context appCtx = act.getApplicationContext();
        KizuRuntime.ioExecutor().execute(() -> {
            MultiStreamApi.StreamLoadResult res = MultiStreamApi.fetchStreamUri(appCtx, targetLogin);
            main.post(() -> {
                if (dismissed || !targetLogin.equals(channelLogin)) return;
                if (res.playUri == null) {
                    loadingBar.setVisibility(View.GONE);
                    errorLabel.setText((res.error != null ? res.error : "読み込み失敗") + "\nタップで再読込");
                    errorLabel.setVisibility(View.VISIBLE);
                    return;
                }
                player.load(res.playUri, res.directUsherUri, 480);
            });
        });
    }

    private void showQualityPicker() {
        Activity act = activityRef.get();
        if (act == null || dismissed) return;
        List<String> labels = player.getQualityLabels();
        if (labels.isEmpty()) return;
        String[] items = labels.toArray(new String[0]);
        SettingsUi.dialog(act)
                .setTitle("サブ小窓の画質 (" + displayName + ")")
                .setItems(items, (dialog, which) -> player.selectQuality(which))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyDanmakuState() {
        danmakuView.setVisibility(danmakuEnabled ? View.VISIBLE : View.INVISIBLE);
        danmakuView.setPaused(!danmakuEnabled);
        if (!danmakuEnabled) danmakuView.clear();
    }

    private void updateTitleBadge() {
        titleBadge.setText("● " + displayName + " · " + player.getCurrentQualityLabel());
    }

    private void updateButtons() {
        muteBtn.setText(player.isMuted() ? "🔇" : "🔊");
        danmakuBtn.setTextColor(danmakuEnabled ? 0xFFBF94FF : 0xAAFFFFFF);
        sizeBtn.setText(SIZE_LABELS[sizeIndex]);
    }

    private void showHud() {
        hudOverlay.setVisibility(View.VISIBLE);
        scheduleHudHide();
    }

    private void hideHud() {
        if (!dismissed) hudOverlay.setVisibility(View.GONE);
    }

    private void scheduleHudHide() {
        main.removeCallbacks(hideHudRunnable);
        main.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS);
    }

    private void applySizeAndClamp() {
        int parentW = decor.getWidth();
        int parentH = decor.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            parentW = decor.getResources().getDisplayMetrics().widthPixels;
            parentH = decor.getResources().getDisplayMetrics().heightPixels;
        }
        int baseDim = Math.min(parentW, parentH);
        int w = Math.round((parentW > parentH ? parentW * 0.65f : baseDim) * WIDTH_RATIOS[sizeIndex]);
        int minW = TwitchUi.dp(decor.getContext(), 136f);
        int maxW = Math.max(minW, parentW - TwitchUi.dp(decor.getContext(), 16f));
        w = Math.max(minW, Math.min(maxW, w));
        int h = Math.round(w * 9f / 16f);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.width = w;
        lp.height = h;
        int margin = TwitchUi.dp(decor.getContext(), 8f);
        int topSafe = TwitchUi.dp(decor.getContext(), 40f);
        int bottomSafe = TwitchUi.dp(decor.getContext(), 56f);

        if (!positionInitialized) {
            positionInitialized = true;
            lp.leftMargin = Math.max(margin, parentW - w - margin);
            lp.topMargin = Math.max(topSafe, Math.round(parentH * 0.34f));
        }
        lp.leftMargin = Math.max(margin, Math.min(Math.max(margin, parentW - w - margin), lp.leftMargin));
        lp.topMargin = Math.max(topSafe, Math.min(Math.max(topSafe, parentH - h - bottomSafe), lp.topMargin));
        card.setLayoutParams(lp);
    }

    private void setupDragAndTap(View target) {
        final float[] downRaw = new float[2];
        final int[] startMargin = new int[2];
        final boolean[] dragging = {false};

        target.setOnTouchListener((v, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startMargin[0] = lp.leftMargin;
                    startMargin[1] = lp.topMargin;
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        int parentW = decor.getWidth();
                        int parentH = decor.getHeight();
                        int margin = TwitchUi.dp(decor.getContext(), 6f);
                        int topSafe = TwitchUi.dp(decor.getContext(), 28f);
                        int bottomSafe = TwitchUi.dp(decor.getContext(), 24f);
                        lp.leftMargin = Math.max(margin, Math.min(
                                Math.max(margin, parentW - lp.width - margin),
                                Math.round(startMargin[0] + dx)));
                        lp.topMargin = Math.max(topSafe, Math.min(
                                Math.max(topSafe, parentH - lp.height - bottomSafe),
                                Math.round(startMargin[1] + dy)));
                        card.setLayoutParams(lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        if (hudOverlay.getVisibility() == View.VISIBLE) {
                            hideHud();
                        } else {
                            showHud();
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private static TextView miniChip(Context c, String label, View.OnClickListener onClick) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 3f), TwitchUi.dp(c, 6f), TwitchUi.dp(c, 3f));
        tv.setBackground(TwitchUi.roundedRect(0x9918181B, TwitchUi.dp(c, 4f), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = TwitchUi.dp(c, 4f);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(onClick);
        return tv;
    }
}
