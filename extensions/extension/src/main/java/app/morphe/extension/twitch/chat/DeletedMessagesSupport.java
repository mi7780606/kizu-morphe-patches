package app.morphe.extension.twitch.chat;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bakwudo.uyu.extension.settings.Settings;

public final class DeletedMessagesSupport {
    private static final String DIAGNOSTIC_TAG = "KizuDeletedStyle";
    private static final String DELETED_MARKER = "<message deleted>";
    private static final int ORIGINAL_CACHE_LIMIT = 1_500;
    private static final long ORIGINAL_CACHE_TTL_MS = 5L * 60L * 1000L;
    private static final Object ORIGINAL_CACHE_LOCK = new Object();
    private static long lastOriginalCachePruneAt;
    private static final Map<String, CachedOriginal> ORIGINAL_MESSAGES =
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedOriginal> eldest) {
                    return size() > ORIGINAL_CACHE_LIMIT;
                }
            };

    private static final ConcurrentHashMap<Class<?>, Field> MESSAGE_ID_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> SPANNED_FIELDS = new ConcurrentHashMap<>();
    private static final Field MISSING_FIELD;
    private static final ColorMatrixColorFilter GRAY_FILTER;

    static {
        Field sentinel = null;
        try {
            sentinel = DeletedMessagesSupport.class.getDeclaredField("DIAGNOSTIC_TAG");
        } catch (NoSuchFieldException ignored) {}
        MISSING_FIELD = sentinel;

        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        GRAY_FILTER = new ColorMatrixColorFilter(matrix);
    }

    private static final class CachedOriginal {
        final SpannedString text;
        final long capturedAt;

        CachedOriginal(SpannedString text, long capturedAt) {
            this.text = text;
            this.capturedAt = capturedAt;
        }
    }

    /** Marker retained through EmoteSupport's text rebuild so later emotes inherit Gray mode. */
    public static final class DeletedStyleMarker {
        private DeletedStyleMarker() {}
    }

    private DeletedMessagesSupport() {}

    public static void onRowBound(Object messageModel, TextView textView) {
        if (textView == null) return;
        try {
            if (!Settings.CHAT_DELETED_MESSAGES.get()) return;
            String messageId = readMessageId(messageModel);
            if (messageId == null || messageId.isEmpty()) return;

            CharSequence current = textView.getText();
            if (current == null || current.length() == 0) return;

            if (containsDeletedPlaceholder(current)) {
                Spanned recovered = current instanceof Spanned
                        ? restoreDeletedMessageFromRow((Spanned) current)
                        : null;
                if (recovered != null && recovered.length() > 0) {
                    textView.setText(recovered, TextView.BufferType.SPANNABLE);
                    return;
                }
                SpannedString cached = findCachedOriginal(messageId, SystemClock.uptimeMillis());
                if (cached != null && cached.length() > 0) {
                    SpannableStringBuilder restored = new SpannableStringBuilder(cached);
                    int bodyStart = findMessageBodyStart(restored);
                    applyDeletedStyle(restored, bodyStart, restored.length());
                    textView.setText(SpannedString.valueOf(restored), TextView.BufferType.SPANNABLE);
                }
                return;
            }

            SpannedString snapshot = new SpannedString(current);
            long now = SystemClock.uptimeMillis();
            synchronized (ORIGINAL_CACHE_LOCK) {
                ORIGINAL_MESSAGES.put(messageId, new CachedOriginal(snapshot, now));
                if (ORIGINAL_MESSAGES.size() > 256
                        && now - lastOriginalCachePruneAt >= 60_000L) {
                    lastOriginalCachePruneAt = now;
                    Iterator<Map.Entry<String, CachedOriginal>> iterator =
                            ORIGINAL_MESSAGES.entrySet().iterator();
                    while (iterator.hasNext()) {
                        if (now - iterator.next().getValue().capturedAt > ORIGINAL_CACHE_TTL_MS) {
                            iterator.remove();
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            Log.w(DIAGNOSTIC_TAG, "could not cache or restore a chat row", failure);
        }
    }

    private static SpannedString findCachedOriginal(String messageId, long now) {
        synchronized (ORIGINAL_CACHE_LOCK) {
            CachedOriginal cached = ORIGINAL_MESSAGES.get(messageId);
            if (cached == null) return null;
            if (now - cached.capturedAt > ORIGINAL_CACHE_TTL_MS) {
                ORIGINAL_MESSAGES.remove(messageId);
                return null;
            }
            return cached.text;
        }
    }

    private static boolean containsDeletedPlaceholder(CharSequence text) {
        int len = text == null ? 0 : text.length();
        int mLen = DELETED_MARKER.length();
        if (len < mLen) return false;
        int first = TextUtils.indexOf(text, '<');
        if (first < 0) return false;
        int limit = len - mLen;
        for (int start = first; start <= limit; start++) {
            if (text.charAt(start) == '<' && regionMatchesIgnoreCase(text, start, DELETED_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatchesIgnoreCase(CharSequence text, int offset, String lowerTarget) {
        int len = lowerTarget.length();
        for (int i = 0; i < len; i++) {
            char a = text.charAt(offset + i);
            char b = lowerTarget.charAt(i);
            if (a != b && Character.toLowerCase(a) != b) return false;
        }
        return true;
    }

    private static String readMessageId(Object model) {
        if (model == null) return null;
        Class<?> type = model.getClass();
        Field cached = MESSAGE_ID_FIELDS.get(type);
        if (cached != null && cached != MISSING_FIELD) {
            try {
                Object value = cached.get(model);
                if (value != null) {
                    String id = String.valueOf(value);
                    if (!id.isEmpty()) return id;
                }
            } catch (Throwable ignored) {}
        }

        if (cached == null) {
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                try {
                    Field f = current.getDeclaredField("messageId");
                    f.setAccessible(true);
                    MESSAGE_ID_FIELDS.putIfAbsent(type, f);
                    Object value = f.get(model);
                    if (value != null) {
                        String id = String.valueOf(value);
                        if (!id.isEmpty()) return id;
                    }
                    break;
                } catch (NoSuchFieldException ignored) {}
                  catch (Throwable ignored) {}
            }
        }

        String parsed = readMessageIdFromDataClassString(model);
        if (cached == null && parsed != null) {
            Field discovered = discoverStringFieldWithValue(type, model, parsed);
            MESSAGE_ID_FIELDS.putIfAbsent(type, discovered != null ? discovered : MISSING_FIELD);
        } else if (cached == null) {
            MESSAGE_ID_FIELDS.putIfAbsent(type, MISSING_FIELD);
        }
        return parsed;
    }

    private static Field discoverStringFieldWithValue(Class<?> type, Object model, String expected) {
        Field match = null;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object val = field.get(model);
                    if (expected.equals(val)) {
                        if (match != null) return null; // Ambiguous, keep toString fallback.
                        match = field;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return match;
    }

    private static String readMessageIdFromDataClassString(Object model) {
        try {
            String value = String.valueOf(model);
            int marker = value.indexOf("MessageRecyclerItem(messageId=");
            int start = marker >= 0
                    ? marker + "MessageRecyclerItem(messageId=".length()
                    : value.indexOf("messageId=");
            if (start < 0) return null;

            int end = value.indexOf(", sourceChannelId=", start);
            if (end < 0) end = value.indexOf(',', start);
            if (end < 0) return null;

            String id = value.substring(start, end).trim();
            return id.isEmpty() || "null".equals(id) ? null : id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean resolveAccess(boolean original) {
        try {
            if (!Settings.CHAT_DELETED_MESSAGES.get()) return original;
            return true;
        } catch (Throwable ignored) {
            return original;
        }
    }

    public static Spanned styleDeletedMessageFromFactory(Spanned message) {
        try {
            if (!Settings.CHAT_DELETED_MESSAGES.get() || message == null || message.length() == 0) {
                return null;
            }
            if (containsDeletedPlaceholder(message)) return null;

            String style = normalizeStyle();
            if ("mod".equals(style)) return null;

            SpannableStringBuilder builder = new SpannableStringBuilder(message);
            int bodyStart = findMessageBodyStart(builder);
            if ("strikethrough".equals(style)) {
                if (builder.getSpans(0, builder.length(), StrikethroughSpan.class).length > 0) {
                    return message;
                }
                if (bodyStart < builder.length()) {
                    builder.setSpan(new StrikethroughSpan(), bodyStart, builder.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if ("grey".equals(style)) {
                for (ForegroundColorSpan span : builder.getSpans(
                        0, builder.length(), ForegroundColorSpan.class)) {
                    builder.removeSpan(span);
                }
                grayInlineDrawables(builder);
                builder.setSpan(new ForegroundColorSpan(Color.GRAY), 0, builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new DeletedStyleMarker(), 0, builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                return null;
            }
            return SpannedString.valueOf(builder);
        } catch (Throwable failure) {
            Log.e(DIAGNOSTIC_TAG, "could not style original deleted message", failure);
            return null;
        }
    }

    public static boolean isGreyDeletedMessage(CharSequence text) {
        if (!(text instanceof Spanned spanned)) return false;
        return spanned.getSpans(0, spanned.length(), DeletedStyleMarker.class).length > 0;
    }

    public static void applyGrayFilter(Drawable drawable) {
        if (drawable == null) return;
        try {
            drawable.setColorFilter(GRAY_FILTER);
        } catch (Throwable ignored) {}
    }

    private static void grayInlineDrawables(Spanned text) {
        try {
            for (DynamicDrawableSpan span : text.getSpans(
                    0, text.length(), DynamicDrawableSpan.class)) {
                applyGrayFilter(span.getDrawable());
            }
        } catch (Throwable ignored) {}
    }

    public static Spanned restoreDeletedMessageFromRow(Spanned message) {
        if (message == null || message.length() == 0) return null;
        try {
            if (!Settings.CHAT_DELETED_MESSAGES.get()) return null;
            for (ClickableSpan deletedSpan : message.getSpans(0, message.length(), ClickableSpan.class)) {
                Field spannedField = spannedFieldFor(deletedSpan);
                if (spannedField == null) continue;
                int start = message.getSpanStart(deletedSpan), end = message.getSpanEnd(deletedSpan);
                if (start < 0 || end <= start || end > message.length()) continue;
                SpannedString original = readSpannedField(deletedSpan, spannedField);
                if (original == null || original.length() == 0
                        || containsDeletedPlaceholder(original)) continue;
                SpannedString body = stripDuplicateChatterHeader(
                        SpannedString.valueOf(message), deletedSpan, start, original);
                SpannableStringBuilder builder = new SpannableStringBuilder(message);
                builder.replace(start, end, body);
                builder.removeSpan(deletedSpan);
                applyDeletedStyle(builder, start, start + body.length());
                return SpannedString.valueOf(builder);
            }
        } catch (Throwable failure) {
            Log.w(DIAGNOSTIC_TAG, "could not restore original deleted chat message", failure);
        }
        return null;
    }

    private static Field spannedFieldFor(Object candidate) {
        if (candidate == null) return null;
        Class<?> type = candidate.getClass();
        Field cached = SPANNED_FIELDS.get(type);
        if (cached != null) return cached == MISSING_FIELD ? null : cached;

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() == SpannedString.class) {
                        field.setAccessible(true);
                        SPANNED_FIELDS.putIfAbsent(type, field);
                        return field;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (MISSING_FIELD != null) SPANNED_FIELDS.putIfAbsent(type, MISSING_FIELD);
        return null;
    }

    private static SpannedString readSpannedField(ClickableSpan deletedSpan, Field field) {
        try {
            Object value = field.get(deletedSpan);
            return value instanceof SpannedString ? (SpannedString) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int findMessageBodyStart(Spanned text) {
        try {
            for (ClickableSpan span : text.getSpans(0, text.length(), ClickableSpan.class)) {
                if (!"ClickableUsernameSpan".equals(span.getClass().getSimpleName())) continue;
                int end = text.getSpanEnd(span);
                if (end < 0 || end > text.length()) continue;
                if (end + 2 <= text.length() && ": ".contentEquals(text.subSequence(end, end + 2))) {
                    return end + 2;
                }
                return end;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void applyDeletedStyle(SpannableStringBuilder text, int start, int end) {
        if (start < 0 || end <= start || end > text.length()) return;
        String style = normalizeStyle();

        if ("strikethrough".equals(style)) {
            start = findMessageBodyStart(text);
            if (start >= end) return;
            for (StrikethroughSpan span : text.getSpans(start, end, StrikethroughSpan.class)) {
                int spanStart = text.getSpanStart(span);
                int spanEnd = text.getSpanEnd(span);
                if (spanStart >= start && spanEnd <= end) text.removeSpan(span);
            }
            text.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if ("grey".equals(style)) {
            for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
                text.removeSpan(span);
            }
            grayInlineDrawables(text);
            text.setSpan(new ForegroundColorSpan(Color.GRAY), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (text.getSpans(0, text.length(), DeletedStyleMarker.class).length == 0) {
                text.setSpan(new DeletedStyleMarker(), 0, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static String normalizeStyle() {
        try {
            String style = Settings.CHAT_DELETED_MESSAGES_STYLE.get();
            if (style == null) return "mod";
            style = style.trim().toLowerCase(java.util.Locale.ROOT);
            if ("default".equals(style)) return "mod";
            if ("gray".equals(style)) return "grey";
            if ("strikethrough".equals(style) || "grey".equals(style) || "mod".equals(style)) {
                return style;
            }
        } catch (Throwable ignored) {}
        return "mod";
    }

    private static SpannedString stripDuplicateChatterHeader(
            SpannedString message,
            ClickableSpan deletedSpan,
            int spanStart,
            SpannedString original
    ) {
        try {
            int lineStart = 0;
            for (int i = spanStart - 1; i >= 0; i--) {
                if (message.charAt(i) == 10) {
                    lineStart = i + 1;
                    break;
                }
            }
            if (spanStart <= lineStart) return original;

            String outerText = message.toString();
            String originalText = original.toString();

            int delimiter = outerText.lastIndexOf(": ", spanStart - 1);
            if (delimiter >= lineStart) {
                int headerEnd = delimiter + 2;
                String fullHeader = outerText.substring(lineStart, headerEnd);
                if (!fullHeader.isEmpty() && originalText.startsWith(fullHeader)) {
                    return deletePrefix(original, fullHeader.length());
                }
            }

            ClickableSpan[] spans = message.getSpans(lineStart, spanStart, ClickableSpan.class);
            ClickableSpan candidate = null;
            int candidateEnd = -1;
            for (ClickableSpan span : spans) {
                if (span == deletedSpan) continue;
                int start = message.getSpanStart(span);
                int end = message.getSpanEnd(span);
                if (start < lineStart || end <= start || end > spanStart) continue;
                if (end <= candidateEnd) continue;

                if (end + 2 <= spanStart
                        && ": ".contentEquals(message.subSequence(end, end + 2))) {
                    candidate = span;
                    candidateEnd = end;
                }
            }

            if (candidate != null) {
                int usernameStart = message.getSpanStart(candidate);
                int usernameEnd = message.getSpanEnd(candidate);
                if (usernameStart >= lineStart
                        && usernameEnd > usernameStart
                        && usernameEnd <= spanStart) {
                    String usernamePrefix =
                            message.subSequence(usernameStart, usernameEnd).toString() + ": ";
                    if (originalText.startsWith(usernamePrefix)) {
                        return deletePrefix(original, usernamePrefix.length());
                    }
                }
            }

            String before = outerText.substring(lineStart, spanStart);
            int previous = before.lastIndexOf(": ");
            if (previous >= 0) {
                String tail = before.substring(previous + 2);
                String tailPrefix = tail + ": ";
                if (!tail.isEmpty() && originalText.startsWith(tailPrefix)) {
                    return deletePrefix(original, tailPrefix.length());
                }
            }
        } catch (Throwable ignored) {}

        return original;
    }

    private static SpannedString deletePrefix(SpannedString original, int length) {
        if (length <= 0 || length > original.length()) return original;
        SpannableStringBuilder builder = new SpannableStringBuilder(original);
        builder.delete(0, length);
        return SpannedString.valueOf(builder);
    }
}
