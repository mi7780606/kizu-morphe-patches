package app.morphe.extension.twitch.chat;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class ChatPacer {
    private static final String TAG = "KizuChatPacer";

    private static final int HISTORY_BATCH = 30;
    private static final long MAX_DELAY_MS = 1000;
    private static final long MIN_SPACING_MS = 16;
    private static final float OVERLOAD_ENTER_RATE = 40f;
    private static final float OVERLOAD_EXIT_RATE = 25f;
    private static final long OVERLOAD_HOLD_MS = 3000;
    private static final int OVERLOAD_QUEUE = 80;
    private static final long LATE_MS = 200;
    private static final int LATE_LIMIT = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final Map<Class<?>, Method> METHODS = new HashMap<>();
    private static boolean bypass;
    private static boolean broken;

    private ChatPacer() {}

    private static final class Item {
        final Object obj; final long due;
        Item(Object obj, long due) { this.obj = obj; this.due = due; }
    }

    private static final class State {
        final WeakReference<Object> adapter;
        final ArrayDeque<Item> queue = new ArrayDeque<>();
        final Runnable tick;
        long lastBatchAt; float avgGapMs = 500f; long lastDue;
        float rate; long windowStart; int windowCount;
        boolean overload; long overloadUntil; int lateCount;
        boolean scheduled; long scheduledAt;

        State(Object a) {
            adapter = new WeakReference<>(a);
            final State self = this;
            tick = new Runnable() { @Override public void run() { onTick(self); } };
        }
    }

    public static boolean intercept(Object adapter, List list) {
        if (bypass || broken || adapter == null || list == null || list.isEmpty()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) return false;

        try {
            if (!KizuSettings.SMOOTH_CHAT.get()) {
                State old = STATES.get(adapter);
                if (old != null && !old.queue.isEmpty()) flush(old);
                return false;
            }
        } catch (Throwable ignored) {}

        long now = SystemClock.uptimeMillis();
        State s;
        try {
            s = STATES.get(adapter);
            if (s == null) { s = new State(adapter); STATES.put(adapter, s); }
            updateRate(s, now, list.size());
            updateOverload(s, now);
            if (list.size() >= HISTORY_BATCH || s.overload) { flush(s); return false; }
            learnGap(s, now);
        } catch (Throwable t) { fail("intercept failed", t); return false; }

        try {
            enqueue(s, list, now);
            if (s.queue.size() > OVERLOAD_QUEUE) { enterOverload(s, now); flush(s); }
            else { ensureTick(s, now); }
        } catch (Throwable t) { fail("enqueue failed", t); flush(s); }
        return true;
    }

    private static void updateRate(State s, long now, int count) {
        if (s.windowStart == 0 || now - s.windowStart > 2000) {
            if (s.windowStart != 0) s.rate = 0f;
            s.windowStart = now; s.windowCount = count; return;
        }
        s.windowCount += count;
        long span = now - s.windowStart;
        if (span >= 500) {
            float inst = s.windowCount * 1000f / span;
            s.rate = s.rate * 0.5f + inst * 0.5f;
            s.windowStart = now; s.windowCount = 0;
        }
    }

    private static void updateOverload(State s, long now) {
        if (s.overload) {
            if (s.rate > OVERLOAD_EXIT_RATE) s.overloadUntil = now + OVERLOAD_HOLD_MS;
            else if (now >= s.overloadUntil) {
                s.overload = false; s.lateCount = 0;
                ChatSmoothScroll.setSuspended(false);
                Log.i(TAG, "overload off");
            }
        } else if (s.rate >= OVERLOAD_ENTER_RATE) { enterOverload(s, now); }
    }

    private static void enterOverload(State s, long now) {
        if (!s.overload) Log.i(TAG, "overload on (rate=" + s.rate + ")");
        s.overload = true; s.overloadUntil = now + OVERLOAD_HOLD_MS;
        ChatSmoothScroll.setSuspended(true);
    }

    private static void learnGap(State s, long now) {
        if (s.lastBatchAt != 0) {
            long gap = now - s.lastBatchAt;
            if (gap < 3000) { float g = Math.max(50f, Math.min(2000f, gap)); s.avgGapMs = s.avgGapMs * 0.7f + g * 0.3f; }
        }
        s.lastBatchAt = now;
    }

    private static void enqueue(State s, List list, long now) {
        int n = list.size();
        long spacing = Math.max(MIN_SPACING_MS, (long) (s.avgGapMs / n));
        boolean immediate = s.queue.isEmpty() && s.lastDue <= now;
        long base = immediate ? now : Math.max(now, s.lastDue + spacing);
        long limit = now + MAX_DELAY_MS;
        long due = base;
        for (int i = 0; i < n; i++) {
            due = Math.min(base + spacing * i, limit);
            s.queue.addLast(new Item(list.get(i), due));
        }
        s.lastDue = due;
    }

    private static void onTick(State s) {
        s.scheduled = false;
        long now = SystemClock.uptimeMillis();
        try {
            if (now - s.scheduledAt > LATE_MS) {
                if (++s.lateCount >= LATE_LIMIT) { enterOverload(s, now); flush(s); return; }
            } else if (s.lateCount > 0) { s.lateCount--; }
            ArrayList out = new ArrayList();
            while (!s.queue.isEmpty() && s.queue.peekFirst().due <= now) out.add(s.queue.pollFirst().obj);
            if (!out.isEmpty()) deliver(s, out);
            ensureTick(s, now);
        } catch (Throwable t) { fail("tick failed", t); flush(s); }
    }

    private static void ensureTick(State s, long now) {
        if (s.scheduled || s.queue.isEmpty()) return;
        long delay = Math.max(0L, s.queue.peekFirst().due - now);
        s.scheduled = true; s.scheduledAt = now + delay;
        MAIN.postDelayed(s.tick, delay);
    }

    private static void flush(State s) {
        MAIN.removeCallbacks(s.tick); s.scheduled = false;
        if (s.queue.isEmpty()) return;
        ArrayList out = new ArrayList(s.queue.size());
        for (Item it : s.queue) out.add(it.obj);
        s.queue.clear(); s.lastDue = 0;
        deliver(s, out);
    }

    private static void deliver(State s, ArrayList out) {
        Object a = s.adapter.get();
        if (a == null) { s.queue.clear(); return; }
        try {
            Method m = METHODS.get(a.getClass());
            if (m == null) { m = a.getClass().getMethod("w", List.class); METHODS.put(a.getClass(), m); }
            bypass = true;
            try { m.invoke(a, out); } finally { bypass = false; }
            ChatSmoothScroll.markDelivered();
        } catch (Throwable t) { fail("deliver failed", t); s.queue.clear(); }
    }

    private static void fail(String msg, Throwable t) { broken = true; Log.e(TAG, msg, t); }
}