package app.morphe.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared lightweight background executors and cached auth-token reader for Kizu extension modules.
 * Core threads time out after 30s of idleness so background overhead stays near zero when unused.
 */
public final class KizuRuntime {
    private static final Object IO_LOCK = new Object();
    private static final Object SCHED_LOCK = new Object();

    private static volatile ThreadPoolExecutor ioExecutor;
    private static volatile ScheduledThreadPoolExecutor scheduler;
    private static volatile String cachedAuthPrefsName;

    private KizuRuntime() {}

    public static ThreadPoolExecutor ioExecutor() {
        ThreadPoolExecutor existing = ioExecutor;
        if (existing != null) return existing;
        synchronized (IO_LOCK) {
            if (ioExecutor == null) {
                AtomicInteger counter = new AtomicInteger();
                ThreadFactory factory = runnable -> {
                    Thread t = new Thread(() -> {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (Throwable ignored) {}
                        runnable.run();
                    }, "kizu-io-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                };
                ThreadPoolExecutor exec = new ThreadPoolExecutor(
                        4, 4, 30L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(), factory);
                exec.allowCoreThreadTimeOut(true);
                ioExecutor = exec;
            }
            return ioExecutor;
        }
    }

    public static ScheduledExecutorService scheduler() {
        ScheduledThreadPoolExecutor existing = scheduler;
        if (existing != null) return existing;
        synchronized (SCHED_LOCK) {
            if (scheduler == null) {
                ThreadFactory factory = runnable -> {
                    Thread t = new Thread(() -> {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (Throwable ignored) {}
                        runnable.run();
                    }, "kizu-sched");
                    t.setDaemon(true);
                    return t;
                };
                ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, factory);
                exec.setKeepAliveTime(30L, TimeUnit.SECONDS);
                exec.allowCoreThreadTimeOut(true);
                exec.setRemoveOnCancelPolicy(true);
                scheduler = exec;
            }
            return scheduler;
        }
    }

    /**
     * Reads Twitch's own authToken_v2 without repeatedly scanning shared_prefs/*.xml.
     * Once located, subsequent lookups hit that SharedPreferences file directly in O(1).
     */
    public static String readAuthToken(Context context) {
        if (context == null) return "";
        String known = cachedAuthPrefsName;
        if (known != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(known, Context.MODE_PRIVATE);
                String token = prefs.getString("authToken_v2", null);
                if (token != null) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) return trimmed;
                }
            } catch (Throwable ignored) {}
        }

        File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = dir.listFiles();
        if (files == null) return "";
        for (File file : files) {
            if (file == null) continue;
            String fileName = file.getName();
            if (!fileName.endsWith(".xml")) continue;
            String name = fileName.substring(0, fileName.length() - 4);
            try {
                SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                String token = prefs.getString("authToken_v2", null);
                if (token != null) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        cachedAuthPrefsName = name;
                        return trimmed;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }
}
