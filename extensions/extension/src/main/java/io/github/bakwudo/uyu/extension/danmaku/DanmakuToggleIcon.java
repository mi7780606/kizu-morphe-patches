package io.github.bakwudo.uyu.extension.danmaku;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A speech bubble with two lines of text, crossed out while danmaku is off. Drawn in code so
 * no resources are added to the app.
 */
final class DanmakuToggleIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF corner = new RectF();
    private final int intrinsicSize;
    private ColorStateList tint = ColorStateList.valueOf(0xFFFFFFFF);
    private int alpha = 255;
    private boolean on;
    private float cachedLeft = Float.NaN;
    private float cachedTop = Float.NaN;
    private float cachedSize = Float.NaN;

    DanmakuToggleIcon(int intrinsicSize) {
        this.intrinsicSize = intrinsicSize;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setOn(boolean on) {
        if (this.on == on) return;
        this.on = on;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        if (size <= 0) return;
        float left = bounds.exactCenterX() - size / 2f;
        float top = bounds.exactCenterY() - size / 2f;
        float unit = size / 24f;

        if (left != cachedLeft || top != cachedTop || size != cachedSize) {
            cachedLeft = left;
            cachedTop = top;
            cachedSize = size;
            rebuildBubblePath(left, top, unit);
        }

        int color = tint.getColorForState(getState(), tint.getDefaultColor());
        int fullAlpha = Color.alpha(color) * alpha / 255;
        paint.setColor(color);
        paint.setAlpha(on ? fullAlpha : Math.round(fullAlpha * 0.55f));
        paint.setStrokeWidth(2 * unit);

        canvas.drawPath(path, paint);
        canvas.drawLine(left + 7 * unit, top + 9 * unit, left + 17 * unit, top + 9 * unit, paint);
        canvas.drawLine(left + 7 * unit, top + 12.5f * unit, left + 14 * unit, top + 12.5f * unit, paint);

        if (!on) {
            paint.setAlpha(fullAlpha);
            canvas.drawLine(left + 3 * unit, top + 3 * unit, left + 21 * unit, top + 21 * unit, paint);
        }
    }

    private void rebuildBubblePath(float left, float top, float unit) {
        path.reset();
        path.moveTo(left + 11.5f * unit, top + 17 * unit);
        path.lineTo(left + 18 * unit, top + 17 * unit);
        corner.set(left + 15 * unit, top + 11 * unit, left + 21 * unit, top + 17 * unit);
        path.arcTo(corner, 90, -90, false);
        path.lineTo(left + 21 * unit, top + 7 * unit);
        corner.set(left + 15 * unit, top + 4 * unit, left + 21 * unit, top + 10 * unit);
        path.arcTo(corner, 0, -90, false);
        path.lineTo(left + 6 * unit, top + 4 * unit);
        corner.set(left + 3 * unit, top + 4 * unit, left + 9 * unit, top + 10 * unit);
        path.arcTo(corner, 270, -90, false);
        path.lineTo(left + 3 * unit, top + 14 * unit);
        corner.set(left + 3 * unit, top + 11 * unit, left + 9 * unit, top + 17 * unit);
        path.arcTo(corner, 180, -90, false);
        path.lineTo(left + 7 * unit, top + 17 * unit);
        path.lineTo(left + 7 * unit, top + 20.8f * unit);
        path.close();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        this.tint = tint == null ? ColorStateList.valueOf(0xFFFFFFFF) : tint;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return tint.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return tint.isStateful();
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicSize;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
