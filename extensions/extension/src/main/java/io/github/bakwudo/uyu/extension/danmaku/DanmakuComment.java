package io.github.bakwudo.uyu.extension.danmaku;

import android.graphics.Bitmap;

/**
 * One chat message to scroll across the video: text and Twitch emotes, in order.
 */
public final class DanmakuComment {
    /** A Twitch emote, drawn as an image one line high. */
    public static final class Emote {
        public final String id;
        int cachedTier = -1;
        String cachedKey;

        public Emote(String id) {
            this.id = id;
        }
    }

    /** Each part is a {@link String} of text or an {@link Emote}. */
    final Object[] parts;

    // Set by DanmakuView for its current style.
    float[] partWidths;
    float width;
    int row;
    /** Time on the view's clock when the comment entered at the right edge. */
    long start;
    int measuredGen;

    // Cached pre-rendered sprite bitmap (managed by DanmakuView).
    Bitmap sprite;
    int spriteGen;
    int spriteBytes;
    boolean spriteComplete;

    // Kizu: 待機列用
    long due;
    long queuedAt;

    public DanmakuComment(Object[] parts) {
        this.parts = parts;
        this.partWidths = new float[parts.length];
    }
}
