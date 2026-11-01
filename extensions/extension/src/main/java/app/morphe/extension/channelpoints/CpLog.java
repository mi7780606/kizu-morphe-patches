package app.morphe.extension.channelpoints;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * チャンネルポイント・予想まわりの通信結果を端末に残す（原因調査用）。
 * 予想ボタンの画面と「追加機能」→「チャンネルポイントの記録」で見られる。
 */
public final class CpLog {
    private static final String TAG = "KizuCpLog";
    private static final String FILE = "kizu_cp_log.txt";
    private static final int MAX_LINES = 60;
    private static final int MAX_LINE = 400;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static volatile File file;
    private static boolean loaded;

    private CpLog() {}

    public static void init(Context context) {
        if (file == null && context != null) {
            file = new File(context.getApplicationContext().getFilesDir(), FILE);
        }
    }

    public static void add(String message) {
        if (message == null) return;
        String line = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(new Date())
                + " " + (message.length() > MAX_LINE ? message.substring(0, MAX_LINE) + "…" : message);
        Log.i(TAG, line);
        synchronized (LINES) {
            load();
            LINES.addFirst(line);
            while (LINES.size() > MAX_LINES) LINES.pollLast();
            save();
        }
    }

    /** 新しい順 */
    public static String read() {
        synchronized (LINES) {
            load();
            if (LINES.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    /** 一番新しい1行 */
    public static String latest() {
        synchronized (LINES) {
            load();
            return LINES.isEmpty() ? "" : LINES.peekFirst();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
            loaded = true;
            File f = file;
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static void load() {
        if (loaded) return;
        File f = file;
        if (f == null) return;
        loaded = true;
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            for (String l : new String(buf, 0, n, StandardCharsets.UTF_8).split("\n")) {
                if (!l.isEmpty() && LINES.size() < MAX_LINES) LINES.addLast(l);
            }
        } catch (Throwable ignored) {}
    }

    private static void save() {
        File f = file;
        if (f == null) return;
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }
}
