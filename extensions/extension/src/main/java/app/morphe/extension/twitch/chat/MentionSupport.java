package app.morphe.extension.twitch.chat;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.LineBackgroundSpan;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bakwudo.uyu.extension.settings.Settings;

/**
 * Detects Twitch's own local-user MentionToken and highlights the complete chat line.
 * Caches reflection paths per concrete class to avoid per-message graph walks and toString() allocations.
 */
public final class MentionSupport {
    private static final Object LOCK = new Object();
    private static final Map<TextView, Object> MODELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TextView, Object> SOUND_MODELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile long lastSoundAtMs = Long.MIN_VALUE;
    private static ToneGenerator toneGenerator;

    private static final Field[] EMPTY_PATH = new Field[0];
    private static final ConcurrentHashMap<Class<?>, Field[]> LIVE_MESSAGE_PATHS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> TOKEN_LIST_FIELDS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, TokenKind> TOKEN_KINDS =
            new ConcurrentHashMap<>();

    private enum TokenKind {
        MENTION,
        OTHER
    }

    public static final int DEFAULT_HIGHLIGHT_COLOR = 0x4D9146FF;

    private static final String LIVE_MESSAGE_PREFIX = "LiveChatMessage(";
    private static final String MENTION_PREFIX = "MentionToken(";
    private static final String[] TOKEN_PREFIXES = {
            MENTION_PREFIX,
            "TextToken(",
            "EmoteToken(",
            "UrlToken(",
            "BitsToken(",
            "CensoredTextToken("
    };

    private static final class MarkerSpan {}
    private static final class MentionBar implements LineBackgroundSpan {
        private final int color;

        MentionBar(int color) {
            this.color = color;
        }

        @Override
        public void drawBackground(
                Canvas canvas,
                Paint paint,
                int left,
                int right,
                int top,
                int baseline,
                int bottom,
                CharSequence text,
                int start,
                int end,
                int lineNumber
        ) {
            int previous = paint.getColor();
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(previous);
        }
    }

    private MentionSupport() {}

    public static void bind(Object messageModel, TextView textView) {
        if (textView == null) return;
        synchronized (LOCK) {
            SOUND_MODELS.remove(textView);
            if (messageModel == null) {
                MODELS.remove(textView);
            } else {
                MODELS.put(textView, messageModel);
            }
        }
    }

    public static void forget(TextView textView) {
        if (textView == null) return;
        synchronized (LOCK) {
            MODELS.remove(textView);
            SOUND_MODELS.remove(textView);
        }
    }

    public static void apply(TextView textView) {
        if (textView == null) return;
        try {
            Object model;
            synchronized (LOCK) {
                model = MODELS.get(textView);
            }

            CharSequence current = textView.getText();
            if (current == null || current.length() == 0) return;

            boolean highlightEnabled = Settings.CHAT_MENTION_HIGHLIGHT.get();
            boolean soundEnabled = Settings.CHAT_MENTION_SOUND.get();
            boolean mention = (highlightEnabled || soundEnabled) && mentionsLocalUser(model);

            Spanned spanned = current instanceof Spanned ? (Spanned) current : null;
            MentionBar[] oldBars = spanned == null
                    ? new MentionBar[0]
                    : spanned.getSpans(0, spanned.length(), MentionBar.class);
            MarkerSpan[] oldMarkers = spanned == null
                    ? new MarkerSpan[0]
                    : spanned.getSpans(0, spanned.length(), MarkerSpan.class);

            if (!mention && oldBars.length == 0 && oldMarkers.length == 0) return;

            SpannableStringBuilder out = new SpannableStringBuilder(current);
            for (MentionBar span : oldBars) {
                out.removeSpan(span);
            }
            for (MarkerSpan span : oldMarkers) {
                out.removeSpan(span);
            }

            if (mention && highlightEnabled && out.length() > 0) {
                out.setSpan(
                        new MentionBar(Settings.CHAT_MENTION_HIGHLIGHT_COLOR.get()),
                        0,
                        out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                out.setSpan(
                        new MarkerSpan(),
                        0,
                        out.length(),
                        Spannable.SPAN_INCLUSIVE_INCLUSIVE
                );
            }

            textView.setText(new SpannedString(out), TextView.BufferType.SPANNABLE);

            if (mention && soundEnabled) {
                maybePlayMentionSound(textView, model);
            }
        } catch (Throwable ignored) {}
    }

    private static void maybePlayMentionSound(TextView textView, Object model) {
        if (textView == null || model == null) return;

        long now = SystemClock.uptimeMillis();
        int cooldown = Settings.CHAT_MENTION_SOUND_COOLDOWN_MS.get();
        synchronized (LOCK) {
            if (SOUND_MODELS.get(textView) == model) return;
            SOUND_MODELS.put(textView, model);
            if (lastSoundAtMs != Long.MIN_VALUE && now - lastSoundAtMs < cooldown) {
                return;
            }
            lastSoundAtMs = now;
        }

        try {
            ToneGenerator generator;
            synchronized (LOCK) {
                if (toneGenerator == null) {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);
                }
                generator = toneGenerator;
            }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 140);
        } catch (Throwable ignored) {}
    }

    private static boolean mentionsLocalUser(Object model) {
        if (model == null) return false;
        Object liveMessage = resolveLiveMessage(model);
        if (liveMessage == null) return false;

        List<?> tokens = resolveTokenList(liveMessage);
        if (tokens == null) {
            String messageText = safeString(liveMessage);
            return messageText.contains(MENTION_PREFIX) && messageText.contains("isLocalUser=true");
        }

        for (int i = 0, n = tokens.size(); i < n; i++) {
            Object token = tokens.get(i);
            if (token == null) continue;
            Class<?> tokenClass = token.getClass();
            TokenKind kind = TOKEN_KINDS.get(tokenClass);
            if (kind == TokenKind.OTHER) continue;

            String value = safeString(token);
            if (kind == null) {
                kind = value.startsWith(MENTION_PREFIX) ? TokenKind.MENTION : TokenKind.OTHER;
                TOKEN_KINDS.putIfAbsent(tokenClass, kind);
            }
            if (kind == TokenKind.MENTION && value.contains("isLocalUser=true")) {
                return true;
            }
        }
        return false;
    }

    private static Object resolveLiveMessage(Object model) {
        Class<?> modelClass = model.getClass();
        Field[] cachedPath = LIVE_MESSAGE_PATHS.get(modelClass);
        if (cachedPath != null) {
            if (cachedPath.length == 0) return model;
            Object cur = model;
            try {
                for (Field f : cachedPath) {
                    if (cur == null) return null;
                    cur = f.get(cur);
                }
                if (cur != null && safeString(cur).startsWith(LIVE_MESSAGE_PREFIX)) {
                    return cur;
                }
            } catch (Throwable ignored) {}
        }

        ArrayList<Field> path = new ArrayList<>(3);
        Object found = findLiveMessage(model, 3, new IdentityHashMap<>(), path);
        if (found != null) {
            LIVE_MESSAGE_PATHS.putIfAbsent(modelClass, path.toArray(new Field[0]));
        }
        return found;
    }

    private static Object findLiveMessage(
            Object root, int depth, Map<Object, Boolean> seen, List<Field> path) {
        if (root == null || seen.put(root, Boolean.TRUE) != null) return null;

        String rootText = safeString(root);
        if (rootText.startsWith(LIVE_MESSAGE_PREFIX)) return root;
        if (depth <= 0) return null;

        Class<?> type = root.getClass();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                try {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    if (List.class.isAssignableFrom(field.getType())
                            || Map.class.isAssignableFrom(field.getType())
                            || field.getType().isArray()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value == null) continue;

                    path.add(field);
                    if (safeString(value).startsWith(LIVE_MESSAGE_PREFIX)) {
                        return value;
                    }
                    Object nested = findLiveMessage(value, depth - 1, seen, path);
                    if (nested != null) return nested;
                    path.remove(path.size() - 1);
                } catch (Throwable ignored) {
                    if (!path.isEmpty()) path.remove(path.size() - 1);
                }
            }
        }
        return null;
    }

    private static List<?> resolveTokenList(Object message) {
        Class<?> type = message.getClass();
        Field cached = TOKEN_LIST_FIELDS.get(type);
        if (cached != null) {
            try {
                Object val = cached.get(message);
                return val instanceof List<?> list ? list : null;
            } catch (Throwable ignored) {}
        }

        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                try {
                    if (Modifier.isStatic(field.getModifiers())
                            || !List.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(message);
                    if (!(value instanceof List<?> list)) continue;
                    int checked = 0;
                    for (Object token : list) {
                        if (token == null) continue;
                        String tokenText = safeString(token);
                        if (startsWithKnownToken(tokenText)) {
                            TOKEN_LIST_FIELDS.putIfAbsent(type, field);
                            return list;
                        }
                        if (++checked >= 3) break;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean startsWithKnownToken(String value) {
        for (String prefix : TOKEN_PREFIXES) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String safeString(Object value) {
        try {
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
