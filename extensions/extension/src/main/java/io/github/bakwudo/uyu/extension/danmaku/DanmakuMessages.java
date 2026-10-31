package io.github.bakwudo.uyu.extension.danmaku;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns received chat messages into danmaku comments.
 */
final class DanmakuMessages {
    /**
     * Message ids already handled. The chat connection sends the last messages again when a
     * channel is joined again, and the same batch can reach the hook more than once.
     */
    private static final Map<String, Boolean> seenIds = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 2000;
        }
    };

    private DanmakuMessages() {
    }

    // ===== 流速に応じた間引き（弾幕だけ。チャット欄のコメントは減らさない） =====
    private static final long DUP_WINDOW_MS = 3000;
    private static final int FLOOD_PER_SECOND = 10;
    /** 最近流した本文 → 時刻（連投の判定用） */
    private static final Map<String, Long> recentBodies = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 500;
        }
    };
    private static float floodTokens = FLOOD_PER_SECOND;
    private static long floodRefillAt;

    /**
     * 混雑: 連投・スタンプだけのコメントを先に削り、全体を半分程度に。
     * 激流: 同じく削ったうえで1秒あたり最大10件。
     */
    static synchronized List<?> thin(List<?> messages, int level) {
        if (level <= 0 || messages.isEmpty()) return messages;
        long now = android.os.SystemClock.uptimeMillis();
        List<Object> good = new ArrayList<>(messages.size());
        List<Object> weak = new ArrayList<>(); // スタンプだけ・連投
        for (Object message : messages) {
            String body = plainBody(message);
            if (body == null) continue;
            boolean emoteOnly = body.isEmpty();
            boolean dup = false;
            if (!emoteOnly) {
                Long at = recentBodies.get(body);
                dup = at != null && now - at < DUP_WINDOW_MS;
                recentBodies.put(body, now);
            }
            if (emoteOnly || dup) weak.add(message); else good.add(message);
        }

        int budget;
        if (level == 1) {
            budget = Math.max(1, (messages.size() + 1) / 2);
        } else {
            if (floodRefillAt == 0) floodRefillAt = now;
            floodTokens = Math.min(FLOOD_PER_SECOND, floodTokens + (now - floodRefillAt) * FLOOD_PER_SECOND / 1000f);
            floodRefillAt = now;
            budget = (int) floodTokens;
            if (budget <= 0) return java.util.Collections.emptyList();
        }

        List<Object> out = new ArrayList<>(Math.min(budget, messages.size()));
        pickEvenly(good, budget, out);
        if (out.size() < budget) pickEvenly(weak, budget - out.size(), out);
        if (level >= 2) floodTokens -= out.size();
        return out;
    }

    /** 全体から均等に count 件取る（同じ時間帯に偏らないように） */
    private static void pickEvenly(List<Object> from, int count, List<Object> out) {
        int n = from.size();
        if (count <= 0 || n == 0) return;
        if (count >= n) { out.addAll(from); return; }
        float step = n / (float) count;
        for (int i = 0; i < count; i++) out.add(from.get(Math.min(n - 1, (int) (i * step))));
    }

    /** 文字の部分だけの本文。スタンプだけなら空文字、読めなければ null */
    private static String plainBody(Object message) {
        Object info = ChatMessages.messageInfo(message);
        List<?> tokens = info == null ? null : ChatMessages.tokens(info);
        if (tokens == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Object token : tokens) {
            if (ChatMessages.emoteTokenId(token) != null) continue;
            String s = ChatMessages.textTokenText(token);
            if (s == null) s = ChatMessages.mentionTokenText(token);
            if (s == null) s = ChatMessages.urlTokenUrl(token);
            if (s != null) sb.append(s);
        }
        return sb.toString().trim();
    }

    /**
     * @param messages ChatLiveMessage objects.
     * @return Comments for the messages not seen before. Messages without text or emotes are
     * left out.
     */
    static List<DanmakuComment> toComments(List<?> messages) {
        List<DanmakuComment> comments = new ArrayList<>(messages.size());
        for (Object message : messages) {
            String id = ChatMessages.messageId(message);
            if (id != null) {
                synchronized (seenIds) {
                    if (seenIds.put(id, Boolean.TRUE) != null) continue;
                }
            }

            Object info = ChatMessages.messageInfo(message);
            List<?> tokens = info == null ? null : ChatMessages.tokens(info);
            DanmakuComment comment = tokens == null ? null : toComment(tokens);
            if (comment != null) comments.add(comment);
        }
        return comments;
    }

    /**
     * Keeps the text and the Twitch emotes. Mentions, links and cheers are shown as text.
     */
    private static DanmakuComment toComment(List<?> tokens) {
        List<Object> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Object token : tokens) {
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

    private static void addText(List<Object> parts, StringBuilder text) {
        if (text.length() == 0) return;
        parts.add(text.toString().replaceAll("\\s", " "));
        text.setLength(0);
    }

    /** Removes leading and trailing spaces, and parts left empty. */
    private static void trimEnds(List<Object> parts) {
        if (!parts.isEmpty() && parts.get(0) instanceof String first) {
            String trimmed = first.replaceAll("^ +", "");
            if (trimmed.isEmpty()) parts.remove(0);
            else parts.set(0, trimmed);
        }
        int last = parts.size() - 1;
        if (last >= 0 && parts.get(last) instanceof String end) {
            String trimmed = end.replaceAll(" +$", "");
            if (trimmed.isEmpty()) parts.remove(last);
            else parts.set(last, trimmed);
        }
    }
}
