package io.github.bakwudo.uyu.extension.danmaku;

import android.graphics.Typeface;

import io.github.bakwudo.uyu.extension.settings.Settings;

/**
 * The danmaku settings at one point in time.
 */
final class DanmakuStyle {
    final int rows;
    final int usableRows;
    final int maxComments;
    final int durationMs;
    final float invDuration;
    final Typeface typeface;
    final int textColor;
    final int outlineColor;
    final float outlineRatio;
    final float opacity;

    private DanmakuStyle() {
        rows = Settings.DANMAKU_ROWS.get();
        usableRows = Math.max(1, rows * Settings.DANMAKU_AREA.get() / 100);
        maxComments = Settings.DANMAKU_MAX_COMMENTS.get();
        durationMs = Math.max(1, Settings.DANMAKU_DURATION.get());
        invDuration = 1f / durationMs;
        typeface = DanmakuFonts.typeface(Settings.DANMAKU_FONT.get(), Settings.DANMAKU_FONT_WEIGHT.get());
        textColor = Settings.DANMAKU_TEXT_COLOR.get();
        outlineColor = Settings.DANMAKU_OUTLINE_COLOR.get();
        outlineRatio = Settings.DANMAKU_OUTLINE_WIDTH.get() / 100f;
        opacity = Settings.DANMAKU_OPACITY.get() / 100f;
    }

    boolean metricsEqual(DanmakuStyle other) {
        if (other == null) return false;
        return rows == other.rows
                && textColor == other.textColor
                && outlineColor == other.outlineColor
                && Float.compare(outlineRatio, other.outlineRatio) == 0
                && typeface.equals(other.typeface);
    }

    static DanmakuStyle fromSettings() {
        return new DanmakuStyle();
    }
}
