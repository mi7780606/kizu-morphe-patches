package app.morphe.extension.twitch.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

public final class ChatHistory {
    private static final String TAG = "KizuChatHistory";

    public static final boolean ENABLED = true;
    private static final int MAX_PER_USER = 50;
    private static final int MAX_USERS = 1500;
    private static final int MAX_TOTAL = 15000;
    private static final long DUP_WINDOW_MS = 60_000;
    private static final int DUP_CHECK = 20;
    private static final long TAP_TIMEOUT_MS = 400;

    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^(?:午前|午後)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM|am|pm)?\\s*");
    private static final Pattern NAME_WITH_LOGIN =
            Pattern.compile("^(.+?)\\s*[(（]([A-Za-z0-9_]{1,25})[)）]$");
    private static final Pattern PLAIN_NAME = Pattern.compile("^[^\\s:：]{1,25}$");

    private static final class Entry {
        final long time; final String text;
        Entry(long time, String text) { this.time = time; this.text = text; }
    }
    private static final class User { final ArrayDeque<Entry> entries = new ArrayDeque<>(); }
    private static final class Parsed {
        final String key, display, body; final int nameStart, colon;
        Parsed(String key, String display, String body, int nameStart, int colon) {
            this.key = key; this.display = display; this.body = body;
            this.nameStart = nameStart; this.colon = colon;
        }
    }
    private static final class Hit {
        final Parsed p; final ClickableSpan span;
        Hit(Parsed p, ClickableSpan span) { this.p = p; this.span = span; }
    }

    private static final LinkedHashMap<String, User> USERS = new LinkedHashMap<>(256, 0.75f, true);
    private static int total;
    private static final LinkedHashMap<Integer, Boolean> SEEN =
            new LinkedHashMap<Integer, Boolean>(512, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> e) { return size() > 800; }
            };
    private static final Set<View> LISTS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Set<View> TAPPABLE = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static boolean broken;
    private static boolean announced;
    private static int toasts;
    private static int slop = -1;
    private static WeakReference<Context> lastContext = new WeakReference<>(null);
    private static Field listenerInfoField;
    private static Field touchListenerField;
    private static boolean reflectFailed;

    private ChatHistory() {}

    private static boolean on() {
        try { return KizuSettings.CHAT_HISTORY.get(); } catch (Throwable t) { return true; }
    }

    public static void attach(View list) {
        if (!ENABLED || broken || !(list instanceof ViewGroup)) return;
        try {
            if (!LISTS.add(list)) return;
            lastContext = new WeakReference<>(list.getContext());
            Recorder r = new Recorder((ViewGroup) list);
            list.addOnAttachStateChangeListener(r);
            if (list.isAttachedToWindow()) r.onViewAttachedToWindow(list);
            if (!announced && on()) {
                announced = true;
                debug(list.getContext(), "過去コメント: 記録を開始しました");
            }
        } catch (Throwable t) { fail("attach failed", t); }
    }

    private static final class Recorder
            implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final WeakReference<ViewGroup> ref;
        ViewTreeObserver vto;
        Recorder(ViewGroup list) { ref = new WeakReference<>(list); }

        @Override public void onViewAttachedToWindow(View v) {
            if (vto == null || !vto.isAlive()) { vto = v.getViewTreeObserver(); vto.addOnPreDrawListener(this); }
        }
        @Override public void onViewDetachedFromWindow(View v) {
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = null;
        }
        @Override public boolean onPreDraw() {
            if (broken || !on()) return true;
            ViewGroup list = ref.get();
            if (list == null) return true;
            try {
                for (int i = 0, n = list.getChildCount(); i < n; i++) {
                    TextView tv = rowText(list.getChildAt(i), 0);
                    if (tv == null) continue;
                    record(tv.getText());
                    install(tv);
                }
            } catch (Throwable t) { fail("record failed", t); }
            return true;
        }
    }

    private static boolean hasColon(CharSequence t) {
        return t != null && (TextUtils.indexOf(t, ':') > 0 || TextUtils.indexOf(t, '：') > 0);
    }
    private static TextView rowText(View v, int depth) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView) return hasColon(((TextView) v).getText()) ? (TextView) v : null;
        if (v instanceof ViewGroup && depth < 4) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { TextView tv = rowText(g.getChildAt(i), depth+1); if (tv!=null) return tv; }
        }
        return null;
    }
    private static void record(CharSequence cs) {
        if (cs == null || cs.length() == 0 || cs.length() > 2000) return;
        int id = System.identityHashCode(cs);
        if (SEEN.containsKey(id)) return;
        SEEN.put(id, Boolean.TRUE);
        Parsed p = parse(cs.toString());
        if (p == null || p.body.isEmpty()) return;
        User u = USERS.get(p.key);
        if (u == null) { u = new User(); USERS.put(p.key, u); }
        long now = System.currentTimeMillis();
        int checked = 0;
        for (Iterator<Entry> it = u.entries.descendingIterator(); it.hasNext() && checked < DUP_CHECK; checked++) {
            Entry e = it.next();
            if (now - e.time > DUP_WINDOW_MS) break;
            if (e.text.equals(p.body)) return;
        }
        u.entries.addLast(new Entry(now, p.body));
        total++;
        while (u.entries.size() > MAX_PER_USER) { u.entries.pollFirst(); total--; }
        while (USERS.size() > MAX_USERS || total > MAX_TOTAL) {
            Iterator<Map.Entry<String, User>> it = USERS.entrySet().iterator();
            if (!it.hasNext()) break;
            total -= it.next().getValue().entries.size();
            it.remove();
        }
    }
    private static boolean isSpace(char c) { return Character.isWhitespace(c) || c == '\u00A0' || c == '\u3000'; }
    private static int skip(String s, int i) {
        while (i < s.length()) { char c = s.charAt(i); if (c == '\uFFFC' || c == '\u200B' || isSpace(c)) i++; else break; }
        return i;
    }
    private static String clean(String s) {
        s = s.replace("\uFFFC", "").replace("\u200B", "");
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
    private static Parsed parse(String s) {
        int start = skip(s, 0);
        Matcher m = TIME_PREFIX.matcher(s);
        m.region(start, s.length());
        if (m.lookingAt()) start = skip(s, m.end());
        int colon = -1;
        for (int i = start; i < s.length() && i - start <= 60; i++) {
            char c = s.charAt(i);
            if ((c == ':' || c == '：') && (i + 1 >= s.length() || isSpace(s.charAt(i + 1)))) { colon = i; break; }
        }
        if (colon <= start) return null;
        String name = clean(s.substring(start, colon));
        String body = clean(s.substring(colon + 1));
        if (name.isEmpty()) return null;
        String key;
        Matcher n = NAME_WITH_LOGIN.matcher(name);
        if (n.matches()) key = n.group(2).toLowerCase(Locale.ROOT);
        else if (PLAIN_NAME.matcher(name).matches()) key = name.toLowerCase(Locale.ROOT);
        else return null;
        return new Parsed(key, name, body, start, colon);
    }
    private static View.OnTouchListener currentTouchListener(View v) {
        if (reflectFailed) return null;
        try {
            if (listenerInfoField == null) { listenerInfoField = View.class.getDeclaredField("mListenerInfo"); listenerInfoField.setAccessible(true); }
            Object info = listenerInfoField.get(v);
            if (info == null) return null;
            if (touchListenerField == null) { touchListenerField = info.getClass().getDeclaredField("mOnTouchListener"); touchListenerField.setAccessible(true); }
            return (View.OnTouchListener) touchListenerField.get(info);
        } catch (Throwable t) { reflectFailed = true; return null; }
    }
    private static void install(TextView tv) {
        View.OnTouchListener cur = currentTouchListener(tv);
        if (cur instanceof NameTap) return;
        if (reflectFailed && !TAPPABLE.add(tv)) return;
        if (slop < 0) slop = ViewConfiguration.get(tv.getContext()).getScaledTouchSlop();
        tv.setOnTouchListener(new NameTap(cur));
    }
    private static final class NameTap implements View.OnTouchListener {
        final View.OnTouchListener prev;
        float downX, downY; long downTime; Hit candidate;
        NameTap(View.OnTouchListener prev) { this.prev = prev; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            try { if (!broken && on() && v instanceof TextView && handle((TextView) v, e)) return true; }
            catch (Throwable t) { candidate = null; fail("touch failed", t); }
            return prev != null && prev.onTouch(v, e);
        }
        private boolean moved(MotionEvent e) { return Math.abs(e.getX()-downX) > slop || Math.abs(e.getY()-downY) > slop; }
        private boolean handle(TextView tv, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); downTime = e.getEventTime();
                    candidate = hitName(tv, downX, downY); return false;
                case MotionEvent.ACTION_MOVE:
                    if (candidate != null && moved(e)) candidate = null; return false;
                case MotionEvent.ACTION_UP: {
                    Hit h = candidate; candidate = null;
                    if (h == null || moved(e) || e.getEventTime() - downTime > TAP_TIMEOUT_MS) return false;
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { tv.onTouchEvent(cancel); } finally { cancel.recycle(); }
                    Activity a = findActivity(tv.getContext());
                    if (a != null) tv.post(() -> showHistory(a, h, tv));
                    return true;
                }
                default: candidate = null; return false;
            }
        }
    }
    private static Hit hitName(TextView tv, float x, float y) {
        Layout layout = tv.getLayout(); CharSequence text = tv.getText();
        if (layout == null || text == null) return null;
        Parsed p = parse(text.toString());
        if (p == null) {
            String s = text.toString().replace("\uFFFC", "");
            debug(tv.getContext(), "過去コメント: 名前を読み取れません「" + (s.length() > 40 ? s.substring(0, 40) : s) + "」");
            return null;
        }
        float lx = x - tv.getTotalPaddingLeft() + tv.getScrollX();
        float ly = y - tv.getTotalPaddingTop() + tv.getScrollY();
        int line = layout.getLineForVertical((int) ly);
        if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null;
        int off = layout.getOffsetForHorizontal(line, lx);
        if (off < p.nameStart || off > p.colon) return null;
        ClickableSpan span = null;
        if (text instanceof Spanned) {
            ClickableSpan[] spans = ((Spanned) text).getSpans(off, off, ClickableSpan.class);
            if (spans.length > 0) span = spans[0];
        }
        return new Hit(p, span);
    }
    private static void showHistory(Activity a, Hit h, TextView tv) {
        try {
            if (a.isFinishing()) return;
            float d = a.getResources().getDisplayMetrics().density;
            User u = USERS.get(h.p.key);
            int n = u == null ? 0 : u.entries.size();
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
            if (n == 0) { list.addView(line(a, d, "まだ記録されたコメントはありません")); }
            else { for (Entry e : u.entries) list.addView(line(a, d, f.format(new Date(e.time)) + " " + e.text)); }
            ScrollView sv = new ScrollView(a);
            sv.addView(list);
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert);
            b.setTitle(h.p.display + "（" + n + "件）");
            b.setView(sv);
            b.setNegativeButton("閉じる", null);
            if (h.span != null) {
                b.setPositiveButton("プロフィール", (dialog, which) -> { try { h.span.onClick(tv); } catch (Throwable t) { Log.e(TAG, "profile failed", t); } });
            }
            b.show();
        } catch (Throwable t) { Log.e(TAG, "show failed", t); debug(a, "過去コメント: 一覧を表示できませんでした"); }
    }
    private static TextView line(Context c, float d, String s) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(0xFFEFEFF1);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, (int)(4*d), 0, (int)(4*d));
        t.setTextIsSelectable(true);
        return t;
    }
    private static Activity findActivity(Context c) {
        while (c instanceof ContextWrapper) { if (c instanceof Activity) return (Activity) c; c = ((ContextWrapper) c).getBaseContext(); }
        return null;
    }
    private static void debug(Context c, String msg) {
        if (c == null || toasts >= 6) return;
        try { if (!KizuSettings.DEBUG_MESSAGES.get()) return; } catch (Throwable ignored) {}
        toasts++;
        try { Toast.makeText(c.getApplicationContext(), "Kizu " + msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    private static void fail(String msg, Throwable t) {
        if (!broken) debug(lastContext.get(), "過去コメント: エラーで停止しました（" + msg + "）");
        broken = true;
        Log.e(TAG, msg, t);
    }
}