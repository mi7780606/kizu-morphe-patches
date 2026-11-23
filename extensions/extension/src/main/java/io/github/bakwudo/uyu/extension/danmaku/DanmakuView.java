package io.github.bakwudo.uyu.extension.danmaku;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;

import io.github.bakwudo.uyu.extension.settings.Setting;

/**
 * Niconico style danmaku. Kizu version:
 * - Sprite-cached comment bitmaps eliminate per-frame CJK stroke rasterization and View.setAlpha()
 *   full-screen offscreen FBO layers.
 * - Zero-allocation onDraw loop with smart idle sleep when waiting on queued comments.
 */
public final class DanmakuView extends View {
    private static final String SETTING_PREFIX = "danmaku_";
    private static final String TAG = "KizuDanmaku";

    private static final long NORMAL_MAX_WAIT_MS = 3000;
    private static final int NORMAL_MAX_PENDING = 150;
    private static final int NORMAL_PLACE_PER_FRAME = 8;
    private static final long MIN_SPACING_MS = 16;
    private static final long MAX_SPACING_MS = 250;
    private static final long MAX_SPREAD_MS = 1000;

    private static final float OVERLOAD_ENTER_RATE = 30f;
    private static final float OVERLOAD_EXIT_RATE = 15f;
    private static final long OVERLOAD_HOLD_MS = 3000;
    private static final int OVERLOAD_ENTER_PENDING = 60;
    private static final long OVERLOAD_MAX_WAIT_MS = 800;
    private static final int OVERLOAD_MAX_PENDING = 15;
    private static final int OVERLOAD_PLACE_PER_FRAME = 3;

    private static final int MAX_SPRITE_CACHE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SPRITE_WIDTH = 2048;
    private static final int MAX_SPRITE_HEIGHT = 256;

    private final ArrayList<DanmakuComment> comments = new ArrayList<>();
    private final ArrayList<DanmakuComment> incoming = new ArrayList<>();
    private final ArrayDeque<DanmakuComment> pending = new ArrayDeque<>();
    private boolean[] rowBlocked = new boolean[0];
    private boolean flushPosted;
    private boolean delayedWakePosted;
    private final Runnable flushRunnable = this::flushIncoming;
    private final Runnable wakeRunnable = () -> {
        delayedWakePosted = false;
        if (!paused && windowVisible) postInvalidateOnAnimation();
    };

    private long lastBatchAt;
    private float avgGapMs = 500f;
    private long lastDue;
    private float rate;
    private long windowStart;
    private int windowCount;
    private boolean overload;
    private long overloadUntil;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Canvas spriteCanvas = new Canvas();
    private final RectF imageRect = new RectF();

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> {
                if (key == null || key.startsWith(SETTING_PREFIX)) setStyle(DanmakuStyle.fromSettings());
            };

    private DanmakuStyle style;
    private int styleGen = 1;
    private int spriteCacheBytes;
    private int opacityAlpha = 255;
    private float lineHeight;
    private float strokeWidth;
    private float baseline;
    private float emoteSize;
    private int emoteTier;
    private float gap;

    private long clock;
    private long lastTick = -1;
    private boolean paused;
    private boolean windowVisible = true;

    public DanmakuView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setStyle(DanmakuStyle.fromSettings());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Setting.addChangeListener(settingsListener);
        windowVisible = getWindowVisibility() == View.VISIBLE;
        setStyle(DanmakuStyle.fromSettings());
    }

    @Override
    protected void onDetachedFromWindow() {
        Setting.removeChangeListener(settingsListener);
        removeCallbacks(flushRunnable);
        removeCallbacks(wakeRunnable);
        flushPosted = false;
        delayedWakePosted = false;
        clear();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == View.VISIBLE;
        if (windowVisible && !paused && (!comments.isEmpty() || !pending.isEmpty())) {
            lastTick = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    void setStyle(DanmakuStyle nextStyle) {
        DanmakuStyle prev = this.style;
        this.style = nextStyle;
        this.opacityAlpha = Math.max(0, Math.min(255, Math.round(nextStyle.opacity * 255f)));
        bitmapPaint.setAlpha(opacityAlpha);
        if (prev == null || !prev.metricsEqual(nextStyle)) {
            updateMetrics();
        } else {
            invalidate();
        }
    }

    public void addComment(DanmakuComment comment) {
        if (paused || getWidth() == 0 || getHeight() == 0 || comment == null) return;
        incoming.add(comment);
        if (!flushPosted) {
            flushPosted = true;
            post(flushRunnable);
        }
    }

    private void flushIncoming() {
        flushPosted = false;
        if (incoming.isEmpty()) return;
        if (paused || style == null) {
            incoming.clear();
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            int n = incoming.size();
            updateRate(now, n);
            updateOverload(now);
            if (!overload) learnGap(now);

            long spacing = overload ? 0
                    : Math.max(MIN_SPACING_MS, Math.min(MAX_SPACING_MS, (long) (avgGapMs / n)));
            boolean immediate = pending.isEmpty() && lastDue <= now;
            long base = immediate ? now : Math.max(now, lastDue + spacing);
            long limit = now + MAX_SPREAD_MS;
            for (int i = 0; i < n; i++) {
                DanmakuComment c = incoming.get(i);
                c.due = Math.min(base + spacing * i, limit);
                c.queuedAt = now;
                pending.addLast(c);
                lastDue = c.due;
            }
            incoming.clear();

            if (!overload && pending.size() >= OVERLOAD_ENTER_PENDING) enterOverload(now);
            trimPending();

            tick(now);
            placePending(now);
            postInvalidateOnAnimation();
        } catch (Throwable t) {
            Log.e(TAG, "flush failed", t);
            incoming.clear();
            pending.clear();
        }
    }

    private void updateRate(long now, int count) {
        if (windowStart == 0 || now - windowStart > 2000) {
            if (windowStart != 0) rate = 0f;
            windowStart = now;
            windowCount = count;
            return;
        }
        windowCount += count;
        long span = now - windowStart;
        if (span >= 500) {
            float inst = windowCount * 1000f / span;
            rate = rate * 0.5f + inst * 0.5f;
            windowStart = now;
            windowCount = 0;
        }
    }

    private void updateOverload(long now) {
        if (overload) {
            if (rate > OVERLOAD_EXIT_RATE) {
                overloadUntil = now + OVERLOAD_HOLD_MS;
            } else if (now >= overloadUntil) {
                overload = false;
                Log.i(TAG, "overload off");
            }
        } else if (rate >= OVERLOAD_ENTER_RATE) {
            enterOverload(now);
        }
    }

    private void enterOverload(long now) {
        if (!overload) Log.i(TAG, "overload on (rate=" + rate + ", pending=" + pending.size() + ")");
        overload = true;
        overloadUntil = now + OVERLOAD_HOLD_MS;
    }

    private void learnGap(long now) {
        if (lastBatchAt != 0) {
            long g = now - lastBatchAt;
            if (g < 3000) avgGapMs = avgGapMs * 0.7f + Math.max(50f, Math.min(2000f, g)) * 0.3f;
        }
        lastBatchAt = now;
    }

    private void trimPending() {
        int max = overload ? OVERLOAD_MAX_PENDING : NORMAL_MAX_PENDING;
        while (pending.size() > max) pending.pollFirst();
    }

    private void placePending(long now) {
        if (pending.isEmpty() || paused || style == null || getWidth() == 0) return;
        if (overload) trimPending();
        long maxWait = overload ? OVERLOAD_MAX_WAIT_MS : NORMAL_MAX_WAIT_MS;
        int limit = overload ? OVERLOAD_PLACE_PER_FRAME : NORMAL_PLACE_PER_FRAME;
        int placed = 0;
        while (!pending.isEmpty() && placed < limit) {
            DanmakuComment c = pending.peekFirst();
            if (now - c.queuedAt > maxWait) {
                pending.pollFirst();
                continue;
            }
            if (c.due > now) break;
            if (comments.size() >= style.maxComments) break;
            if (c.measuredGen != styleGen) layoutComment(c);
            int row = findRow(c.width);
            if (row < 0) break;
            pending.pollFirst();
            c.row = row;
            c.start = clock;
            comments.add(c);
            placed++;
        }
    }

    private int findRow(float width) {
        int rows = style.usableRows;
        if (rows <= 0) return -1;
        if (rowBlocked.length < rows) rowBlocked = new boolean[rows];
        for (int i = 0; i < rows; i++) rowBlocked[i] = false;
        float viewWidth = getWidth();
        float invDur = style.invDuration;
        for (int i = 0, size = comments.size(); i < size; i++) {
            DanmakuComment other = comments.get(i);
            int r = other.row;
            if (r < 0 || r >= rows || rowBlocked[r]) continue;
            float remaining = 1f - (clock - other.start) * invDur;
            if (remaining * (viewWidth + other.width) + gap > viewWidth
                    || remaining * (viewWidth + width) + gap > viewWidth) {
                rowBlocked[r] = true;
            }
        }
        for (int r = 0; r < rows; r++) if (!rowBlocked[r]) return r;
        return -1;
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        long now = SystemClock.uptimeMillis();
        tick(now);
        this.paused = paused;
        lastTick = paused ? -1 : now;
        if (paused) {
            incoming.clear();
            pending.clear();
            lastDue = 0;
        }
        invalidate();
    }

    public boolean isPaused() {
        return paused;
    }

    int getCommentCount() {
        return comments.size();
    }

    DanmakuStyle getStyle() {
        return style;
    }

    public void clear() {
        boolean hadData = !comments.isEmpty() || !incoming.isEmpty() || !pending.isEmpty() || lastDue != 0;
        for (int i = 0, n = comments.size(); i < n; i++) {
            releaseSprite(comments.get(i));
        }
        comments.clear();
        incoming.clear();
        pending.clear();
        lastDue = 0;
        if (hadData) invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (height != oldHeight || lineHeight <= 0f) {
            updateMetrics();
        }
    }

    private void updateMetrics() {
        if (style == null) return;
        styleGen++;
        lineHeight = getHeight() / (float) style.rows;

        fill.setTypeface(style.typeface);
        fill.setTextSize(100);
        Paint.FontMetrics metrics = fill.getFontMetrics();
        float heightRatio = (metrics.descent - metrics.ascent) / 100f;
        float textSize = lineHeight / (heightRatio + style.outlineRatio);

        strokeWidth = textSize * style.outlineRatio;
        baseline = strokeWidth / 2f - metrics.ascent / 100f * textSize;
        emoteSize = lineHeight - strokeWidth;
        emoteTier = EmoteImages.tierForSize(emoteSize);
        gap = lineHeight * 0.2f;

        fill.setTextSize(textSize);
        fill.setColor(style.textColor);
        stroke.setTypeface(style.typeface);
        stroke.setTextSize(textSize);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(style.outlineColor);

        for (int i = 0, n = comments.size(); i < n; i++) {
            DanmakuComment c = comments.get(i);
            releaseSprite(c);
            layoutComment(c);
        }
        invalidate();
    }

    private void layoutComment(DanmakuComment comment) {
        float width = strokeWidth;
        for (int i = 0; i < comment.parts.length; i++) {
            Object part = comment.parts[i];
            float partWidth = part instanceof String text ? fill.measureText(text) : emoteSize;
            comment.partWidths[i] = partWidth;
            width += partWidth;
        }
        comment.width = width;
        comment.measuredGen = styleGen;
    }

    private void tick(long now) {
        if (!paused && lastTick >= 0) clock += now - lastTick;
        lastTick = paused ? -1 : now;
    }

    private void releaseSprite(DanmakuComment c) {
        if (c.sprite != null) {
            spriteCacheBytes = Math.max(0, spriteCacheBytes - c.spriteBytes);
            c.sprite = null;
        }
        c.spriteBytes = 0;
        c.spriteGen = 0;
        c.spriteComplete = false;
    }

    private boolean hasAllEmotesReady(DanmakuComment comment) {
        for (int i = 0; i < comment.parts.length; i++) {
            if (comment.parts[i] instanceof DanmakuComment.Emote emote) {
                if (EmoteImages.get(emote, emoteTier) == null) return false;
            }
        }
        return true;
    }

    private void ensureSprite(DanmakuComment comment, boolean drawOutline) {
        if (comment.sprite != null && comment.spriteGen == styleGen) {
            if (comment.spriteComplete) return;
            if (!hasAllEmotesReady(comment)) return;
            // Missing emote finished loading; re-render once into existing bitmap.
            renderIntoSprite(comment, comment.sprite, drawOutline);
            return;
        }
        releaseSprite(comment);

        int w = (int) Math.ceil(comment.width + 2f);
        int h = (int) Math.ceil(lineHeight + 2f);
        if (w <= 0 || h <= 0 || w > MAX_SPRITE_WIDTH || h > MAX_SPRITE_HEIGHT) return;
        int bytes = w * h * 4;
        if (spriteCacheBytes + bytes > MAX_SPRITE_CACHE_BYTES) return;

        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError ignored) {
            return;
        }
        spriteCacheBytes += bytes;
        comment.sprite = bmp;
        comment.spriteBytes = bytes;
        comment.spriteGen = styleGen;
        renderIntoSprite(comment, bmp, drawOutline);
    }

    private void renderIntoSprite(DanmakuComment comment, Bitmap bmp, boolean drawOutline) {
        bmp.eraseColor(Color.TRANSPARENT);
        spriteCanvas.setBitmap(bmp);

        // Keep full color alpha inside sprite; opacity is applied once when blitting via bitmapPaint.
        fill.setColor(style.textColor);
        stroke.setColor(style.outlineColor);
        imagePaint.setAlpha(255);

        float x = strokeWidth / 2f;
        boolean complete = true;
        for (int i = 0; i < comment.parts.length; i++) {
            Object part = comment.parts[i];
            float partWidth = comment.partWidths[i];
            if (part instanceof String text) {
                if (drawOutline) spriteCanvas.drawText(text, x, baseline, stroke);
                spriteCanvas.drawText(text, x, baseline, fill);
            } else if (part instanceof DanmakuComment.Emote emote) {
                Bitmap eb = EmoteImages.get(emote, emoteTier);
                if (eb == null) {
                    complete = false;
                } else {
                    drawEmoteBitmap(spriteCanvas, eb, x, strokeWidth / 2f);
                }
            }
            x += partWidth;
        }
        spriteCanvas.setBitmap(null);
        comment.spriteComplete = complete;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (style == null || getWidth() == 0 || getHeight() == 0) return;

        long now = SystemClock.uptimeMillis();
        tick(now);

        float viewWidth = getWidth();
        float invDur = style.invDuration;
        int write = 0;
        for (int read = 0, size = comments.size(); read < size; read++) {
            DanmakuComment c = comments.get(read);
            if ((clock - c.start) * invDur >= 1f) {
                releaseSprite(c);
            } else {
                comments.set(write++, c);
            }
        }
        if (write < comments.size()) {
            comments.subList(write, comments.size()).clear();
        }

        try {
            placePending(now);
        } catch (Throwable t) {
            Log.e(TAG, "place failed", t);
            pending.clear();
        }

        boolean drawOutline = strokeWidth > 0 && Color.alpha(style.outlineColor) > 0;
        for (int n = 0, size = comments.size(); n < size; n++) {
            DanmakuComment comment = comments.get(n);
            float progress = (clock - comment.start) * invDur;
            float baseX = viewWidth - progress * (viewWidth + comment.width);
            float top = comment.row * lineHeight;

            ensureSprite(comment, drawOutline);
            if (comment.sprite != null) {
                canvas.drawBitmap(comment.sprite, baseX, top, bitmapPaint);
                continue;
            }

            // Direct draw fallback for ultra-wide comments exceeding MAX_SPRITE_WIDTH or cache budget.
            fill.setColor(scaleAlpha(style.textColor, opacityAlpha));
            if (drawOutline) stroke.setColor(scaleAlpha(style.outlineColor, opacityAlpha));
            imagePaint.setAlpha(opacityAlpha);

            float x = baseX + strokeWidth / 2f;
            for (int i = 0; i < comment.parts.length; i++) {
                Object part = comment.parts[i];
                float partWidth = comment.partWidths[i];
                if (x + partWidth >= 0 && x <= viewWidth) {
                    if (part instanceof String text) {
                        if (drawOutline) canvas.drawText(text, x, top + baseline, stroke);
                        canvas.drawText(text, x, top + baseline, fill);
                    } else if (part instanceof DanmakuComment.Emote emote) {
                        Bitmap eb = EmoteImages.get(emote, emoteTier);
                        if (eb != null) drawEmoteBitmap(canvas, eb, x, top + strokeWidth / 2f);
                    }
                }
                x += partWidth;
            }
        }

        if (!paused && windowVisible) {
            if (!comments.isEmpty()) {
                postInvalidateOnAnimation();
            } else if (!pending.isEmpty()) {
                DanmakuComment head = pending.peekFirst();
                long wait = head == null ? 0L : head.due - now;
                if (wait <= 16L) {
                    postInvalidateOnAnimation();
                } else if (!delayedWakePosted) {
                    delayedWakePosted = true;
                    postOnAnimationDelayed(wakeRunnable, Math.min(wait, 1000L));
                }
            }
        }
    }

    private static int scaleAlpha(int color, int opacityAlpha) {
        if (opacityAlpha >= 255) return color;
        int a = (Color.alpha(color) * opacityAlpha) / 255;
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void drawEmoteBitmap(Canvas canvas, Bitmap bitmap, float left, float top) {
        float scale = emoteSize / Math.max(bitmap.getWidth(), bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float x = left + (emoteSize - width) / 2f;
        float y = top + (emoteSize - height) / 2f;
        imageRect.set(x, y, x + width, y + height);
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint);
    }
}
