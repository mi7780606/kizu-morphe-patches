package app.morphe.extension.twitch.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.bakwudo.uyu.extension.danmaku.ChatMessages;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * 名前タップで過去コメント。
 *
 * 記録: 受信データ（弾幕と同じ MessagesReceivedEvent のフック）から、画面に出たかどうかに関係なく
 * 全コメントを送信者のログイン名で記録する。弾幕パッチが無いときだけ、画面の文字からの記録に切り替える。
 * タップ: チャット欄の各行に軽いタッチ判定を付け、名前部分のタップで一覧を出す。
 */
public final class ChatHistory {
    private static final String TAG = "KizuChatHistory";

    public static final boolean ENABLED = true;
    private static final int MAX_PER_USER = 100;
    private static final int MAX_USERS = 3000;
    private static final int MAX_TOTAL = 20000;
    private static final int MAX_SEEN_IDS = 5000;
    private static final int MAX_TEXT = 500;
    private static final long TAP_TIMEOUT_MS = 400;

    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^(?:午前|午後)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM|am|pm)?\\s*");
    private static final Pattern NAME_WITH_LOGIN =
            Pattern.compile("^(.+?)\\s*[(（]([A-Za-z0-9_]{1,25})[)）]$");
    private static final Pattern PLAIN_NAME = Pattern.compile("^[^\\s:：]{1,25}$");

    private static final class Entry {
        final long time; final String text; final String channel;
        Entry(long time, String text, String channel) { this.time = time; this.text = text; this.channel = channel; }
    }
    private static final class User {
        String login; String display;
        final ArrayDeque<Entry> entries = new ArrayDeque<>();
    }
    private static final class Parsed {
        final String login, display, body; final int nameStart, colon;
        Parsed(String login, String display, String body, int nameStart, int colon) {
            this.login = login; this.display = display; this.body = body;
            this.nameStart = nameStart; this.colon = colon;
        }
    }
    private static final class Hit {
        final Parsed p; final ClickableSpan span;
        Hit(Parsed p, ClickableSpan span) { this.p = p; this.span = span; }
    }

    private static final Object LOCK = new Object();
    /** ログイン名（小文字）→ ユーザー。古く使われていない人から消す */
    private static final LinkedHashMap<String, User> USERS = new LinkedHashMap<>(512, 0.75f, true);
    /** 表示名（小文字）→ ログイン名。日本語の表示名から引くため */
    private static final HashMap<String, String> BY_DISPLAY = new HashMap<>();
    private static final LinkedHashMap<String, Boolean> SEEN_IDS =
            new LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MAX_SEEN_IDS; }
            };
    private static int total;
    private static volatile String currentChannel;
    /** 受信データから記録できているか。false のあいだは画面の文字から記録する */
    private static volatile boolean dataHookAlive;

    private static final Set<View> LISTS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    /** 行の TextView → 最後に見た文字列。文字が変わった（再利用された）行だけ処理する */
    private static final Map<TextView, CharSequence> LAST_TEXT = new WeakHashMap<>();
    private static boolean broken;
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

    // ===================== 記録（受信データ） =====================

    /** DanmakuPatch.onMessagesReceived から呼ばれる（チャット受信スレッド） */
    public static void onMessages(String channelId, List<?> messages, boolean fromHistory) {
        if (!ENABLED || messages == null || messages.isEmpty() || !on()) return;
        try {
            if (channelId != null) currentChannel = channelId;
            long now = System.currentTimeMillis();
            ArrayList<Object[]> rows = new ArrayList<>(messages.size());
            for (Object m : messages) {
                Object info = ChatMessages.messageInfo(m);
                if (info == null) continue;
                Object ui = ChatMessages.userInfo(info);
                if (ui == null) continue;
                String login = ChatMessages.userName(ui);
                if (login == null || login.isEmpty()) continue;
                String body = bodyOf(info);
                if (body == null || body.isEmpty()) continue;
                Long ts = ChatMessages.timestamp(info);
                long time = ts != null && ts > 0 ? ts : now;
                if (time < 100_000_000_000L) time *= 1000; // 秒で入っている場合
                if (time > now + 60_000) time = now;
                rows.add(new Object[]{ChatMessages.messageId(m), login, ChatMessages.displayName(ui), body, time});
            }
            if (rows.isEmpty()) return;
            dataHookAlive = true;
            synchronized (LOCK) {
                for (Object[] r : rows) {
                    String id = (String) r[0];
                    if (id != null && SEEN_IDS.put(id, Boolean.TRUE) != null) continue;
                    add((String) r[1], (String) r[2], (String) r[3], (Long) r[4], channelId);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "record failed", t);
        }
    }

    private static String bodyOf(Object info) {
        List<?> tokens = ChatMessages.tokens(info);
        if (tokens == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Object token : tokens) {
            String s = ChatMessages.textTokenText(token);
            if (s == null) s = ChatMessages.mentionTokenText(token);
            if (s == null) s = ChatMessages.urlTokenUrl(token);
            if (s == null && ChatMessages.emoteTokenId(token) != null) {
                String name = ChatMessages.emoteTokenText(token);
                s = "[" + (name == null || name.isEmpty() ? "スタンプ" : name.trim()) + "]";
            }
            if (s == null) {
                String prefix = ChatMessages.bitsTokenPrefix(token);
                if (prefix != null) s = prefix + ChatMessages.bitsTokenAmount(token);
            }
            if (s != null) sb.append(s);
            if (sb.length() > MAX_TEXT) break;
        }
        String out = sb.toString().trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) + "…" : out;
    }

    /** LOCK を持った状態で呼ぶ */
    private static void add(String login, String display, String body, long time, String channel) {
        String key = login.toLowerCase(Locale.ROOT);
        User u = USERS.get(key);
        if (u == null) { u = new User(); USERS.put(key, u); }
        u.login = key;
        if (display != null && !display.isEmpty()) {
            if (u.display != null && !u.display.equalsIgnoreCase(display)) BY_DISPLAY.remove(u.display.toLowerCase(Locale.ROOT));
            u.display = display;
            BY_DISPLAY.put(display.toLowerCase(Locale.ROOT), key);
        }
        u.entries.addLast(new Entry(time, body, channel));
        total++;
        while (u.entries.size() > MAX_PER_USER) { u.entries.pollFirst(); total--; }
        while (USERS.size() > MAX_USERS || total > MAX_TOTAL) {
            Iterator<Map.Entry<String, User>> it = USERS.entrySet().iterator();
            if (!it.hasNext()) break;
            User old = it.next().getValue();
            total -= old.entries.size();
            if (old.display != null) BY_DISPLAY.remove(old.display.toLowerCase(Locale.ROOT));
            it.remove();
        }
    }

    /** メモリ確認用: "ユーザー数/件数" */
    public static String stats() {
        synchronized (LOCK) { return USERS.size() + "人/" + total + "件"; }
    }

    /** メモリが足りないときに古い人から半分捨てる */
    public static void trimForMemory() {
        synchronized (LOCK) {
            int target = USERS.size() / 2;
            Iterator<Map.Entry<String, User>> it = USERS.entrySet().iterator();
            while (USERS.size() > target && it.hasNext()) {
                User old = it.next().getValue();
                total -= old.entries.size();
                if (old.display != null) BY_DISPLAY.remove(old.display.toLowerCase(Locale.ROOT));
                it.remove();
            }
            SEEN_IDS.clear();
        }
    }

    // ===================== チャット欄への取り付け =====================

    public static void attach(View list) {
        if (!ENABLED || broken || !(list instanceof ViewGroup)) return;
        try {
            if (!LISTS.add(list)) return;
            lastContext = new WeakReference<>(list.getContext());
            Recorder r = new Recorder((ViewGroup) list);
            list.addOnAttachStateChangeListener(r);
            if (list.isAttachedToWindow()) r.onViewAttachedToWindow(list);
        } catch (Throwable t) { fail("attach failed", t); }
    }

    /** 毎フレーム呼ばれるが、文字が変わった行（新しく表示・再利用された行）だけを処理する */
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
                    CharSequence text = tv.getText();
                    if (LAST_TEXT.get(tv) == text) continue; // 変化なし → 何もしない
                    LAST_TEXT.put(tv, text);
                    install(tv);
                    if (!dataHookAlive) recordFromView(text);
                }
            } catch (Throwable t) { fail("row failed", t); }
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
            for (int i = 0; i < g.getChildCount(); i++) { TextView tv = rowText(g.getChildAt(i), depth + 1); if (tv != null) return tv; }
        }
        return null;
    }

    /** 弾幕パッチが無いときの予備: 画面の文字から記録 */
    private static void recordFromView(CharSequence cs) {
        if (cs == null || cs.length() == 0 || cs.length() > 2000) return;
        Parsed p = parse(cs.toString());
        if (p == null || p.body.isEmpty()) return;
        synchronized (LOCK) {
            User u = USERS.get(p.login);
            if (u != null) {
                Entry last = u.entries.peekLast();
                if (last != null && last.text.equals(p.body) && System.currentTimeMillis() - last.time < 60_000) return;
            }
            add(p.login, p.display, p.body, System.currentTimeMillis(), currentChannel);
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
        String login, display;
        Matcher n = NAME_WITH_LOGIN.matcher(name);
        if (n.matches()) { display = n.group(1).trim(); login = n.group(2).toLowerCase(Locale.ROOT); }
        else if (PLAIN_NAME.matcher(name).matches()) { display = name; login = name.toLowerCase(Locale.ROOT); }
        else return null;
        return new Parsed(login, display, body, start, colon);
    }

    // ===================== タップ =====================

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
        if (slop < 0) slop = ViewConfiguration.get(tv.getContext()).getScaledTouchSlop();
        tv.setOnTouchListener(new NameTap(reflectFailed ? null : cur));
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
        private boolean moved(MotionEvent e) { return Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop; }
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
        if (p == null) return null;
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

    // ===================== 一覧 =====================

    /** 表示用のスナップショット（新しい順） */
    private static final class Snapshot {
        String login; String display; final ArrayList<Entry> entries = new ArrayList<>();
    }
    private static Snapshot snapshot(Parsed p) {
        Snapshot s = new Snapshot();
        synchronized (LOCK) {
            User u = USERS.get(p.login);
            if (u == null) {
                String login = BY_DISPLAY.get(p.display.toLowerCase(Locale.ROOT));
                if (login != null) u = USERS.get(login);
            }
            if (u != null) {
                s.login = u.login; s.display = u.display;
                Iterator<Entry> it = u.entries.descendingIterator();
                while (it.hasNext()) s.entries.add(it.next());
            }
        }
        if (s.display == null) s.display = p.display;
        return s;
    }

    private static void showHistory(Activity a, Hit h, TextView tv) {
        try {
            if (a.isFinishing()) return;
            float d = a.getResources().getDisplayMetrics().density;
            Snapshot snap = snapshot(h.p);
            String channel = currentChannel;
            boolean hasOther = false;
            for (Entry e : snap.entries) if (channel != null && e.channel != null && !channel.equals(e.channel)) { hasOther = true; break; }

            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * d);
            root.setPadding(pad, (int) (4 * d), pad, 0);

            EditText search = new EditText(a);
            search.setSingleLine(true);
            search.setHint("コメント内を検索");
            search.setTextColor(0xFFEFEFF1);
            search.setHintTextColor(0x99EFEFF1);
            search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            root.addView(search);

            CheckBox allChannels = new CheckBox(a);
            allChannels.setText("ほかの配信でのコメントも表示");
            allChannels.setTextColor(0xFFEFEFF1);
            allChannels.setVisibility(hasOther ? View.VISIBLE : View.GONE);
            root.addView(allChannels);

            TextView count = new TextView(a);
            count.setTextColor(0xFFADADB8);
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            count.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
            root.addView(count);

            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            ScrollView sv = new ScrollView(a);
            sv.addView(list);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f));
            root.addView(sv, lp);

            Runnable refresh = () -> fill(a, d, list, count, snap, channel,
                    allChannels.isChecked(), search.getText().toString());
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { refresh.run(); }
            });
            allChannels.setOnCheckedChangeListener((b, checked) -> refresh.run());
            refresh.run();

            String title = snap.display;
            if (snap.login != null && !snap.login.equalsIgnoreCase(snap.display)) title += "（" + snap.login + "）";
            AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert);
            b.setTitle(title);
            b.setView(root);
            b.setNegativeButton("閉じる", null);
            if (h.span != null) {
                b.setPositiveButton("プロフィール", (dialog, which) -> {
                    try { h.span.onClick(tv); } catch (Throwable t) { Log.e(TAG, "profile failed", t); }
                });
            }
            b.show();
        } catch (Throwable t) { Log.e(TAG, "show failed", t); debug(a, "過去コメント: 一覧を表示できませんでした"); }
    }

    private static void fill(Context c, float d, LinearLayout list, TextView count, Snapshot snap,
            String channel, boolean all, String query) {
        list.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        SimpleDateFormat f = new SimpleDateFormat("M/d HH:mm:ss", Locale.JAPAN);
        int shown = 0, inChannel = 0;
        for (Entry e : snap.entries) {
            boolean here = channel == null || e.channel == null || channel.equals(e.channel);
            if (here) inChannel++;
            if (!all && !here) continue;
            if (!q.isEmpty() && !e.text.toLowerCase(Locale.ROOT).contains(q)) continue;
            list.addView(line(c, d, f.format(new Date(e.time)), e.text, here));
            shown++;
        }
        if (snap.entries.isEmpty()) {
            list.addView(line(c, d, null, "まだ記録されたコメントはありません", true));
            count.setText("記録はアプリを開いているあいだだけ残ります");
        } else if (shown == 0) {
            list.addView(line(c, d, null, "一致するコメントはありません", true));
            count.setText("この配信 " + inChannel + "件 / 全体 " + snap.entries.size() + "件");
        } else {
            count.setText("新しい順・" + shown + "件表示（この配信 " + inChannel + "件 / 全体 " + snap.entries.size() + "件）");
        }
    }

    private static TextView line(Context c, float d, String time, String text, boolean here) {
        TextView t = new TextView(c);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (time != null) {
            sb.append(time).append("  ");
            sb.setSpan(new ForegroundColorSpan(0xFFADADB8), 0, time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append(text);
        t.setText(sb);
        t.setTextColor(here ? 0xFFEFEFF1 : 0xFF9A9AA5);
        if (!here) t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
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
