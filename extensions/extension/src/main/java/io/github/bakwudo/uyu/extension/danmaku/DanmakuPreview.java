package io.github.bakwudo.uyu.extension.danmaku;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Random;

import app.morphe.extension.settings.SettingsUi;

/**
 * A live preview for the settings screen: sample comments flowing over a dark frame with the
 * aspect ratio of the screen in landscape, so the preview is a scaled-down fullscreen player.
 * Comments arrive a little faster than they leave, so the preview always shows the maximum
 * number of comments.
 */
public final class DanmakuPreview extends FrameLayout {
    private static final long MIN_INTERVAL_MS = 50;
    private static final long MAX_INTERVAL_MS = 1000;
    /** How much faster comments arrive than they leave. */
    private static final float ARRIVAL_RATE = 1.25f;
    /** Largest share of the screen height the preview takes, so the settings stay visible. */
    private static final float MAX_HEIGHT_FRACTION = 0.4f;

    /** Twitch's global emote "Kappa". */
    private static final String KAPPA = "25";

    private static final Object[][] SAMPLES = {
            {"wwwwwwwwww"},
            {"888888888888"},
            {"GG"},
            {"Nice play!"},
            {"今の上手すぎる"},
            {"lol"},
            {new DanmakuComment.Emote(KAPPA)},
            {"おつかれさまでした！"},
            {"That was close ", new DanmakuComment.Emote(KAPPA)},
            {"えぐい"},
            {"Longer comments move faster, so every comment is on screen for the same time"},
            {"草"},
    };

    private final DanmakuView danmaku;
    private final float aspectRatio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean running;
    private final Runnable addSamples = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            DanmakuStyle style = danmaku.getStyle();
            if (danmaku.getCommentCount() < style.maxComments) {
                danmaku.addComment(new DanmakuComment(SAMPLES[random.nextInt(SAMPLES.length)]));
            }
            float perMillisecond = ARRIVAL_RATE * style.maxComments / Math.max(1, style.durationMs);
            long avgIntervalMs = perMillisecond > 0f ? Math.round(1f / perMillisecond) : 500L;
            long delay = Math.round(avgIntervalMs * (0.5f + random.nextFloat()));
            handler.postDelayed(this, Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, delay)));
        }
    };

    public DanmakuPreview(Context context) {
        super(context);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        aspectRatio = Math.max(metrics.widthPixels, metrics.heightPixels)
                / (float) Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1F1F2E, 0xFF0E0E10, 0xFF2B2038});
        bg.setCornerRadius(12 * metrics.density);
        bg.setStroke(Math.max(1, Math.round(metrics.density)), SettingsUi.borderColor(context));
        setBackground(bg);
        setClipToOutline(true);
        danmaku = new DanmakuView(context);
        addView(danmaku, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width / aspectRatio);
        // In landscape, a preview as wide as the screen would cover the whole screen.
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION);
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.round(height * aspectRatio);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == View.VISIBLE) startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE && isAttachedToWindow()) {
            startLoop();
        } else {
            stopLoop();
        }
    }

    private void startLoop() {
        if (running) return;
        running = true;
        handler.removeCallbacks(addSamples);
        handler.post(addSamples);
    }

    private void stopLoop() {
        running = false;
        handler.removeCallbacks(addSamples);
    }
}
