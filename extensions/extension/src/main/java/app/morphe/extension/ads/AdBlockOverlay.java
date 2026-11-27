package app.morphe.extension.ads;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.Utils;
import app.morphe.extension.multistream.AdBypassOverlayPlayer;
import app.morphe.extension.multistream.MultiStreamManager;
import app.morphe.extension.settings.Settings;
import app.morphe.extension.settings.TwitchUi;

/**
 * An ad break stitched into the stream of one player: the player is muted and its video is
 * covered with a Twitch-styled dark overlay showing "Ad blocked", tabular seconds countdown,
 * and a subtle purple progress bar.
 */
final class AdBlockOverlay {
    static final String VIEW_TAG = "uyu_ad_blocked";

    private static final long END_GRACE_MILLIS = 5000;
    private static final int BG_COLOR = 0xFF0E0E10;
    private static final int CARD_COLOR = 0xFF18181B;
    private static final int BORDER_COLOR = 0xFF2F2F35;
    private static final int TEXT_PRIMARY = 0xFFEFEFF1;
    private static final int TEXT_SECONDARY = 0xFFADADB8;
    private static final int ACCENT = 0xFFBF94FF;
    private static final int ACCENT_BAR = 0xFF9147FF;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Map<Object, AdBlockOverlay> breaks = new WeakHashMap<>();
    private static volatile boolean anyActive;

    private final WeakReference<Object> player;
    private final Runnable tick = this::tick;
    private WeakReference<View> lastRenderView = new WeakReference<>(null);
    private long startTime;
    private long endTime;
    private long lastDisplayedSeconds = -2L;
    private View view;
    private TextView remaining;
    private View progressFill;
    private int progressTrackWidth;

    private AdBlockOverlay(Object player) {
        this.player = new WeakReference<>(player);
    }

    static boolean hasActiveBreaks() {
        return anyActive;
    }

    static void show(View renderView, Object player, float adSeconds, float adBreakSeconds) {
        long now = SystemClock.uptimeMillis();
        AdBlockOverlay adBreak = breaks.get(player);
        float fallbackSec = adBreakSeconds > 0 ? adBreakSeconds : (adSeconds > 0 ? adSeconds : 30f);
        if (adBreak == null) {
            adBreak = new AdBlockOverlay(player);
            breaks.put(player, adBreak);
            anyActive = true;
            adBreak.startTime = now;
            adBreak.endTime = now + (long) (fallbackSec * 1000);
            PlayerEvents.setMuted(player, true);
        } else {
            float extendSec = adSeconds > 0 ? adSeconds : (adBreakSeconds > 0 ? adBreakSeconds : 15f);
            adBreak.endTime = Math.max(adBreak.endTime, now + (long) (extendSec * 1000));
        }
        if (renderView != null) {
            adBreak.lastRenderView = new WeakReference<>(renderView);
        }
        adBreak.lastDisplayedSeconds = -2L;
        adBreak.cover(renderView);
        adBreak.tick();
        Utils.logInfo(String.format(Locale.ROOT, "Ad blocked: %.1f s ad, %.1f s break",
                adSeconds, adBreakSeconds));
    }

    /**
     * Called on {@link PlayerEvents#STITCHED_AD_QUARTILE} to recover if a stream-start preroll
     * fired {@code STITCHED_AD_STARTED} before the player view attached, and to keep the overlay
     * alive across long multi-ad pods.
     */
    static void onAdQuartile(View renderView, Object player) {
        long now = SystemClock.uptimeMillis();
        AdBlockOverlay adBreak = breaks.get(player);
        if (adBreak == null) {
            show(renderView, player, 20f, 20f);
            return;
        }
        if (renderView != null) {
            adBreak.lastRenderView = new WeakReference<>(renderView);
        }
        adBreak.endTime = Math.max(adBreak.endTime, now + 12_000L);
        PlayerEvents.setMuted(player, true);
        if (adBreak.view == null || adBreak.view.getParent() == null) {
            adBreak.cover(renderView);
        } else if (adBreak.view.getParent() instanceof ViewGroup group) {
            adBreak.maybeStartBypassOverlay(group);
        }
    }

    static void end(Object player) {
        AdBlockOverlay adBreak = breaks.remove(player);
        anyActive = !breaks.isEmpty();
        if (adBreak != null) adBreak.finish();
    }

    private void cover(View renderView) {
        ViewGroup container = findPlayerView(renderView);
        if (container == null) {
            if (view == null) Utils.logInfo("Ad blocked, but the player view was not found. The player is muted.");
            return;
        }
        if (view == null || view.getParent() != container) {
            removeView();
            view = createView(container.getContext());
            container.addView(view, Math.min(1, container.getChildCount()), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        maybeStartBypassOverlay(container);
    }

    private void maybeStartBypassOverlay(ViewGroup container) {
        try {
            if (!Settings.ADS_BYPASS_OVERLAY_PLAYER.get()) return;
            if (MultiStreamManager.isMultiModeActive()) return;
            Object current = player.get();
            if (current == null) return;
            String login = BlockAdsPatch.getCurrentLiveChannelLogin();
            if (login == null || login.isEmpty()) return;
            AdBypassOverlayPlayer.start(container, view, current, login);
        } catch (Throwable ignored) {}
    }

    private View createView(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        FrameLayout layout = new FrameLayout(context);
        layout.setTag(VIEW_TAG);
        layout.setBackgroundColor(BG_COLOR);
        layout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                mainHandler.removeCallbacks(tick);
                try { AdBypassOverlayPlayer.stop(player.get()); } catch (Throwable ignored) {}
            }
        });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(d, 22), dp(d, 16), dp(d, 22), dp(d, 16));
        card.setBackground(TwitchUi.roundedRect(CARD_COLOR, 14f * d, Math.max(1, dp(d, 1)), BORDER_COLOR));

        ImageView icon = new ImageView(context);
        int iconSize = dp(d, 26);
        icon.setImageDrawable(new ShieldDrawable(ACCENT, iconSize));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.bottomMargin = dp(d, 8);
        card.addView(icon, iconLp);

        TextView title = new TextView(context);
        title.setText("Ad blocked");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        remaining = new TextView(context);
        remaining.setTextColor(TEXT_SECONDARY);
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        remaining.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(-2, -2);
        remLp.topMargin = dp(d, 4);
        card.addView(remaining, remLp);

        progressTrackWidth = dp(d, 140);
        FrameLayout track = new FrameLayout(context);
        track.setBackground(TwitchUi.roundedRect(BORDER_COLOR, 2f * d, 0, 0));
        progressFill = new View(context);
        progressFill.setBackground(TwitchUi.roundedRect(ACCENT_BAR, 2f * d, 0, 0));
        track.addView(progressFill, new FrameLayout.LayoutParams(progressTrackWidth, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(progressTrackWidth, dp(d, 3));
        barLp.topMargin = dp(d, 10);
        card.addView(track, barLp);

        layout.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return layout;
    }

    private void tick() {
        mainHandler.removeCallbacks(tick);
        long now = SystemClock.uptimeMillis();
        long left = endTime - now;
        if (left <= -END_GRACE_MILLIS) {
            Object current = player.get();
            if (current != null) breaks.remove(current);
            anyActive = !breaks.isEmpty();
            finish();
            return;
        }
        // Retry mounting cover + bypass overlay if preroll fired before player_view_delegate attached.
        boolean needsMount = view == null || view.getParent() == null;
        if (needsMount) {
            cover(lastRenderView.get());
            needsMount = view == null || view.getParent() == null;
        }
        long seconds = left > 0 ? (left + 999L) / 1000L : 0L;
        if (seconds != lastDisplayedSeconds) {
            lastDisplayedSeconds = seconds;
            if (remaining != null) {
                remaining.setText(seconds > 0
                        ? "The stream resumes in " + seconds + " s"
                        : "The stream resumes soon");
            }
            if (progressFill != null && progressTrackWidth > 0) {
                long total = Math.max(1000L, endTime - startTime);
                float ratio = Math.max(0f, Math.min(1f, left / (float) total));
                int w = Math.round(progressTrackWidth * ratio);
                ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
                if (lp != null && lp.width != w) {
                    lp.width = w;
                    progressFill.setLayoutParams(lp);
                }
            }
        }
        long nextDelay = needsMount
                ? 200L
                : (seconds > 0
                ? Math.max(100L, Math.min(1000L, left - (seconds - 1L) * 1000L + 16L))
                : Math.max(150L, Math.min(1000L, left + END_GRACE_MILLIS)));
        mainHandler.postDelayed(tick, nextDelay);
    }

    private void finish() {
        mainHandler.removeCallbacks(tick);
        Object current = player.get();
        try { AdBypassOverlayPlayer.stop(current); } catch (Throwable ignored) {}
        if (current != null) PlayerEvents.setMuted(current, false);
        removeView();
        Utils.logInfo("Ad break ended");
    }

    private void removeView() {
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup group) group.removeView(view);
        view = null;
        remaining = null;
        progressFill = null;
    }

    private static ViewGroup findPlayerView(View renderView) {
        if (renderView != null) {
            int playerId = Utils.getResourceId(renderView.getContext(), "player_view_delegate", "id");
            for (ViewParent parent = renderView.getParent(); parent instanceof View v; parent = parent.getParent()) {
                if (playerId != 0 && v.getId() == playerId && v instanceof FrameLayout layout) return layout;
            }
            if (renderView.isAttachedToWindow()) {
                ViewParent parent = renderView.getParent();
                if (parent instanceof FrameLayout layout) return layout;
            }
        }
        // Fallback for stream-start prerolls when STITCHED_AD_STARTED fires before renderView attaches.
        android.app.Activity activity = Utils.getCurrentActivity();
        if (activity != null && activity.getWindow() != null) {
            View decor = activity.getWindow().getDecorView();
            if (decor != null) {
                int playerId = Utils.getResourceId(activity, "player_view_delegate", "id");
                if (playerId != 0) {
                    View found = decor.findViewById(playerId);
                    if (found instanceof FrameLayout layout && layout.isAttachedToWindow()) {
                        return layout;
                    }
                }
            }
        }
        return null;
    }

    private static int dp(float d, int value) {
        return Math.round(value * d);
    }

    private static final class ShieldDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int size;

        ShieldDrawable(int color, int size) {
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) return;
            float l = b.exactCenterX() - s / 2f;
            float t = b.exactCenterY() - s / 2f;
            float u = s / 24f;
            paint.setStrokeWidth(Math.max(1.5f, 1.9f * u));

            path.reset();
            path.moveTo(l + 12 * u, t + 3 * u);
            path.lineTo(l + 19 * u, t + 6 * u);
            path.lineTo(l + 19 * u, t + 11.5f * u);
            path.cubicTo(l + 19 * u, t + 16.2f * u, l + 15.8f * u, t + 19.6f * u, l + 12 * u, t + 21 * u);
            path.cubicTo(l + 8.2f * u, t + 19.6f * u, l + 5 * u, t + 16.2f * u, l + 5 * u, t + 11.5f * u);
            path.lineTo(l + 5 * u, t + 6 * u);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawLine(l + 8.5f * u, t + 8.5f * u, l + 15.5f * u, t + 15.5f * u, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
