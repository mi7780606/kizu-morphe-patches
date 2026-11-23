package io.github.bakwudo.uyu.extension.danmaku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * Turns received chat messages into danmaku comments without regex allocations or redundant seen-id checks.
 */
final class DanmakuMessages {
    private static final Map<String, Boolean> seenIds = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 2000;
        }
    };

    private DanmakuMessages() {}

    private static final long DUP_WINDOW_MS = 3000;
    private static final int FLOOD_PER_SECOND = 10;
    private static final Map<String, Long> recentBodies = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 500;
        }
    };
    private static float floodTokens = FLOOD_PER_SECOND;
    private static long floodRefillAt;

    /** Deduplicates messages before thinning so replayed batches do not burn flood budget. */
    static List<?> filterNew(List<?> messages) {
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        List<Object> fresh = null;
        synchronized (seenIds) {
            for (int i = 0, n = messages.size(); i < n; i++) {
                Object message = messages.get(i);
                String id = ChatMessages.messageId(message);
                boolean dup = id != null && seenIds.put(id, Boolean.TRUE) != null;
                if (dup) {
                    if (fresh == null) {
                        fresh = new ArrayList<>(n);
                        for (int j = 0; j < i; j++) fresh.add(messages.get(j));
                    }
                } else if (fresh != null) {
                    fresh.add(message);
                }
            }
        }
        return fresh == null ? messages : fresh;
    }

    static synchronized List<?> thin(List<?> messages, int level) {
        if (level <= 0 || messages.isEmpty()) return messages;
        long now = android.os.SystemClock.uptimeMillis();
        List<Object> good = new ArrayList<>(messages.size());
        List<Object> weak = new ArrayList<>();
        for (int i = 0, n = messages.size(); i < n; i++) {
            Object message = messages.get(i);
            String body = plainBody(message);
            if (body == null) continue;
            boolean emoteOnly = body.isEmpty();
            boolean dup = false;
            if (!emoteOnly) {
                Long at = recentBodies.get(body);
                dup = at != null && now - at < DUP_WINDOW_MS;
                recentBodies.put(body, now);
            }
            if (emoteOnly || dup) weak.add(message);
            else good.add(message);
        }

        int budget;
        if (level == 1) {
            budget = Math.max(1, (messages.size() + 1) / 2);
        } else {
            if (floodRefillAt == 0) floodRefillAt = now;
            floodTokens = Math.min(FLOOD_PER_SECOND, floodTokens + (now - floodRefillAt) * FLOOD_PER_SECOND / 1000f);
            floodRefillAt = now;
            budget = (int) floodTokens;
            if (budget <= 0) return Collections.emptyList();
        }

        List<Object> out = new ArrayList<>(Math.min(budget, messages.size()));
        pickEvenly(good, budget, out);
        if (out.size() < budget) pickEvenly(weak, budget - out.size(), out);
        if (level >= 2) floodTokens -= out.size();
        return out;
    }

    private static void pickEvenly(List<Object> from, int count, List<Object> out) {
        int n = from.size();
        if (count <= 0 || n == 0) return;
        if (count >= n) { out.addAll(from); return; }
        float step = n / (float) count;
        for (int i = 0; i < count; i++) out.add(from.get(Math.min(n - 1, (int) (i * step))));
    }

    private static String plainBody(Object message) {
        Object info = ChatMessages.messageInfo(message);
        List<?> tokens = info == null ? null : ChatMessages.tokens(info);
        if (tokens == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = tokens.size(); i < n; i++) {
            Object token = tokens.get(i);
            if (ChatMessages.emoteTokenId(token) != null) continue;
            String s = ChatMessages.textTokenText(token);
            if (s == null) s = ChatMessages.mentionTokenText(token);
            if (s == null) s = ChatMessages.urlTokenUrl(token);
            if (s != null) sb.append(s);
        }
        return sb.toString().trim();
    }

    static List<DanmakuComment> toComments(List<?> messages) {
        List<DanmakuComment> comments = new ArrayList<>(messages.size());
        int maxLength = 0;
        try { maxLength = KizuSettings.DANMAKU_MAX_LENGTH.get(); }
        catch (Throwable ignored) {}
        for (int i = 0, n = messages.size(); i < n; i++) {
            Object message = messages.get(i);
            Object info = ChatMessages.messageInfo(message);
            List<?> tokens = info == null ? null : ChatMessages.tokens(info);
            DanmakuComment comment = tokens == null ? null : toComment(tokens);
            if (comment == null) continue;
            if (maxLength > 0 && length(comment) > maxLength) continue;
            comments.add(comment);
        }
        return comments;
    }

    private static DanmakuComment toComment(List<?> tokens) {
        List<Object> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0, n = tokens.size(); i < n; i++) {
            Object token = tokens.get(i);
            String emoteId = ChatMessages.emoteTokenId(token);
            if (emoteId != null) {
                addText(parts, text);
                parts.add(new DanmakuComment.Emote(emoteId));
                continue;
            }

            String tokenText = ChatMessages.textTokenText(token);
            if (tokenText == null) tokenText = ChatMessages.mentionTokenText(token);
            if (tokenText == null) tokenText = ChatMessages.urlTokenUrl(token);
            if (tokenText == null) {
                String prefix = ChatMessages.bitsTokenPrefix(token);
                if (prefix != null) tokenText = prefix + ChatMessages.bitsTokenAmount(token);
            }
            if (tokenText != null) text.append(tokenText);
        }
        addText(parts, text);

        trimEnds(parts);
        return parts.isEmpty() ? null : new DanmakuComment(parts.toArray());
    }

    private static int length(DanmakuComment comment) {
        int n = 0;
        for (Object part : comment.parts) {
            if (part instanceof String str) n += str.codePointCount(0, str.length());
            else n += 1;
        }
        return n;
    }

    private static void addText(List<Object> parts, StringBuilder text) {
        int len = text.length();
        if (len == 0) return;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c != ' ' && Character.isWhitespace(c)) {
                text.setCharAt(i, ' ');
            }
        }
        parts.add(text.toString());
        text.setLength(0);
    }

    private static void trimEnds(List<Object> parts) {
        if (!parts.isEmpty() && parts.get(0) instanceof String first) {
            int start = 0;
            int len = first.length();
            while (start < len && first.charAt(start) == ' ') start++;
            if (start >= len) parts.remove(0);
            else if (start > 0) parts.set(0, first.substring(start));
        }
        int last = parts.size() - 1;
        if (last >= 0 && parts.get(last) instanceof String end) {
            int limit = end.length();
            while (limit > 0 && end.charAt(limit - 1) == ' ') limit--;
            if (limit == 0) parts.remove(last);
            else if (limit < end.length()) parts.set(last, end.substring(0, limit));
        }
    }
}
