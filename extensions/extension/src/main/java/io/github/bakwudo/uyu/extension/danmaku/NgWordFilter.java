package io.github.bakwudo.uyu.extension.danmaku;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;
import io.github.bakwudo.uyu.extension.settings.Setting;

/** 本文がNGワードと完全一致するコメントを、受信した時点でリストから取り除く */
public final class NgWordFilter {
    private static final String TAG = "KizuNgWord";

    private static volatile boolean initialized;
    private static volatile Set<String> cachedWords = Collections.emptySet();
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || KizuSettings.NG_WORDS.key.equals(key)) {
                    reload();
                }
            };

    private NgWordFilter() {}

    private static Set<String> words() {
        if (!initialized) {
            synchronized (NgWordFilter.class) {
                if (!initialized) {
                    initialized = true;
                    Setting.addChangeListener(PREF_LISTENER);
                    reload();
                }
            }
        }
        return cachedWords;
    }

    private static void reload() {
        String raw;
        try {
            raw = KizuSettings.NG_WORDS.get();
        } catch (Throwable t) {
            return;
        }
        if (raw == null || raw.isEmpty()) {
            cachedWords = Collections.emptySet();
            return;
        }
        Set<String> set = new HashSet<>();
        for (String line : raw.split("\n")) {
            String w = line.trim();
            if (!w.isEmpty()) set.add(w);
        }
        cachedWords = set.isEmpty() ? Collections.emptySet() : set;
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
        for (int i = 0, n = tokens.size(); i < n; i++) {
            Object token = tokens.get(i);
            // Any message containing an emote can never exact-match a plain NG word.
            if (ChatMessages.emoteTokenId(token) != null) return null;
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
