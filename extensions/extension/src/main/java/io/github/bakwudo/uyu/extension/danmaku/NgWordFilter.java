package io.github.bakwudo.uyu.extension.danmaku;

import android.util.Log;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** 本文がNGワードと完全一致するコメントを、受信した時点でリストから取り除く */
public final class NgWordFilter {
    private static final String TAG = "KizuNgWord";
    /** スタンプを含むコメントは完全一致の対象外にするための印 */
    private static final char EMOTE_MARK = '\uFFFC';

    private static String cachedRaw;
    private static Set<String> cachedWords = new HashSet<>();

    private NgWordFilter() {}

    private static Set<String> words() {
        String raw;
        try {
            raw = KizuSettings.NG_WORDS.get();
        } catch (Throwable t) {
            return cachedWords;
        }
        if (raw == null) raw = "";
        if (raw.equals(cachedRaw)) return cachedWords;
        Set<String> set = new HashSet<>();
        for (String line : raw.split("\n")) {
            String w = line.trim();
            if (!w.isEmpty()) set.add(w);
        }
        cachedRaw = raw;
        cachedWords = set;
        return set;
    }

    /** DanmakuPatch.onMessagesReceived の先頭から呼ぶ。リストを直接書き換える */
    public static void filter(List<?> messages) {
        if (messages == null || messages.isEmpty()) return;
        Set<String> ng = words();
        if (ng.isEmpty()) return;
        try {
            Iterator<?> it = messages.iterator();
            while (it.hasNext()) {
                String body = body(it.next());
                if (body != null && ng.contains(body)) it.remove();
            }
        } catch (UnsupportedOperationException e) {
            Log.i(TAG, "list is read-only, skipped");
        } catch (Throwable t) {
            Log.e(TAG, "filter failed", t);
        }
    }

    private static String body(Object message) {
        Object info = ChatMessages.messageInfo(message);
        List<?> tokens = info == null ? null : ChatMessages.tokens(info);
        if (tokens == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Object token : tokens) {
            if (ChatMessages.emoteTokenId(token) != null) {
                sb.append(EMOTE_MARK);
                continue;
            }
            String s = ChatMessages.textTokenText(token);
            if (s == null) s = ChatMessages.mentionTokenText(token);
            if (s == null) s = ChatMessages.urlTokenUrl(token);
            if (s == null) {
                String prefix = ChatMessages.bitsTokenPrefix(token);
                if (prefix != null) s = prefix + ChatMessages.bitsTokenAmount(token);
            }
            if (s != null) sb.append(s);
        }
        return sb.toString().trim();
    }
}
