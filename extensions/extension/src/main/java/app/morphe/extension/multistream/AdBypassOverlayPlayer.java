package app.morphe.extension.multistream;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * Full-player-fitted ad-bypass overlay player for normal (non-multi-mode) Twitch playback.
 *
 * <p>When a stitched ad starts on Twitch's native player, this controller attaches a lightweight
 * Amazon IVS {@link MultiStreamPlayer} directly inside {@code player_view_delegate} above
 * {@code AdBlockOverlay} and below {@code DanmakuView} and Twitch's player controls. It probes
 * clean backup {@code playerType} streams via {@link MultiStreamHlsProxy}, reveals video and
 * unmutes only once clean live video reaches {@code PLAYING}, falls back silently to
 * {@code AdBlockOverlay}'s dark screen if all backups also contain ads, and tears itself down
 * immediately when {@code LIVE_CONTENT} resumes.</p>
 */
public final class AdBypassOverlayPlayer {
    private static final String TAG = "KizuAdBypassPlayer";
    private static final String VIEW_TAG = "kizu_ad_bypass_player";
    private static final long RETRY_DELAY_MS = 4000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ActiveOverlay active;

    private AdBypassOverlayPlayer() {}

    /**
     * Starts (or keeps running) an ad-bypass overlay player for the current stitched ad break.
     * Must be called on the main thread.
     */
    public static void start(ViewGroup playerContainer, View adBlockView,
                             Object nativePlayer, String rawChannelLogin) {
        if (playerContainer == null || rawChannelLogin == null) return;
        if (!Settings.BLOCK_ADS.get() || !Settings.ADS_BYPASS_OVERLAY_PLAYER.get()) return;
        if (MultiStreamManager.isMultiModeActive()) return;

        String login = rawChannelLogin.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty()) return;

        // Already running for the same player & channel (e.g. ad 2/3 in the same pod): keep seamlessly.
        if (active != null && active.matches(playerContainer, nativePlayer, login)) {
            active.syncAdBlockView(adBlockView);
            return;
        }
        stopAll();

        Activity activity = Utils.findActivity(playerContainer.getContext());
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) return;

        ActiveOverlay next = new ActiveOverlay(playerContainer, adBlockView, nativePlayer, activity, login);
        active = next;
        next.attachAndStart();
    }

    /** Stops the overlay player associated with {@code nativePlayer} (if any). */
    public static void stop(Object nativePlayer) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> stop(nativePlayer));
            return;
        }
        ActiveOverlay cur = active;
        if (cur == null) return;
        if (nativePlayer == null || cur.belongsToNativePlayer(nativePlayer)) {
            active = null;
            cur.release();
        }
    }

    /** Stops any active overlay only if it is playing a different channel than {@code newChannelLogin}. */
    public static void stopIfChannelDiffers(String newChannelLogin) {
        if (newChannelLogin == null || newChannelLogin.trim().isEmpty()) return;
        String normalized = newChannelLogin.trim().toLowerCase(Locale.ROOT);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> stopIfChannelDiffers(normalized));
            return;
        }
        ActiveOverlay cur = active;
        if (cur != null && !cur.channelLogin.equals(normalized)) {
            active = null;
            cur.release();
        }
    }

    /** Immediately stops and releases any active ad-bypass overlay player. */
    public static void stopAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(AdBypassOverlayPlayer::stopAll);
            return;
        }
        ActiveOverlay cur = active;
        active = null;
        if (cur != null) {
            cur.release();
        }
    }

    static void onActivityPaused(Activity activity) {
        ActiveOverlay cur = active;
        if (cur != null && cur.belongsToActivity(activity)) {
            cur.pause();
        }
    }

    static void onActivityResumed(Activity activity) {
        ActiveOverlay cur = active;
        if (cur != null && cur.belongsToActivity(activity)) {
            cur.resume();
        }
    }

    static void onActivityDestroyed(Activity activity) {
        ActiveOverlay cur = active;
        if (cur != null && cur.belongsToActivity(activity)) {
            active = null;
            cur.release();
        }
    }

    private static final class ActiveOverlay {
        private final WeakReference<ViewGroup> containerRef;
        private WeakReference<View> adBlockViewRef;
        private final WeakReference<Object> nativePlayerRef;
        private final WeakReference<Activity> activityRef;
        private final String channelLogin;

        private final FrameLayout rootView;
        private final FrameLayout videoBox;
        private final TextureView textureView;
        private final LinearLayout statusChip;
        private final TextView muteBtn;
        private final MultiStreamPlayer player;
        private final View.OnLayoutChangeListener containerLayoutListener;
        private final Runnable retryRunnable = this::loadBackupStream;

        private String proxySessionId;
        private boolean streamRevealed;
        private boolean adCovered;
        private boolean userMuted;
        private boolean retryScheduled;
        private boolean pausedByLifecycle;
        private boolean released;

        ActiveOverlay(ViewGroup container, View adBlockView, Object nativePlayer,
                      Activity activity, String channelLogin) {
            this.containerRef = new WeakReference<>(container);
            this.adBlockViewRef = new WeakReference<>(adBlockView);
            this.nativePlayerRef = new WeakReference<>(nativePlayer);
            this.activityRef = new WeakReference<>(activity);
            this.channelLogin = channelLogin;

            Context c = container.getContext();
            rootView = new FrameLayout(c);
            rootView.setTag(VIEW_TAG);
            rootView.setBackgroundColor(Color.BLACK);
            rootView.setClickable(false);
            rootView.setFocusable(false);
            // MUST remain View.VISIBLE so TextureView immediately allocates its SurfaceTexture.
            // While loading, rootView sits directly underneath opaque AdBlockOverlay.view; once
            // clean playback starts, AdBlockOverlay.view is hidden to reveal rootView smoothly.
            rootView.setVisibility(View.VISIBLE);

            videoBox = new FrameLayout(c);
            videoBox.setBackgroundColor(Color.BLACK);
            videoBox.setClickable(false);
            videoBox.setFocusable(false);

            textureView = new TextureView(c);
            textureView.setClickable(false);
            textureView.setFocusable(false);
            videoBox.addView(textureView, new FrameLayout.LayoutParams(-1, -1));
            rootView.addView(videoBox, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

            // Compact unobtrusive badge + mute toggle in top-left corner (hidden automatically in mini-player/PiP).
            statusChip = new LinearLayout(c);
            statusChip.setOrientation(LinearLayout.HORIZONTAL);
            statusChip.setGravity(Gravity.CENTER_VERTICAL);
            statusChip.setPadding(TwitchUi.dp(c, 8f), TwitchUi.dp(c, 4f),
                    TwitchUi.dp(c, 6f), TwitchUi.dp(c, 4f));
            statusChip.setBackground(TwitchUi.roundedRect(0xAA18181B, TwitchUi.dp(c, 12f),
                    Math.max(1, TwitchUi.dp(c, 0.75f)), 0x669147FF));

            TextView badgeLabel = new TextView(c);
            badgeLabel.setText("🛡 広告回避中");
            badgeLabel.setTextColor(0xFFEFEFF1);
            badgeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            badgeLabel.setTypeface(Typeface.DEFAULT_BOLD);
            statusChip.addView(badgeLabel);

            muteBtn = new TextView(c);
            muteBtn.setText("🔊");
            muteBtn.setTextColor(Color.WHITE);
            muteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            muteBtn.setGravity(Gravity.CENTER);
            muteBtn.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 1f),
                    TwitchUi.dp(c, 2f), TwitchUi.dp(c, 1f));
            muteBtn.setClickable(true);
            muteBtn.setFocusable(true);
            muteBtn.setOnClickListener(v -> toggleUserMute());
            statusChip.addView(muteBtn);

            FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            chipLp.setMargins(TwitchUi.dp(c, 10f), TwitchUi.dp(c, 10f), 0, 0);
            rootView.addView(statusChip, chipLp);

            rootView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override
                public void onViewDetachedFromWindow(View v) {
                    if (active == ActiveOverlay.this) {
                        active = null;
                    }
                    release();
                }
            });

            containerLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (!released) {
                    fitVideoAspect16x9(r - l, b - t);
                }
            };

            player = new MultiStreamPlayer(c, new MultiStreamPlayer.Listener() {
                @Override
                public void onStateChanged(boolean loading, String errorMessage) {
                    if (released) return;
                    if (!loading && errorMessage == null) {
                        onPlaybackReady();
                    } else if (errorMessage != null) {
                        onPlaybackFailed(errorMessage);
                    }
                }

                @Override
                public void onQualityChanged(String qualityLabel) {}
            });
            player.setMuted(true);
            player.attachTextureView(textureView);
        }

        boolean matches(ViewGroup container, Object nativePlayer, String login) {
            if (released) return false;
            return containerRef.get() == container
                    && belongsToNativePlayer(nativePlayer)
                    && channelLogin.equals(login)
                    && rootView.getParent() == container;
        }

        boolean belongsToNativePlayer(Object nativePlayer) {
            Object cur = nativePlayerRef.get();
            return cur != null && cur == nativePlayer;
        }

        boolean belongsToActivity(Activity activity) {
            return activity != null && activityRef.get() == activity;
        }

        void syncAdBlockView(View adBlockView) {
            if (adBlockView != null) {
                adBlockViewRef = new WeakReference<>(adBlockView);
                adBlockView.setVisibility(streamRevealed && !adCovered ? View.INVISIBLE : View.VISIBLE);
            }
        }

        void attachAndStart() {
            ViewGroup container = containerRef.get();
            if (container == null || released) return;

            View stale = container.findViewWithTag(VIEW_TAG);
            if (stale != null) {
                try { container.removeView(stale); } catch (Throwable ignored) {}
            }

            // Insert directly UNDER AdBlockOverlay's dark cover (so TextureView is VISIBLE and
            // creates its SurfaceTexture immediately while AdBlockOverlay hides the loading state).
            View adView = adBlockViewRef.get();
            int adIdx = adView != null ? container.indexOfChild(adView) : -1;
            int insertIdx = adIdx >= 0
                    ? adIdx
                    : Math.min(1, container.getChildCount());

            container.addView(rootView, insertIdx, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            container.addOnLayoutChangeListener(containerLayoutListener);
            fitVideoAspect16x9(container.getWidth(), container.getHeight());

            loadBackupStream();
        }

        private void loadBackupStream() {
            if (released) return;
            ViewGroup container = containerRef.get();
            if (container == null) return;
            Context appCtx = container.getContext().getApplicationContext();

            releaseProxySession();
            KizuRuntime.ioExecutor().execute(() -> {
                MultiStreamHlsProxy.SessionHandle handle =
                        MultiStreamHlsProxy.getInstance().registerBackupFirstSession(appCtx, channelLogin);
                MAIN.post(() -> {
                    if (released || active != this) {
                        if (handle != null) {
                            MultiStreamHlsProxy.getInstance().unregisterSession(handle.sessionId);
                        }
                        return;
                    }
                    if (handle == null || handle.localMasterUri == null) {
                        Log.i(TAG, "No clean backup stream currently available for " + channelLogin);
                        hideVideoOverlay();
                        scheduleOneRetry();
                        return;
                    }
                    proxySessionId = handle.sessionId;
                    MultiStreamHlsProxy.getInstance().setAdStateListener(proxySessionId, this::onProxyAdState);
                    adCovered = false;
                    // Pass directFallbackUri=null so overlay NEVER falls back to an ad-stitched primary stream.
                    player.load(handle.localMasterUri, null, 0);
                });
            });
        }

        private void onPlaybackReady() {
            if (released || adCovered) return;
            streamRevealed = true;
            if (!pausedByLifecycle) {
                player.setAdMuted(false);
                player.setMuted(userMuted);
            }
            setAdBlockCoverVisible(false);
            updateMuteChip();
        }

        private void onPlaybackFailed(String reason) {
            Log.i(TAG, "Ad-bypass overlay playback failed for " + channelLogin + ": " + reason);
            hideVideoOverlay();
            scheduleOneRetry();
        }

        private void onProxyAdState(MultiStreamHlsProxy.AdState state, float remainingSeconds) {
            if (released) return;
            if (state == MultiStreamHlsProxy.AdState.AD_BLOCKED_COVER) {
                adCovered = true;
                player.setAdMuted(true);
                hideVideoOverlay();
            } else {
                adCovered = false;
                player.setAdMuted(false);
                if (streamRevealed) {
                    if (!pausedByLifecycle) {
                        player.setMuted(userMuted);
                    }
                    setAdBlockCoverVisible(false);
                }
            }
        }

        private void hideVideoOverlay() {
            streamRevealed = false;
            player.setMuted(true);
            setAdBlockCoverVisible(true);
        }

        private void setAdBlockCoverVisible(boolean visible) {
            View adView = adBlockViewRef.get();
            if (adView != null) {
                int target = visible ? View.VISIBLE : View.INVISIBLE;
                if (adView.getVisibility() != target) {
                    adView.setVisibility(target);
                }
            }
        }

        private void scheduleOneRetry() {
            if (released || retryScheduled) return;
            retryScheduled = true;
            MAIN.removeCallbacks(retryRunnable);
            MAIN.postDelayed(retryRunnable, RETRY_DELAY_MS);
        }

        private void toggleUserMute() {
            if (released) return;
            userMuted = !userMuted;
            if (streamRevealed && !adCovered && !pausedByLifecycle) {
                player.setMuted(userMuted);
            }
            updateMuteChip();
        }

        private void updateMuteChip() {
            muteBtn.setText(userMuted ? "🔇" : "🔊");
        }

        private void fitVideoAspect16x9(int containerW, int containerH) {
            if (containerW <= 0 || containerH <= 0) return;
            int fitW = containerW;
            int fitH = Math.round(containerW * 9f / 16f);
            if (fitH > containerH) {
                fitH = containerH;
                fitW = Math.round(containerH * 16f / 9f);
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoBox.getLayoutParams();
            if (lp.width != fitW || lp.height != fitH) {
                lp.width = Math.max(1, fitW);
                lp.height = Math.max(1, fitH);
                lp.gravity = Gravity.CENTER;
                videoBox.setLayoutParams(lp);
            }
            int minChipH = TwitchUi.dp(rootView.getContext(), 110f);
            statusChip.setVisibility(containerH >= minChipH ? View.VISIBLE : View.GONE);
        }

        void pause() {
            if (released) return;
            pausedByLifecycle = true;
            player.setMuted(true);
            player.pause();
        }

        void resume() {
            if (released) return;
            pausedByLifecycle = false;
            if (streamRevealed && !adCovered) {
                player.setMuted(userMuted);
            }
            player.resume();
        }

        private void releaseProxySession() {
            if (proxySessionId != null) {
                MultiStreamHlsProxy.getInstance().unregisterSession(proxySessionId);
                proxySessionId = null;
            }
        }

        void release() {
            if (released) return;
            released = true;
            MAIN.removeCallbacks(retryRunnable);
            releaseProxySession();
            player.setMuted(true);
            player.release();
            ViewGroup container = containerRef.get();
            if (container != null) {
                try { container.removeOnLayoutChangeListener(containerLayoutListener); } catch (Throwable ignored) {}
            }
            ViewParent parent = rootView.getParent();
            if (parent instanceof ViewGroup group) {
                try { group.removeView(rootView); } catch (Throwable ignored) {}
            }
            setAdBlockCoverVisible(true);
        }
    }
}
