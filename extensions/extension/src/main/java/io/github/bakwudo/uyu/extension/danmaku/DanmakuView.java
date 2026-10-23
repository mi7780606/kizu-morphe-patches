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
import java.util.Iterator;

import io.github.bakwudo.uyu.extension.settings.Setting;

/**
 * Niconico style danmaku. Kizu version:
 * - 普段: 空いた行が出るまで待って流す（重ならない）。同時に届いた分は少しずつずらして流す。
 * - 混雑時: 待機列を短く・少なくして、古いものから弾幕だけ間引く（チャット欄には全部出る）。
 * Must be used on the main thread.
 */
public final class DanmakuView extends View {
    private static final String SETTING_PREFIX = "danmaku_";
    private static final String TAG = "KizuDanmaku";

    // ===== 普段の動き =====
    private static final long NORMAL_MAX_WAIT_MS = 3000; // 空き行をこれだけ待つ
    private static final int NORMAL_MAX_PENDING = 150; // 待機列の上限（安全装置）
    private static final int NORMAL_PLACE_PER_FRAME = 8;
    private static final long MIN_SPACING_MS = 16;
    private static final long MAX_SPACING_MS = 250;
    private static final long MAX_SPREAD_MS = 1000; // 1回分を広げる最大時間

    // ===== 混雑モード =====
    private static final float OVERLOAD_ENTER_RATE = 30f; // 毎秒これ以上で混雑モード
    private static final float OVERLOAD_EXIT_RATE = 15f; // これ以下が続いたら解除
    private static final long OVERLOAD_HOLD_MS = 3000;
    private static final int OVERLOAD_ENTER_PENDING = 60; // 待機がこれ以上でも混雑モード
    private static final long OVERLOAD_MAX_WAIT_MS = 800;
    private static final int OVERLOAD_MAX_PENDING = 15;
    private static final int OVERLOAD_PLACE_PER_FRAME = 3;

    private final ArrayList<DanmakuComment> comments = new ArrayList<>();
    private final ArrayList<DanmakuComment> incoming = new ArrayList<>();
    private final ArrayDeque<DanmakuComment> pending = new ArrayDeque<>();
    private boolean[] rowBlocked = new boolean[0];
    private boolean flushPosted;
    private final Runnable flushRunnable = this::flushIncoming;

    // 流量の学習
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
    private final RectF imageRect = new RectF();

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> {
                if (key == null || key.startsWith(SETTING_PREFIX)) setStyle(DanmakuStyle.fromSettings());
            };

    private DanmakuStyle style;
    private float lineHeight;
    private float strokeWidth;
    private float baseline;
    private float emoteSize;
    private float gap;

    private long clock;
    private long lastTick = -1;
    private boolean paused;

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
        setStyle(DanmakuStyle.fromSettings());
    }

    @Override
    protected void onDetachedFromWindow() {
        Setting.removeChangeListener(settingsListener);
        clear();
        super.onDetachedFromWindow();
    }

    void setStyle(DanmakuStyle style) {
        this.style = style;
        setAlpha(style.opacity);
        updateMetrics();
    }

    /** 届いたコメントを受け取る。同じ回に届いた分はまとめてから待機列へ入れる。 */
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

            tick();
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

    /** 待機列が長すぎるときは古いものから弾幕だけ外す */
    private void trimPending() {
        int max = overload ? OVERLOAD_MAX_PENDING : NORMAL_MAX_PENDING;
        while (pending.size() > max) pending.pollFirst();
    }

    /** 待機列の先頭から、流せるものを流す。1コマあたりの数に上限あり。 */
    private void placePending(long now) {
        if (pending.isEmpty() || paused || style == null || getWidth() == 0) return;
        if (overload) trimPending();
        long maxWait = overload ? OVERLOAD_MAX_WAIT_MS : NORMAL_MAX_WAIT_MS;
        int limit = overload ? OVERLOAD_PLACE_PER_FRAME : NORMAL_PLACE_PER_FRAME;
        int placed = 0;
        while (!pending.isEmpty() && placed < limit) {
            DanmakuComment c = pending.peekFirst();
            if (now - c.queuedAt > maxWait) { // 待ちすぎたものは弾幕からだけ外す
                pending.pollFirst();
                continue;
            }
            if (c.due > now) break;
            if (comments.size() >= style.maxComments) break;
            layoutComment(c);
            int row = findRow(c.width);
            if (row < 0) break; // 空き行なし → 次のコマで再挑戦
            pending.pollFirst();
            c.row = row;
            c.start = clock;
            comments.add(c);
            placed++;
        }
    }

    /** 1回の走査で全行の空きを調べる。空きがなければ -1 */
    private int findRow(float width) {
        int rows = style.usableRows;
        if (rows <= 0) return -1;
        if (rowBlocked.length < rows) rowBlocked = new boolean[rows];
        for (int i = 0; i < rows; i++) rowBlocked[i] = false;
        float viewWidth = getWidth();
        for (int i = 0, size = comments.size(); i < size; i++) {
            DanmakuComment other = comments.get(i);
            int r = other.row;
            if (r < 0 || r >= rows || rowBlocked[r]) continue;
            float remaining = 1 - (clock - other.start) / (float) style.durationMs;
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
        tick();
        this.paused = paused;
        lastTick = paused ? -1 : SystemClock.uptimeMillis();
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
        comments.clear();
        incoming.clear();
        pending.clear();
        lastDue = 0;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateMetrics();
    }

    private void updateMetrics() {
        if (style == null) return;
        lineHeight = getHeight() / (float) style.rows;

        fill.setTypeface(style.typeface);
        fill.setTextSize(100);
        Paint.FontMetrics metrics = fill.getFontMetrics();
        float heightRatio = (metrics.descent - metrics.ascent) / 100f;
        float textSize = lineHeight / (heightRatio + style.outlineRatio);

        strokeWidth = textSize * style.outlineRatio;
        baseline = strokeWidth / 2 - metrics.ascent / 100f * textSize;
        emoteSize = lineHeight - strokeWidth;
        gap = lineHeight * 0.2f;

        fill.setTextSize(textSize);
        fill.setColor(style.textColor);
        stroke.setTypeface(style.typeface);
        stroke.setTextSize(textSize);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(style.outlineColor);

        for (DanmakuComment comment : comments) layoutComment(comment);
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
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        if (!paused && lastTick >= 0) clock += now - lastTick;
        lastTick = paused ? -1 : now;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        tick();

        float viewWidth = getWidth();
        // 先に流し終わったものを外してから、新しいものを入れる
        Iterator<DanmakuComment> it = comments.iterator();
        while (it.hasNext()) {
            DanmakuComment c = it.next();
            if ((clock - c.start) / (float) style.durationMs >= 1) it.remove();
        }
        try {
            placePending(SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Log.e(TAG, "place failed", t);
            pending.clear();
        }

        boolean drawOutline = strokeWidth > 0 && Color.alpha(style.outlineColor) > 0;
        for (int n = 0, size = comments.size(); n < size; n++) {
            DanmakuComment comment = comments.get(n);
            float progress = (clock - comment.start) / (float) style.durationMs;
            float x = viewWidth - progress * (viewWidth + comment.width) + strokeWidth / 2;
            float top = comment.row * lineHeight;
            for (int i = 0; i < comment.parts.length; i++) {
                Object part = comment.parts[i];
                float partWidth = comment.partWidths[i];
                if (x + partWidth >= 0 && x <= viewWidth) {
                    if (part instanceof String text) {
                        if (drawOutline) canvas.drawText(text, x, top + baseline, stroke);
                        canvas.drawText(text, x, top + baseline, fill);
                    } else if (part instanceof DanmakuComment.Emote emote) {
                        drawEmote(canvas, emote, x, top + strokeWidth / 2);
                    }
                }
                x += partWidth;
            }
        }

        if ((!comments.isEmpty() || !pending.isEmpty()) && !paused) postInvalidateOnAnimation();
    }

    private void drawEmote(Canvas canvas, DanmakuComment.Emote emote, float left, float top) {
        Bitmap bitmap = EmoteImages.get(emote.id, emoteSize);
        if (bitmap == null) return;
        float scale = emoteSize / Math.max(bitmap.getWidth(), bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float x = left + (emoteSize - width) / 2;
        float y = top + (emoteSize - height) / 2;
        imageRect.set(x, y, x + width, y + height);
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint);
    }
}
