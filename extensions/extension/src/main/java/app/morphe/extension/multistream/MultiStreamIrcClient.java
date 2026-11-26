package app.morphe.extension.multistream;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.net.ssl.SSLSocketFactory;

import app.morphe.extension.KizuRuntime;
import io.github.bakwudo.uyu.extension.danmaku.DanmakuComment;
import io.github.bakwudo.uyu.extension.danmaku.NgWordFilter;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * Lightweight Twitch IRCv3-over-TLS client for Multi-View and Sub-PiP:
 * - Receives live chat messages with Twitch emote ranges and converts them into {@link DanmakuComment}.
 * - Supports authenticated comment sending ({@code PRIVMSG}) from Portrait Multi-View.
 */
final class MultiStreamIrcClient {
    private static final String TAG = "KizuMultiIrc";
    private static final String IRC_HOST = "irc.chat.twitch.tv";
    private static final int IRC_PORT = 6697;
    private static final int SOCKET_TIMEOUT_MS = 35_000;
    private static final Random RANDOM = new Random();

    interface Listener {
        void onDanmakuComment(DanmakuComment comment);
    }

    interface SendCallback {
        void onResult(boolean ok, String errorMessage);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object writeLock = new Object();

    private volatile boolean closed;
    private volatile boolean authenticated;
    private volatile String activeChannel = "";
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private Thread readerThread;

    MultiStreamIrcClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void joinChannel(String rawLogin) {
        if (closed || rawLogin == null) return;
        String next = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (next.isEmpty()) return;

        String prev = activeChannel;
        activeChannel = next;

        if (writer != null && socket != null && !socket.isClosed()) {
            if (!next.equals(prev)) {
                KizuRuntime.ioExecutor().execute(() -> {
                    if (!prev.isEmpty()) sendRaw("PART #" + prev);
                    sendRaw("JOIN #" + next);
                });
            }
            return;
        }
        startConnectionThread();
    }

    void sendMessage(String rawText, SendCallback callback) {
        if (closed) {
            postSendResult(callback, false, "接続が閉じられています");
            return;
        }
        String channel = activeChannel;
        if (channel == null || channel.isEmpty()) {
            postSendResult(callback, false, "送信先チャンネルが未選択です");
            return;
        }
        String cleaned = rawText == null ? "" : rawText.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.isEmpty()) {
            postSendResult(callback, false, null);
            return;
        }

        KizuRuntime.ioExecutor().execute(() -> {
            if (!authenticated) {
                postSendResult(callback, false, "ログイン状態を確認できませんでした");
                return;
            }
            boolean ok = sendRaw("PRIVMSG #" + channel + " :" + cleaned);
            if (ok) {
                postSendResult(callback, true, null);
                DanmakuComment echo = buildDanmakuComment(cleaned, null);
                if (echo != null && listener != null) {
                    main.post(() -> {
                        if (!closed) listener.onDanmakuComment(echo);
                    });
                }
            } else {
                postSendResult(callback, false, "コメントの送信に失敗しました");
            }
        });
    }

    void disconnect() {
        closed = true;
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            KizuRuntime.ioExecutor().execute(() -> {
                try { s.close(); } catch (Throwable ignored) {}
            });
        }
        Thread t = readerThread;
        readerThread = null;
        if (t != null) t.interrupt();
    }

    private synchronized void startConnectionThread() {
        if (closed) return;
        if (readerThread != null && readerThread.isAlive()) return;
        Thread t = new Thread(this::runLoop, "kizu-ms-irc");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void runLoop() {
        int backoffMs = 1500;
        while (!closed) {
            Socket s = null;
            try {
                String authToken = KizuRuntime.readAuthToken(context);
                String login = authToken.isEmpty() ? "" : MultiStreamApi.resolveCurrentUserLogin(context);

                s = SSLSocketFactory.getDefault().createSocket(IRC_HOST, IRC_PORT);
                s.setSoTimeout(SOCKET_TIMEOUT_MS);
                socket = s;

                BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                writer = bw;

                sendRaw("CAP REQ :twitch.tv/tags");
                if (!authToken.isEmpty() && !login.isEmpty()) {
                    authenticated = true;
                    sendRaw("PASS oauth:" + authToken);
                    sendRaw("NICK " + login);
                } else {
                    authenticated = false;
                    sendRaw("NICK justinfan" + (10000 + RANDOM.nextInt(89999)));
                }

                String chan = activeChannel;
                if (chan != null && !chan.isEmpty()) {
                    sendRaw("JOIN #" + chan);
                }
                backoffMs = 1500;

                String line;
                while (!closed && (line = br.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Throwable t) {
                if (!closed) {
                    Log.i(TAG, "IRC disconnected: " + t.getClass().getSimpleName());
                }
            } finally {
                writer = null;
                if (s != null) {
                    try { s.close(); } catch (Throwable ignored) {}
                }
                if (socket == s) socket = null;
            }

            if (closed) break;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ignored) {
                break;
            }
            backoffMs = Math.min(12_000, backoffMs * 2);
        }
    }

    private void handleLine(String line) {
        if (line.startsWith("PING ")) {
            sendRaw("PONG " + line.substring(5));
            return;
        }
        String tags = null;
        String rest = line;
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            if (sp <= 0) return;
            tags = rest.substring(1, sp);
            rest = rest.substring(sp + 1);
        }
        int privIdx = rest.indexOf(" PRIVMSG #");
        if (privIdx < 0) return;
        int chanStart = privIdx + " PRIVMSG #".length();
        int msgSep = rest.indexOf(" :", chanStart);
        if (msgSep <= chanStart) return;

        String channel = rest.substring(chanStart, msgSep).trim().toLowerCase(Locale.ROOT);
        if (!channel.equals(activeChannel)) return;

        String message = rest.substring(msgSep + 2);
        if (message.startsWith("\u0001ACTION ") && message.endsWith("\u0001") && message.length() > 9) {
            message = message.substring(8, message.length() - 1);
        }
        String emotesTag = extractTag(tags, "emotes");
        DanmakuComment comment = buildDanmakuComment(message, emotesTag);
        if (comment != null && listener != null) {
            main.post(() -> {
                if (!closed) listener.onDanmakuComment(comment);
            });
        }
    }

    private static String extractTag(String tags, String key) {
        if (tags == null || tags.isEmpty()) return null;
        String prefix = key + "=";
        int start = 0;
        int len = tags.length();
        while (start < len) {
            int semi = tags.indexOf(';', start);
            int end = semi < 0 ? len : semi;
            if (end - start >= prefix.length() && tags.regionMatches(start, prefix, 0, prefix.length())) {
                return tags.substring(start + prefix.length(), end);
            }
            if (semi < 0) break;
            start = semi + 1;
        }
        return null;
    }

    private static final class EmoteRange {
        final String id;
        final int start;
        final int end;

        EmoteRange(String id, int start, int end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    private static DanmakuComment buildDanmakuComment(String message, String emotesTag) {
        if (message == null) return null;
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return null;

        List<EmoteRange> ranges = parseEmoteRanges(emotesTag, message.length());
        if (ranges.isEmpty() && NgWordFilter.matchesPlainText(trimmed)) {
            return null;
        }

        int maxLen = 0;
        try { maxLen = KizuSettings.DANMAKU_MAX_LENGTH.get(); } catch (Throwable ignored) {}

        List<Object> parts = new ArrayList<>();
        int effectiveChars = 0;
        if (ranges.isEmpty()) {
            parts.add(trimmed);
            effectiveChars = trimmed.length();
        } else {
            int cursor = 0;
            for (EmoteRange r : ranges) {
                if (r.start < cursor || r.end >= message.length()) continue;
                if (r.start > cursor) {
                    String segment = message.substring(cursor, r.start);
                    parts.add(segment);
                    effectiveChars += segment.trim().length();
                }
                parts.add(new DanmakuComment.Emote(r.id));
                effectiveChars += 1;
                cursor = r.end + 1;
            }
            if (cursor < message.length()) {
                String tail = message.substring(cursor);
                if (!tail.isEmpty()) {
                    parts.add(tail);
                    effectiveChars += tail.trim().length();
                }
            }
        }
        if (parts.isEmpty()) return null;
        if (maxLen > 0 && effectiveChars > maxLen) return null;
        return new DanmakuComment(parts.toArray(new Object[0]));
    }

    private static List<EmoteRange> parseEmoteRanges(String emotesTag, int msgLen) {
        if (emotesTag == null || emotesTag.isEmpty() || msgLen <= 0) return java.util.Collections.emptyList();
        List<EmoteRange> out = new ArrayList<>();
        String[] entries = emotesTag.split("/");
        for (String entry : entries) {
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon >= entry.length() - 1) continue;
            String id = entry.substring(0, colon).trim();
            if (id.isEmpty()) continue;
            String[] spans = entry.substring(colon + 1).split(",");
            for (String span : spans) {
                int dash = span.indexOf('-');
                if (dash <= 0 || dash >= span.length() - 1) continue;
                try {
                    int s = Integer.parseInt(span.substring(0, dash));
                    int e = Integer.parseInt(span.substring(dash + 1));
                    if (s >= 0 && e >= s && e < msgLen) {
                        out.add(new EmoteRange(id, s, e));
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (out.size() > 1) {
            out.sort((a, b) -> Integer.compare(a.start, b.start));
        }
        return out;
    }

    private boolean sendRaw(String line) {
        synchronized (writeLock) {
            BufferedWriter bw = writer;
            if (bw == null) return false;
            try {
                bw.write(line);
                bw.write("\r\n");
                bw.flush();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private void postSendResult(SendCallback cb, boolean ok, String error) {
        if (cb == null) return;
        main.post(() -> cb.onResult(ok, error));
    }
}
