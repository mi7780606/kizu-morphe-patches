package app.morphe.extension;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.twitch.chat.ChatFlow;

/**
 * アプリが落ちたときの原因（例外とスタック）を端末に保存する。次回の修正用。
 * Kizu設定 →「追加機能」→「最後に落ちたときの記録」で見られる。
 * 保存したあとは元の処理（Android標準のクラッシュ処理）にそのまま渡す。
 */
public final class CrashLog {
    private static final String TAG = "KizuCrashLog";
    private static final String FILE = "kizu_crash_log.txt";
    private static final int MAX_BYTES = 64 * 1024;

    private static volatile boolean installed;
    private static volatile File file;

    private CrashLog() {}

    public static void install(Context context) {
        if (installed || context == null) return;
        synchronized (CrashLog.class) {
            if (installed) return;
            installed = true;
            file = new File(context.getApplicationContext().getFilesDir(), FILE);
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try { write(thread, error); } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(thread, error);
            });
        }
    }

    private static void write(Thread thread, Throwable error) throws Exception {
        File f = file;
        if (f == null) return;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(new Date()) + " ====");
        pw.println("thread: " + thread.getName());
        pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        Runtime rt = Runtime.getRuntime();
        pw.println("memory: used " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "MB / max " + (rt.maxMemory() >> 20) + "MB");
        try {
            pw.println("chat flow: level " + ChatFlow.level() + ", " + Math.round(ChatFlow.rate()) + " msg/s");
        } catch (Throwable ignored) {}
        try {
            String mem = MemoryWatch.summary();
            if (!mem.isEmpty()) pw.println("memory history (新しい順):\n" + mem.trim());
        } catch (Throwable ignored) {}
        error.printStackTrace(pw);
        pw.println();
        pw.flush();

        // 新しい記録を先頭に。古いものは上限を超えた分を捨てる
        String old = read();
        String all = sw + old;
        if (all.length() > MAX_BYTES) all = all.substring(0, MAX_BYTES);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(all.getBytes(StandardCharsets.UTF_8));
        }
        Log.e(TAG, "crash saved");
    }

    /** 保存されている記録（新しい順）。無ければ空文字 */
    public static String read() {
        File f = file;
        if (f == null || !f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_BYTES)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear() {
        File f = file;
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** 設定画面から使う。install 前でも読めるようにする */
    public static void ensureFile(Context context) {
        if (file == null && context != null) file = new File(context.getApplicationContext().getFilesDir(), FILE);
    }
}
