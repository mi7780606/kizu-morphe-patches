package app.morphe.extension.multistream;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
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
 * Mode A: Draggable and pinch-resizable (up to 100% screen width) 16:9 floating sub-stream PiP
 * overlay docked over Twitch's normal playback/chat screen, with proportional {@link DanmakuView}.
 */
final class MultiStreamPipOverlay {
    private static final String TAG_VIEW = "kizu_multi_pip_overlay";
    private static final float[] WIDTH_RATIOS = {0.38f, 0.56f, 0.78f, 1.00f};
    private static final String[] SIZE_LABELS = {"小", "中", "大", "最大"};
    private static final long HUD_AUTO_HIDE_MS = 3500L;

    private final WeakReference<Activity> activityRef;
    private final FrameLayout decor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private final FrameLayout card;
    private final TextureView textureView;
    private final MultiStreamAdCoverView adCoverView;
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
    private final Runnable clearAdCoverRunnable = this::clearAdCoverNow;
    private final Runnable pipSyncRunnable = this::syncSystemPipVisibility;

    private String channelLogin;
    private String displayName;
    private String proxySessionId;
    private boolean adSwappedToBackup;
    private int sizeIndex = 1; // Medium (56%) default
    private float currentWidthRatio = WIDTH_RATIOS[1];
    private boolean danmakuEnabled = true;
    private boolean dismissed;
    private boolean lifecyclePaused;
    private boolean hiddenForSystemPip;
    private boolean hasSavedPrePipLayout;
    private int savedPrePipLeft;
    private int savedPrePipTop;
    private float savedPrePipWidthRatio;
    private boolean positionInitialized;
    private boolean lastFullWidthVisual;

    MultiStreamPipOverlay(Activity activity, String login, String displayName) {
        this.activityRef = new WeakReference<>(activity);
        this.decor = (FrameLayout) activity.getWindow().getDecorView();
        this.channelLogin = login;
        this.displayName = displayName != null && !displayName.isEmpty() ? displayName : login;
        this.touchSlop = Math.max(4, ViewConfiguration.get(activity).getScaledTouchSlop());

        View stale = decor.findViewWithTag(TAG_VIEW);
        if (stale != null) {
            try { decor.removeView(stale); } catch (Throwable ignored) {}
        }

        card = new FrameLayout(activity) {
            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                syncSystemPipVisibility();
            }
        };
        card.setTag(TAG_VIEW);
        applyCardChrome(false);
        card.setClipToOutline(true);
        card.setElevation(TwitchUi.dp(activity, 14f));
        card.setClickable(true);

        textureView = new TextureView(activity);
        card.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        adCoverView = new MultiStreamAdCoverView(activity);
        card.addView(adCoverView, new FrameLayout.LayoutParams(-1, -1));

        // Proportional DanmakuView: lineHeight = getHeight() / style.rows (exact normal PiP ratio).
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

        hudOverlay = new FrameLayout(activity);
        hudOverlay.setBackgroundColor(0x55000000);
        hudOverlay.setVisibility(View.VISIBLE);

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

        muteBtn = miniChip(activity, "🔇", v -> toggleMute());
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

        LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bottomRow.setPadding(TwitchUi.dp(activity, 4f), TwitchUi.dp(activity, 4f),
                TwitchUi.dp(activity, 4f), TwitchUi.dp(activity, 4f));

        sizeBtn = miniChip(activity, SIZE_LABELS[sizeIndex], v -> {
            hasSavedPrePipLayout = false;
            sizeIndex = (sizeIndex + 1) % WIDTH_RATIOS.length;
            currentWidthRatio = WIDTH_RATIOS[sizeIndex];
            applySizeAndClamp(true);
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

        setupDragPinchAndTap(activity, card);

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

        ircClient = new MultiStreamIrcClient(activity, (channel, comment) -> {
            if (!dismissed && !hiddenForSystemPip && danmakuEnabled
                    && channel != null && channel.equalsIgnoreCase(channelLogin)) {
                danmakuView.addComment(comment);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT);
        decor.addView(card, lp);
        applySizeAndClamp(false);
        updateTitleBadge();
        updateButtons();
        applyDanmakuState();

        decorLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (dismissed) return;
            syncSystemPipVisibility();
            if (hiddenForSystemPip) return;
            if (hasSavedPrePipLayout) {
                restorePrePipLayoutIfReady();
                return;
            }
            if (r - l != or - ol || b - t != ob - ot) {
                applySizeAndClamp(true);
            }
        };
        decor.addOnLayoutChangeListener(decorLayoutListener);
        syncSystemPipVisibility();

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
        lifecyclePaused = true;
        player.pause();
        danmakuView.setPaused(true);
        syncSystemPipVisibility();
        main.removeCallbacks(pipSyncRunnable);
        main.post(pipSyncRunnable);
        main.postDelayed(pipSyncRunnable, 120L);
    }

    void resume() {
        if (dismissed) return;
        lifecyclePaused = false;
        syncSystemPipVisibility();
        main.removeCallbacks(pipSyncRunnable);
        main.post(pipSyncRunnable);
        if (!hiddenForSystemPip) {
            player.resume();
            danmakuView.setPaused(!danmakuEnabled);
        }
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        main.removeCallbacks(hideHudRunnable);
        main.removeCallbacks(pipSyncRunnable);
        releaseProxySession();
        try { decor.removeOnLayoutChangeListener(decorLayoutListener); } catch (Throwable ignored) {}
        player.release();
        ircClient.disconnect();
        danmakuView.clear();
        try { decor.removeView(card); } catch (Throwable ignored) {}
    }

    private boolean isActivityInSystemPip() {
        Activity act = activityRef.get();
        return act != null && !act.isDestroyed() && act.isInPictureInPictureMode();
    }

    private void syncSystemPipVisibility() {
        if (dismissed) return;
        boolean inSystemPip = isActivityInSystemPip();
        if (inSystemPip && !hiddenForSystemPip) {
            enterSystemPipHidden();
        } else if (!inSystemPip && hiddenForSystemPip) {
            exitSystemPipHidden();
        }
    }

    private void enterSystemPipHidden() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp != null && positionInitialized && !hasSavedPrePipLayout) {
            savedPrePipLeft = lp.leftMargin;
            savedPrePipTop = lp.topMargin;
            savedPrePipWidthRatio = currentWidthRatio;
            hasSavedPrePipLayout = true;
        }
        hiddenForSystemPip = true;
        card.setVisibility(View.GONE);
        player.pause();
        danmakuView.setPaused(true);
    }

    private void exitSystemPipHidden() {
        hiddenForSystemPip = false;
        card.setVisibility(View.VISIBLE);
        restorePrePipLayoutIfReady();
        if (!lifecyclePaused) {
            player.resume();
            danmakuView.setPaused(!danmakuEnabled);
        }
    }

    private void restorePrePipLayoutIfReady() {
        if (!hasSavedPrePipLayout) {
            applySizeAndClamp(false);
            return;
        }
        int dw = decor.getWidth();
        int dh = decor.getHeight();
        int minAppDim = TwitchUi.dp(decor.getContext(), 280f);
        boolean fullSizeRestored = dw >= minAppDim && dh >= minAppDim;

        currentWidthRatio = savedPrePipWidthRatio;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp != null) {
            lp.leftMargin = savedPrePipLeft;
            lp.topMargin = savedPrePipTop;
        }
        applySizeAndClamp(false);
        updateButtons();
        if (fullSizeRestored) {
            hasSavedPrePipLayout = false;
        }
    }

    private void reloadStream() {
        if (dismissed) return;
        Activity act = activityRef.get();
        if (act == null) return;
        releaseProxySession();
        loadingBar.setVisibility(View.VISIBLE);
        errorLabel.setVisibility(View.GONE);
        String targetLogin = channelLogin;
        Context appCtx = act.getApplicationContext();
        KizuRuntime.ioExecutor().execute(() -> {
            MultiStreamApi.StreamLoadResult res = MultiStreamApi.fetchStreamUri(appCtx, targetLogin);
            main.post(() -> {
                if (dismissed || !targetLogin.equals(channelLogin)) {
                    MultiStreamHlsProxy.getInstance().unregisterSession(res.proxySessionId);
                    return;
                }
                if (res.playUri == null) {
                    MultiStreamHlsProxy.getInstance().unregisterSession(res.proxySessionId);
                    loadingBar.setVisibility(View.GONE);
                    errorLabel.setText((res.error != null ? res.error : "読み込み失敗") + "\nタップで再読込");
                    errorLabel.setVisibility(View.VISIBLE);
                    return;
                }
                proxySessionId = res.proxySessionId;
                if (proxySessionId != null) {
                    MultiStreamHlsProxy.getInstance().setAdStateListener(proxySessionId, this::onAdStateChanged);
                }
                player.load(res.playUri, res.directUsherUri, 480);
            });
        });
    }

    private void releaseProxySession() {
        main.removeCallbacks(clearAdCoverRunnable);
        if (proxySessionId != null) {
            MultiStreamHlsProxy.getInstance().unregisterSession(proxySessionId);
            proxySessionId = null;
        }
        adSwappedToBackup = false;
        adCoverView.hideAdCover();
        if (player != null) {
            player.setAdMuted(false);
        }
    }

    private void onAdStateChanged(MultiStreamHlsProxy.AdState state, float remainingSeconds) {
        if (dismissed) return;
        if (state == MultiStreamHlsProxy.AdState.AD_BLOCKED_COVER) {
            main.removeCallbacks(clearAdCoverRunnable);
            adSwappedToBackup = false;
            player.setAdMuted(true);
            adCoverView.showAdCover(remainingSeconds);
            updateTitleBadge();
        } else if (state == MultiStreamHlsProxy.AdState.SWAPPED_TO_BACKUP) {
            main.removeCallbacks(clearAdCoverRunnable);
            adSwappedToBackup = true;
            player.setAdMuted(false);
            adCoverView.hideAdCover();
            updateTitleBadge();
        } else {
            adSwappedToBackup = false;
            updateTitleBadge();
            if (adCoverView.getVisibility() == View.VISIBLE) {
                main.removeCallbacks(clearAdCoverRunnable);
                main.postDelayed(clearAdCoverRunnable, 1200L);
            } else {
                player.setAdMuted(false);
            }
        }
    }

    private void clearAdCoverNow() {
        adCoverView.hideAdCover();
        if (player != null) {
            player.setAdMuted(false);
        }
    }

    private void toggleMute() {
        if (dismissed || player == null) return;
        player.setMuted(!player.isMuted());
        updateButtons();
        scheduleHudHide();
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
        String suffix = adSwappedToBackup ? " · 🛡" : "";
        titleBadge.setText("● " + displayName + " · " + player.getCurrentQualityLabel() + suffix);
    }

    private void updateButtons() {
        muteBtn.setText(player.isMuted() ? "🔇" : "🔊");
        danmakuBtn.setTextColor(danmakuEnabled ? 0xFFBF94FF : 0xAAFFFFFF);
        sizeBtn.setText(labelForRatio(currentWidthRatio));
    }

    private static String labelForRatio(float ratio) {
        if (ratio >= 0.94f) return SIZE_LABELS[3];
        if (ratio >= 0.68f) return SIZE_LABELS[2];
        if (ratio >= 0.48f) return SIZE_LABELS[1];
        return SIZE_LABELS[0];
    }

    private void applyCardChrome(boolean fullWidth) {
        if (positionInitialized && lastFullWidthVisual == fullWidth) return;
        lastFullWidthVisual = fullWidth;
        Context c = decor.getContext();
        float radius = fullWidth ? 0f : TwitchUi.dp(c, 10f);
        int stroke = fullWidth ? Math.max(1, TwitchUi.dp(c, 1f)) : Math.max(1, TwitchUi.dp(c, 1.5f));
        card.setBackground(TwitchUi.roundedRect(0xFF0E0E10, radius, stroke, 0xFF9147FF));
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

    private int parentWidth() {
        int w = decor.getWidth();
        return w > 0 ? w : decor.getResources().getDisplayMetrics().widthPixels;
    }

    private int parentHeight() {
        int h = decor.getHeight();
        return h > 0 ? h : decor.getResources().getDisplayMetrics().heightPixels;
    }

    private void applySizeAndClamp(boolean preserveCenter) {
        int parentW = parentWidth();
        int parentH = parentHeight();
        Context ctx = decor.getContext();
        int minW = TwitchUi.dp(ctx, 120f);
        int topSafe = TwitchUi.dp(ctx, 28f);
        int bottomSafe = TwitchUi.dp(ctx, 24f);

        int w = Math.max(minW, Math.min(parentW, Math.round(parentW * currentWidthRatio)));
        int h = Math.round(w * 9f / 16f);
        int maxH = Math.max(TwitchUi.dp(ctx, 68f), parentH - topSafe - bottomSafe);
        if (h > maxH) {
            h = maxH;
            w = Math.max(minW, Math.min(parentW, Math.round(h * 16f / 9f)));
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int oldCx = lp.leftMargin + lp.width / 2;
        int oldCy = lp.topMargin + lp.height / 2;

        boolean fullWidth = w >= parentW - TwitchUi.dp(ctx, 8f);
        int hMargin = fullWidth ? 0 : TwitchUi.dp(ctx, 6f);
        if (fullWidth) {
            w = parentW;
            h = Math.min(maxH, Math.round(w * 9f / 16f));
        }

        lp.width = w;
        lp.height = h;
        if (!positionInitialized) {
            positionInitialized = true;
            lp.leftMargin = Math.max(hMargin, parentW - w - hMargin);
            lp.topMargin = Math.max(topSafe, Math.round(parentH * 0.34f));
        } else if (preserveCenter) {
            lp.leftMargin = oldCx - w / 2;
            lp.topMargin = oldCy - h / 2;
        }
        lp.leftMargin = Math.max(hMargin, Math.min(Math.max(hMargin, parentW - w - hMargin), lp.leftMargin));
        lp.topMargin = Math.max(topSafe, Math.min(Math.max(topSafe, parentH - h - bottomSafe), lp.topMargin));
        applyCardChrome(fullWidth);
        card.setLayoutParams(lp);
    }

    private void setupDragPinchAndTap(Context ctx, View target) {
        final float[] downRaw = new float[2];
        final int[] startMargin = new int[2];
        final boolean[] dragging = {false};
        final boolean[] pinched = {false};

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(
                ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        hasSavedPrePipLayout = false;
                        pinched[0] = true;
                        dragging[0] = false;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) return false;
                        int parentW = parentWidth();
                        int parentH = parentHeight();
                        int minW = TwitchUi.dp(ctx, 120f);
                        int topSafe = TwitchUi.dp(ctx, 28f);
                        int bottomSafe = TwitchUi.dp(ctx, 24f);
                        int maxH = Math.max(TwitchUi.dp(ctx, 68f), parentH - topSafe - bottomSafe);

                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
                        int cx = lp.leftMargin + lp.width / 2;
                        int cy = lp.topMargin + lp.height / 2;

                        int nextW = Math.max(minW, Math.min(parentW, Math.round(lp.width * factor)));
                        int nextH = Math.round(nextW * 9f / 16f);
                        if (nextH > maxH) {
                            nextH = maxH;
                            nextW = Math.max(minW, Math.min(parentW, Math.round(nextH * 16f / 9f)));
                        }

                        boolean fullWidth = nextW >= parentW - TwitchUi.dp(ctx, 8f);
                        int hMargin = fullWidth ? 0 : TwitchUi.dp(ctx, 6f);
                        if (fullWidth) {
                            nextW = parentW;
                            nextH = Math.min(maxH, Math.round(nextW * 9f / 16f));
                        }

                        lp.width = nextW;
                        lp.height = nextH;
                        lp.leftMargin = Math.max(hMargin, Math.min(
                                Math.max(hMargin, parentW - nextW - hMargin),
                                cx - nextW / 2));
                        lp.topMargin = Math.max(topSafe, Math.min(
                                Math.max(topSafe, parentH - nextH - bottomSafe),
                                cy - nextH / 2));
                        currentWidthRatio = Math.max(0.25f, Math.min(1f, nextW / (float) Math.max(1, parentW)));
                        applyCardChrome(fullWidth);
                        card.setLayoutParams(lp);
                        sizeBtn.setText(labelForRatio(currentWidthRatio));
                        return true;
                    }
                });

        target.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startMargin[0] = lp.leftMargin;
                    startMargin[1] = lp.topMargin;
                    dragging[0] = false;
                    pinched[0] = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    pinched[0] = true;
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                        pinched[0] = true;
                        return true;
                    }
                    if (pinched[0]) return true;
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        hasSavedPrePipLayout = false;
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        int parentW = parentWidth();
                        int parentH = parentHeight();
                        boolean fullWidth = lp.width >= parentW - TwitchUi.dp(ctx, 8f);
                        int hMargin = fullWidth ? 0 : TwitchUi.dp(ctx, 6f);
                        int topSafe = TwitchUi.dp(ctx, 28f);
                        int bottomSafe = TwitchUi.dp(ctx, 24f);
                        lp.leftMargin = Math.max(hMargin, Math.min(
                                Math.max(hMargin, parentW - lp.width - hMargin),
                                Math.round(startMargin[0] + dx)));
                        lp.topMargin = Math.max(topSafe, Math.min(
                                Math.max(topSafe, parentH - lp.height - bottomSafe),
                                Math.round(startMargin[1] + dy)));
                        card.setLayoutParams(lp);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() == 2) {
                        int keepIdx = event.getActionIndex() == 0 ? 1 : 0;
                        downRaw[0] = event.getX(keepIdx) + card.getLeft();
                        downRaw[1] = event.getY(keepIdx) + card.getTop();
                        startMargin[0] = lp.leftMargin;
                        startMargin[1] = lp.topMargin;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0] && !pinched[0]) {
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
