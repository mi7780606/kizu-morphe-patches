package app.morphe.extension.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Compact, hardware-accelerated capsule HUD pill shown near the top of the player while adjusting
 * brightness or volume by vertical swipe.
 */
final class GestureOsdView extends View {
    private static final long HIDE_DELAY_MS = 500L;
    private static final long SHOW_DURATION_MS = 120L;
    private static final long FADE_DURATION_MS = 280L;

    private static final int COLOR_BG = 0xE018181B;
    private static final int COLOR_BORDER = 0x33FFFFFF;
    private static final int COLOR_TRACK = 0x40FFFFFF;
    private static final int COLOR_ACCENT = 0xFF9147FF;
    private static final int COLOR_TEXT = 0xFFEFEFF1;
    private static final int COLOR_MUTED = 0xFFADADB8;

    private static final float[] SUN_COS = new float[8];
    private static final float[] SUN_SIN = new float[8];
    static {
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45.0);
            SUN_COS[i] = (float) Math.cos(rad);
            SUN_SIN[i] = (float) Math.sin(rad);
        }
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF pillRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final RectF arcRect = new RectF();
    private final Path speakerPath = new Path();

    private final float density;
    private final int pillWidthPx;
    private final int pillHeightPx;
    private final int sidePadPx;
    private final int iconSizePx;
    private final int iconGapPx;
    private final int textGapPx;
    private final int labelWidthPx;
    private final int barHeightPx;
    private final int minTopPx;

    private int value;
    private int max = 100;
    private boolean brightness = true;
    private String label = "0%";
    private boolean showing;

    private final Runnable hideRunnable = () -> {
        showing = false;
        animate().cancel();
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (!showing) {
                        setVisibility(View.INVISIBLE);
                        setAlpha(1f);
                        setScaleX(1f);
                        setScaleY(1f);
                    }
                }).start();
    };

    GestureOsdView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;

        pillWidthPx = Math.round(184f * density);
        pillHeightPx = Math.round(40f * density);
        sidePadPx = Math.round(14f * density);
        iconSizePx = Math.round(18f * density);
        iconGapPx = Math.round(10f * density);
        textGapPx = Math.round(8f * density);
        labelWidthPx = Math.round(36f * density);
        barHeightPx = Math.max(2, Math.round(5f * density));
        minTopPx = Math.round(20f * density);

        bgPaint.setColor(COLOR_BG);
        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, density));

        trackPaint.setColor(COLOR_TRACK);
        fillPaint.setColor(COLOR_ACCENT);

        iconFillPaint.setColor(COLOR_TEXT);
        iconFillPaint.setStyle(Paint.Style.FILL);
        iconStrokePaint.setColor(COLOR_TEXT);
        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(12.5f * scaledDensity);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setFontFeatureSettings("tnum");

        setVisibility(View.INVISIBLE);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(int value, int max, boolean brightness) {
        int safeMax = Math.max(1, max);
        int clamped = Math.max(0, Math.min(safeMax, value));
        boolean changed = this.value != clamped || this.max != safeMax || this.brightness != brightness;
        if (changed) {
            this.value = clamped;
            this.max = safeMax;
            this.brightness = brightness;
            this.label = brightness ? (clamped + "%") : Integer.toString(clamped);
            invalidate();
        }
        removeCallbacks(hideRunnable);
        if (!showing) {
            showing = true;
            animate().cancel();
            if (getVisibility() != View.VISIBLE) {
                setAlpha(0f);
                setScaleX(0.92f);
                setScaleY(0.92f);
                setVisibility(View.VISIBLE);
            }
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SHOW_DURATION_MS).start();
        }
        postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    void scheduleHide() {
        removeCallbacks(hideRunnable);
        if (showing || getVisibility() == View.VISIBLE) {
            postDelayed(hideRunnable, HIDE_DELAY_MS);
        }
    }

    void hideImmediately() {
        showing = false;
        removeCallbacks(hideRunnable);
        animate().cancel();
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pillW = Math.min(pillWidthPx, Math.max(120f * density, w - 32f * density));
        float pillH = pillHeightPx;
        float left = (w - pillW) * 0.5f;
        float top = Math.max(minTopPx, h * 0.14f);
        if (top + pillH > h - 8f * density) {
            top = Math.max(0f, (h - pillH) * 0.5f);
        }
        float radius = pillH * 0.5f;
        float cy = top + radius;

        pillRect.set(left, top, left + pillW, top + pillH);
        canvas.drawRoundRect(pillRect, radius, radius, bgPaint);
        canvas.drawRoundRect(pillRect, radius, radius, borderPaint);

        boolean muted = !brightness && value == 0;
        int fgColor = muted ? COLOR_MUTED : COLOR_TEXT;
        iconFillPaint.setColor(fgColor);
        iconStrokePaint.setColor(fgColor);
        textPaint.setColor(fgColor);
        fillPaint.setColor(muted ? COLOR_MUTED : COLOR_ACCENT);

        float iconLeft = left + sidePadPx;
        float iconTop = cy - iconSizePx * 0.5f;
        drawIcon(canvas, iconLeft, iconTop, iconSizePx);

        float barLeft = iconLeft + iconSizePx + iconGapPx;
        float barRight = left + pillW - sidePadPx - labelWidthPx - textGapPx;
        if (barRight > barLeft + 8f * density) {
            float barRadius = barHeightPx * 0.5f;
            float barTop = cy - barRadius;
            float barBottom = cy + barRadius;
            trackRect.set(barLeft, barTop, barRight, barBottom);
            canvas.drawRoundRect(trackRect, barRadius, barRadius, trackPaint);

            float fraction = value / (float) max;
            if (fraction > 0f) {
                float fillW = Math.max(barHeightPx, (barRight - barLeft) * fraction);
                fillRect.set(barLeft, barTop, Math.min(barRight, barLeft + fillW), barBottom);
                canvas.drawRoundRect(fillRect, barRadius, barRadius, fillPaint);
            }
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, left + pillW - sidePadPx, textY, textPaint);
    }

    private void drawIcon(Canvas canvas, float left, float top, float size) {
        float unit = size / 24f;
        float cx = left + 12f * unit;
        float cy = top + 12f * unit;
        iconStrokePaint.setStrokeWidth(1.8f * unit);

        if (brightness) {
            canvas.drawCircle(cx, cy, 4f * unit, iconStrokePaint);
            float r1 = 6.9f * unit;
            float r2 = 9.6f * unit;
            for (int i = 0; i < 8; i++) {
                float dx = SUN_COS[i];
                float dy = SUN_SIN[i];
                canvas.drawLine(cx + dx * r1, cy + dy * r1, cx + dx * r2, cy + dy * r2, iconStrokePaint);
            }
            return;
        }

        speakerPath.reset();
        speakerPath.moveTo(left + 3f * unit, top + 9.2f * unit);
        speakerPath.lineTo(left + 7f * unit, top + 9.2f * unit);
        speakerPath.lineTo(left + 12f * unit, top + 5.2f * unit);
        speakerPath.lineTo(left + 12f * unit, top + 18.8f * unit);
        speakerPath.lineTo(left + 7f * unit, top + 14.8f * unit);
        speakerPath.lineTo(left + 3f * unit, top + 14.8f * unit);
        speakerPath.close();
        canvas.drawPath(speakerPath, iconFillPaint);

        if (value == 0) {
            canvas.drawLine(left + 15.5f * unit, top + 9.5f * unit,
                    left + 20.5f * unit, top + 14.5f * unit, iconStrokePaint);
            canvas.drawLine(left + 20.5f * unit, top + 9.5f * unit,
                    left + 15.5f * unit, top + 14.5f * unit, iconStrokePaint);
        } else {
            float sx = left + 12f * unit;
            float rInner = 3.6f * unit;
            arcRect.set(sx - rInner, cy - rInner, sx + rInner, cy + rInner);
            canvas.drawArc(arcRect, -50f, 100f, false, iconStrokePaint);

            if (value * 2 >= max) {
                float rOuter = 6.8f * unit;
                arcRect.set(sx - rOuter, cy - rOuter, sx + rOuter, cy + rOuter);
                canvas.drawArc(arcRect, -55f, 110f, false, iconStrokePaint);
            }
        }
    }
}
