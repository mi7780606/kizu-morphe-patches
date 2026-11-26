package app.morphe.extension.multistream;

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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.settings.TwitchUi;

/**
 * Fallback dark cover shown on an individual Mode A PiP or Mode B slot when a Twitch stitched ad
 * slips through backup stream swapping. Modeled after {@code AdBlockOverlay}, with responsive
 * compact sizing for small PiP / multi-view tiles and pass-through touch events.
 */
final class MultiStreamAdCoverView extends FrameLayout {
    private static final int BG_COLOR = 0xFF0E0E10;
    private static final int CARD_COLOR = 0xFF18181B;
    private static final int BORDER_COLOR = 0xFF2F2F35;
    private static final int TEXT_PRIMARY = 0xFFEFEFF1;
    private static final int TEXT_SECONDARY = 0xFFADADB8;
    private static final int ACCENT = 0xFFBF94FF;
    private static final int ACCENT_BAR = 0xFF9147FF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final float density;

    private final LinearLayout card;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView remainingView;
    private final FrameLayout progressTrack;
    private final View progressFill;

    private int progressTrackWidth;
    private long startTimeMs;
    private long endTimeMs;
    private long lastDisplayedSec = -2L;
    private boolean active;

    MultiStreamAdCoverView(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(BG_COLOR);
        setClickable(false);
        setFocusable(false);
        setVisibility(View.GONE);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setBackground(TwitchUi.roundedRect(CARD_COLOR, 12f * density, Math.max(1, dp(1)), BORDER_COLOR));

        iconView = new ImageView(context);
        int iconSize = dp(22);
        iconView.setImageDrawable(new ShieldDrawable(ACCENT, iconSize));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.bottomMargin = dp(6);
        card.addView(iconView, iconLp);

        titleView = new TextView(context);
        titleView.setText("広告をブロック中");
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView);

        remainingView = new TextView(context);
        remainingView.setTextColor(TEXT_SECONDARY);
        remainingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        remainingView.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(-2, -2);
        remLp.topMargin = dp(3);
        card.addView(remainingView, remLp);

        progressTrackWidth = dp(116);
        progressTrack = new FrameLayout(context);
        progressTrack.setBackground(TwitchUi.roundedRect(BORDER_COLOR, 2f * density, 0, 0));
        progressFill = new View(context);
        progressFill.setBackground(TwitchUi.roundedRect(ACCENT_BAR, 2f * density, 0, 0));
        progressTrack.addView(progressFill, new LayoutParams(progressTrackWidth, LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(progressTrackWidth, dp(3));
        barLp.topMargin = dp(8);
        card.addView(progressTrack, barLp);

        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    void showAdCover(float remainingSeconds) {
        long now = SystemClock.uptimeMillis();
        long durMs = Math.max(3000L, (long) (Math.max(1f, remainingSeconds) * 1000f));
        if (!active) {
            active = true;
            startTimeMs = now;
            endTimeMs = now + durMs;
        } else {
            endTimeMs = Math.max(endTimeMs, now + durMs);
        }
        lastDisplayedSec = -2L;
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
        }
        tick();
    }

    void hideAdCover() {
        active = false;
        main.removeCallbacks(tickRunnable);
        if (getVisibility() != View.GONE) {
            setVisibility(View.GONE);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (h <= 0 || w <= 0) return;
        float hDp = h / Math.max(1f, density);
        boolean tiny = hDp < 92f;
        boolean compact = hDp < 140f;

        int padH = dp(tiny ? 10 : (compact ? 14 : 20));
        int padV = dp(tiny ? 6 : (compact ? 10 : 14));
        card.setPadding(padH, padV, padH, padV);

        int iconSize = dp(tiny ? 16 : (compact ? 20 : 24));
        LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) iconView.getLayoutParams();
        if (iconLp.width != iconSize || iconLp.height != iconSize) {
            iconLp.width = iconSize;
            iconLp.height = iconSize;
            iconLp.bottomMargin = dp(tiny ? 3 : 6);
            iconView.setLayoutParams(iconLp);
            iconView.setImageDrawable(new ShieldDrawable(ACCENT, iconSize));
        }

        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 11f : (compact ? 12.5f : 14f));
        remainingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 10f : (compact ? 11f : 12f));

        progressTrack.setVisibility(tiny ? View.GONE : View.VISIBLE);
        int desiredTrackW = Math.min(dp(compact ? 104 : 132), Math.max(dp(64), w - dp(48)));
        if (desiredTrackW != progressTrackWidth) {
            progressTrackWidth = desiredTrackW;
            LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) progressTrack.getLayoutParams();
            barLp.width = progressTrackWidth;
            progressTrack.setLayoutParams(barLp);
            updateProgressFill(SystemClock.uptimeMillis());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(tickRunnable);
        super.onDetachedFromWindow();
    }

    private void tick() {
        main.removeCallbacks(tickRunnable);
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        long left = endTimeMs - now;
        long seconds = left > 0L ? (left + 999L) / 1000L : 0L;
        if (seconds != lastDisplayedSec) {
            lastDisplayedSec = seconds;
            remainingView.setText(seconds > 0L ? ("あと " + seconds + " 秒で再開") : "まもなく再開");
            updateProgressFill(now);
        }
        long nextDelay = seconds > 0L
                ? Math.max(120L, Math.min(1000L, left - (seconds - 1L) * 1000L + 20L))
                : 500L;
        main.postDelayed(tickRunnable, nextDelay);
    }

    private void updateProgressFill(long now) {
        if (progressTrackWidth <= 0) return;
        long total = Math.max(1000L, endTimeMs - startTimeMs);
        long left = Math.max(0L, endTimeMs - now);
        float ratio = Math.max(0f, Math.min(1f, left / (float) total));
        int fillW = Math.round(progressTrackWidth * ratio);
        ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
        if (lp != null && lp.width != fillW) {
            lp.width = fillW;
            progressFill.setLayoutParams(lp);
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
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
            paint.setStrokeWidth(Math.max(1.4f, 1.9f * u));

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
