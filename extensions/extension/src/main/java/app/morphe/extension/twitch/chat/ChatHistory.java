package app.morphe.extension.twitch.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.danmaku.ChatMessages;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * 名前タップで過去コメント。
 * 毎フレーム OnPreDraw 走査を廃止し、RecyclerView の子ビュー追加フック＋タップ時の遅延解析で
 * 描画コストをゼロに抑えつつ、Twitch 純正ボトムシート＋ListView（ViewHolder再利用）で表示する。
 */
public final class ChatHistory {
    private static final String TAG = "KizuChatHistory";

    public static final boolean ENABLED = true;
    private static final int MAX_PER_USER = 100;
    private static final int MAX_USERS = 3000;
    private static final int MAX_TOTAL = 5000;
    private static final int MAX_SEEN_IDS = 5000;
    private static final int MAX_TEXT = 500;
    private static final long TAP_TIMEOUT_MS = 400;

    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^(?:午前|午後)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM|am|pm)?\\s*");
    private static final Pattern NAME_WITH_LOGIN =
            Pattern.compile("^(.+?)\\s*[(（]([A-Za-z0-9_]{1,25})[)）]$");
    private static final Pattern PLAIN_NAME = Pattern.compile("^[^\\s:：]{1,25}$");

    private static final class Entry {
        final long time;
        final String text;
        final String channel;

        Entry(long time, String text, String channel) {
            this.time = time;
            this.text = text;
            this.channel = channel;
        }
    }

    private static final class User {
        String login;
        String display;
        final ArrayDeque<Entry> entries = new ArrayDeque<>();
    }

    private static final class Parsed {
        final String login, display, body;
        final int nameStart, colon;

        Parsed(String login, String display, String body, int nameStart, int colon) {
            this.login = login;
            this.display = display;
            this.body = body;
            this.nameStart = nameStart;
            this.colon = colon;
        }
    }

    private static final class Hit {
        final Parsed p;
        final ClickableSpan span;

        Hit(Parsed p, ClickableSpan span) {
            this.p = p;
            this.span = span;
        }
    }

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, User> USERS = new LinkedHashMap<>(512, 0.75f, true);
    private static final HashMap<String, String> BY_DISPLAY = new HashMap<>();
    private static final LinkedHashMap<String, Boolean> SEEN_IDS =
            new LinkedHashMap<>(1024, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > MAX_SEEN_IDS;
                }
            };
    private static int total;
    private static volatile String currentChannel;
    private static volatile boolean dataHookAlive;

    private static final Set<View> LISTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<TextView, Integer> LAST_TEXT = new WeakHashMap<>();
    private static boolean broken;
    private static int toasts;
    private static int slop = -1;
    private static WeakReference<Context> lastContext = new WeakReference<>(null);
    private static Field listenerInfoField;
    private static Field touchListenerField;
    private static Field hierarchyListenerField;
    private static boolean reflectFailed;
    private static boolean hierarchyReflectFailed;

    private ChatHistory() {}

    private static boolean on() {
        try { return KizuSettings.CHAT_HISTORY.get(); } catch (Throwable t) { return true; }
    }

    // ===================== 記録（受信データ） =====================

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
                if (time < 100_000_000_000L) time *= 1000;
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

    private static void add(String login, String display, String body, long time, String channel) {
        String key = login.toLowerCase(Locale.ROOT);
        User u = USERS.get(key);
        if (u == null) { u = new User(); USERS.put(key, u); }
        u.login = key;
        if (display != null && !display.isEmpty()) {
            if (u.display != null && !u.display.equalsIgnoreCase(display)) {
                BY_DISPLAY.remove(u.display.toLowerCase(Locale.ROOT));
            }
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

    // ===================== チャット欄への取り付け =====================

    public static void attach(View list) {
        if (!ENABLED || broken || !(list instanceof ViewGroup group)) return;
        try {
            if (!LISTS.add(list)) return;
            lastContext = new WeakReference<>(list.getContext());
            for (int i = 0; i < group.getChildCount(); i++) {
                bindRowView(group.getChildAt(i));
            }
            ViewGroup.OnHierarchyChangeListener prev = currentHierarchyListener(group);
            if (!(prev instanceof RowBinder)) {
                group.setOnHierarchyChangeListener(new RowBinder(hierarchyReflectFailed ? null : prev));
            }
            group.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (broken || !on() || !(v instanceof ViewGroup g)) return;
                for (int i = 0; i < g.getChildCount(); i++) {
                    bindRowView(g.getChildAt(i));
                }
            });
        } catch (Throwable t) {
            fail("attach failed", t);
        }
    }

    private static ViewGroup.OnHierarchyChangeListener currentHierarchyListener(ViewGroup g) {
        if (hierarchyReflectFailed) return null;
        try {
            if (hierarchyListenerField == null) {
                hierarchyListenerField = ViewGroup.class.getDeclaredField("mOnHierarchyChangeListener");
                hierarchyListenerField.setAccessible(true);
            }
            return (ViewGroup.OnHierarchyChangeListener) hierarchyListenerField.get(g);
        } catch (Throwable t) {
            hierarchyReflectFailed = true;
            return null;
        }
    }

    private static final class RowBinder implements ViewGroup.OnHierarchyChangeListener {
        private final ViewGroup.OnHierarchyChangeListener prev;

        RowBinder(ViewGroup.OnHierarchyChangeListener prev) {
            this.prev = prev;
        }

        @Override
        public void onChildViewAdded(View parent, View child) {
            if (prev != null) prev.onChildViewAdded(parent, child);
            if (!broken && on()) bindRowView(child);
        }

        @Override
        public void onChildViewRemoved(View parent, View child) {
            if (prev != null) prev.onChildViewRemoved(parent, child);
        }
    }

    private static void bindRowView(View child) {
        try {
            TextView tv = rowText(child, 0);
            if (tv == null) return;
            install(tv);
            if (!dataHookAlive) {
                CharSequence text = tv.getText();
                Integer h = System.identityHashCode(text);
                if (!h.equals(LAST_TEXT.get(tv))) {
                    LAST_TEXT.put(tv, h);
                    recordFromView(text);
                }
            }
        } catch (Throwable t) {
            fail("row failed", t);
        }
    }

    private static boolean hasColon(CharSequence t) {
        return t != null && (TextUtils.indexOf(t, ':') > 0 || TextUtils.indexOf(t, '：') > 0);
    }

    private static TextView rowText(View v, int depth) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView tv) return hasColon(tv.getText()) ? tv : null;
        if (v instanceof ViewGroup g && depth < 4) {
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView tv = rowText(g.getChildAt(i), depth + 1);
                if (tv != null) return tv;
            }
        }
        return null;
    }

    private static void recordFromView(CharSequence cs) {
        if (cs == null || cs.length() == 0 || cs.length() > 2000) return;
        Parsed p = parse(cs.toString());
        if (p == null || p.body.isEmpty()) return;
        synchronized (LOCK) {
            User u = USERS.get(p.login);
            if (u != null) {
                Entry last = u.entries.peekLast();
                if (last != null && last.text.equals(p.body)
                        && System.currentTimeMillis() - last.time < 60_000) return;
            }
            add(p.login, p.display, p.body, System.currentTimeMillis(), currentChannel);
        }
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u3000';
    }

    private static int skip(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\uFFFC' || c == '\u200B' || isSpace(c)) i++;
            else break;
        }
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
            if ((c == ':' || c == '：') && (i + 1 >= s.length() || isSpace(s.charAt(i + 1)))) {
                colon = i;
                break;
            }
        }
        if (colon <= start) return null;
        String name = clean(s.substring(start, colon));
        String body = clean(s.substring(colon + 1));
        if (name.isEmpty()) return null;
        String login, display;
        Matcher n = NAME_WITH_LOGIN.matcher(name);
        if (n.matches()) {
            display = n.group(1).trim();
            login = n.group(2).toLowerCase(Locale.ROOT);
        } else if (PLAIN_NAME.matcher(name).matches()) {
            display = name;
            login = name.toLowerCase(Locale.ROOT);
        } else {
            return null;
        }
        return new Parsed(login, display, body, start, colon);
    }

    // ===================== タップ =====================

    private static View.OnTouchListener currentTouchListener(View v) {
        if (reflectFailed) return null;
        try {
            if (listenerInfoField == null) {
                listenerInfoField = View.class.getDeclaredField("mListenerInfo");
                listenerInfoField.setAccessible(true);
            }
            Object info = listenerInfoField.get(v);
            if (info == null) return null;
            if (touchListenerField == null) {
                touchListenerField = info.getClass().getDeclaredField("mOnTouchListener");
                touchListenerField.setAccessible(true);
            }
            return (View.OnTouchListener) touchListenerField.get(info);
        } catch (Throwable t) {
            reflectFailed = true;
            return null;
        }
    }

    private static void install(TextView tv) {
        View.OnTouchListener cur = currentTouchListener(tv);
        if (cur instanceof NameTap) return;
        if (slop < 0) slop = ViewConfiguration.get(tv.getContext()).getScaledTouchSlop();
        tv.setOnTouchListener(new NameTap(reflectFailed ? null : cur));
    }

    private static final class NameTap implements View.OnTouchListener {
        final View.OnTouchListener prev;
        float downX, downY;
        long downTime;
        Hit candidate;

        NameTap(View.OnTouchListener prev) {
            this.prev = prev;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                if (!broken && on() && v instanceof TextView tv && handle(tv, e)) return true;
            } catch (Throwable t) {
                candidate = null;
                fail("touch failed", t);
            }
            return prev != null && prev.onTouch(v, e);
        }

        private boolean moved(MotionEvent e) {
            return Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop;
        }

        private boolean handle(TextView tv, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = e.getEventTime();
                    candidate = hitName(tv, downX, downY);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (candidate != null && moved(e)) candidate = null;
                    return false;
                case MotionEvent.ACTION_UP: {
                    Hit h = candidate;
                    candidate = null;
                    if (h == null || moved(e) || e.getEventTime() - downTime > TAP_TIMEOUT_MS) {
                        return false;
                    }
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { tv.onTouchEvent(cancel); } finally { cancel.recycle(); }
                    Activity a = findActivity(tv.getContext());
                    if (a != null) tv.post(() -> showHistory(a, h, tv));
                    return true;
                }
                default:
                    candidate = null;
                    return false;
            }
        }
    }

    private static Hit hitName(TextView tv, float x, float y) {
        Layout layout = tv.getLayout();
        CharSequence text = tv.getText();
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
        if (text instanceof Spanned spanned) {
            ClickableSpan[] spans = spanned.getSpans(off, off, ClickableSpan.class);
            if (spans.length > 0) span = spans[0];
        }
        return new Hit(p, span);
    }

    // ===================== 一覧（Twitch 純正ボトムシート＋ListView） =====================

    private static final class Snapshot {
        String login;
        String display;
        final ArrayList<Entry> entries = new ArrayList<>();
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
                s.login = u.login;
                s.display = u.display;
                Iterator<Entry> it = u.entries.descendingIterator();
                while (it.hasNext()) s.entries.add(it.next());
            }
        }
        if (s.display == null) s.display = p.display;
        return s;
    }

    private static void showHistory(Activity a, Hit h, TextView tv) {
        try {
            if (a.isFinishing() || a.isDestroyed()) return;
            float d = a.getResources().getDisplayMetrics().density;
            Snapshot snap = snapshot(h.p);
            String channel = currentChannel;
            boolean hasOther = false;
            for (Entry e : snap.entries) {
                if (channel != null && e.channel != null && !channel.equals(e.channel)) {
                    hasOther = true;
                    break;
                }
            }

            Dialog dlg = TwitchUi.createBottomSheet(a);
            int pad = TwitchUi.dp(a, 16f);

            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, 0, pad, TwitchUi.dp(a, 12f));
            root.addView(TwitchUi.dragHandle(a));

            // ヘッダー
            LinearLayout header = new LinearLayout(a);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, TwitchUi.dp(a, 2f), 0, TwitchUi.dp(a, 10f));

            LinearLayout titleCol = new LinearLayout(a);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            TextView displayTv = new TextView(a);
            displayTv.setText(snap.display);
            displayTv.setTextColor(SettingsUi.primaryTextColor(a));
            displayTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            displayTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleCol.addView(displayTv);
            if (snap.login != null && !snap.login.equalsIgnoreCase(snap.display)) {
                TextView loginTv = new TextView(a);
                loginTv.setText("@" + snap.login);
                loginTv.setTextColor(SettingsUi.secondaryTextColor(a));
                loginTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                titleCol.addView(loginTv);
            }
            header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

            if (h.span != null) {
                TextView profileBtn = TwitchUi.createPrimaryButton(a, "プロフィール");
                profileBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                profileBtn.setPadding(TwitchUi.dp(a, 12f), TwitchUi.dp(a, 6f),
                        TwitchUi.dp(a, 12f), TwitchUi.dp(a, 6f));
                profileBtn.setOnClickListener(v -> {
                    dlg.dismiss();
                    try { h.span.onClick(tv); } catch (Throwable t) { Log.e(TAG, "profile failed", t); }
                });
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                plp.rightMargin = TwitchUi.dp(a, 6f);
                header.addView(profileBtn, plp);
            }
            header.addView(TwitchUi.createCloseButton(a, dlg::dismiss));
            root.addView(header);

            // 検索バー
            FrameLayout searchBox = new FrameLayout(a);
            EditText search = TwitchUi.createPillInput(a, "コメント内を検索");
            search.setSingleLine(true);
            search.setPadding(TwitchUi.dp(a, 12f), TwitchUi.dp(a, 9f),
                    TwitchUi.dp(a, 36f), TwitchUi.dp(a, 9f));
            searchBox.addView(search, new FrameLayout.LayoutParams(-1, -2));

            TextView clearBtn = new TextView(a);
            clearBtn.setText("✕");
            clearBtn.setTextColor(SettingsUi.secondaryTextColor(a));
            clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            clearBtn.setGravity(Gravity.CENTER);
            clearBtn.setBackground(TwitchUi.selectableBorderlessBackground(a));
            clearBtn.setVisibility(View.GONE);
            clearBtn.setOnClickListener(v -> search.setText(""));
            FrameLayout.LayoutParams clrLp = new FrameLayout.LayoutParams(
                    TwitchUi.dp(a, 32f), TwitchUi.dp(a, 32f), Gravity.CENTER_VERTICAL | Gravity.END);
            clrLp.rightMargin = TwitchUi.dp(a, 4f);
            searchBox.addView(clearBtn, clrLp);
            root.addView(searchBox);

            // フィルター & 件数
            LinearLayout metaRow = new LinearLayout(a);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.setPadding(0, TwitchUi.dp(a, 8f), 0, TwitchUi.dp(a, 6f));

            final boolean[] showAll = {false};
            TextView filterChip = new TextView(a);
            filterChip.setText("ほかの配信も含める");
            filterChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            filterChip.setTypeface(Typeface.DEFAULT_BOLD);
            filterChip.setPadding(TwitchUi.dp(a, 10f), TwitchUi.dp(a, 5f),
                    TwitchUi.dp(a, 10f), TwitchUi.dp(a, 5f));
            filterChip.setVisibility(hasOther ? View.VISIBLE : View.GONE);
            metaRow.addView(filterChip);

            TextView countTv = new TextView(a);
            countTv.setTextColor(SettingsUi.secondaryTextColor(a));
            countTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            countTv.setGravity(Gravity.END);
            metaRow.addView(countTv, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(metaRow);

            root.addView(TwitchUi.divider(a));

            ArrayList<Entry> filtered = new ArrayList<>(snap.entries.size());
            HistoryAdapter adapter = new HistoryAdapter(a, d, filtered, channel);
            ListView listView = new ListView(a);
            listView.setAdapter(adapter);
            listView.setDivider(new ColorDrawable(SettingsUi.dividerColor(a)));
            listView.setDividerHeight(Math.max(1, TwitchUi.dp(a, 0.5f)));
            listView.setFooterDividersEnabled(false);
            listView.setHeaderDividersEnabled(false);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= filtered.size()) return;
                Entry entry = filtered.get(position);
                try {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Chat comment", entry.text));
                        Toast.makeText(a, "コメントをコピーしました", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            });

            TextView emptyTv = new TextView(a);
            emptyTv.setTextColor(SettingsUi.secondaryTextColor(a));
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, TwitchUi.dp(a, 24f), 0, TwitchUi.dp(a, 24f));

            FrameLayout listContainer = new FrameLayout(a);
            listContainer.addView(listView, new FrameLayout.LayoutParams(-1, -1));
            listContainer.addView(emptyTv, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            listView.setEmptyView(emptyTv);

            int listH = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.45f);
            root.addView(listContainer, new LinearLayout.LayoutParams(-1, listH));

            Runnable updateChipStyle = () -> {
                boolean active = showAll[0];
                int fill = active ? SettingsUi.accentStrongColor(a) : SettingsUi.alternateBackgroundColor(a);
                int stroke = active ? SettingsUi.accentColor(a) : SettingsUi.borderColor(a);
                filterChip.setTextColor(active ? 0xFFFFFFFF : SettingsUi.secondaryTextColor(a));
                filterChip.setBackground(TwitchUi.ripple(a,
                        TwitchUi.roundedRect(fill, 14f * d, Math.max(1, TwitchUi.dp(a, 1f)), stroke),
                        14f * d));
            };
            updateChipStyle.run();

            Runnable refresh = () -> {
                String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                int inChannel = 0;
                for (Entry e : snap.entries) {
                    boolean here = channel == null || e.channel == null || channel.equals(e.channel);
                    if (here) inChannel++;
                    if (!showAll[0] && !here) continue;
                    if (!q.isEmpty() && !e.text.toLowerCase(Locale.ROOT).contains(q)) continue;
                    filtered.add(e);
                }
                adapter.notifyDataSetChanged();
                if (snap.entries.isEmpty()) {
                    emptyTv.setText("まだ記録されたコメントはありません");
                    countTv.setText("記録はアプリを開いているあいだだけ残ります");
                } else if (filtered.isEmpty()) {
                    emptyTv.setText("一致するコメントはありません");
                    countTv.setText("この配信 " + inChannel + "件 / 全体 " + snap.entries.size() + "件");
                } else {
                    countTv.setText(filtered.size() + "件表示（この配信 " + inChannel
                            + " / 全体 " + snap.entries.size() + "）");
                }
            };

            filterChip.setOnClickListener(v -> {
                showAll[0] = !showAll[0];
                updateChipStyle.run();
                refresh.run();
            });
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    clearBtn.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                    refresh.run();
                }
            });
            refresh.run();

            TwitchUi.prepareBottomSheet(dlg, a, root);
            dlg.setContentView(root);
            dlg.show();
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
            debug(a, "過去コメント: 一覧を表示できませんでした");
        }
    }

    private static final class HistoryAdapter extends BaseAdapter {
        private final Context context;
        private final float d;
        private final List<Entry> items;
        private final String channel;
        private final SimpleDateFormat fmt = new SimpleDateFormat("M/d HH:mm:ss", Locale.JAPAN);
        private final int primaryColor;
        private final int secondaryColor;

        HistoryAdapter(Context context, float d, List<Entry> items, String channel) {
            this.context = context;
            this.d = d;
            this.items = items;
            this.channel = channel;
            this.primaryColor = SettingsUi.primaryTextColor(context);
            this.secondaryColor = SettingsUi.secondaryTextColor(context);
        }

        @Override public int getCount() { return items.size(); }
        @Override public Entry getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView reused) {
                tv = reused;
            } else {
                tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                tv.setPadding(0, Math.round(9 * d), 0, Math.round(9 * d));
                tv.setLineSpacing(Math.round(2 * d), 1f);
            }
            Entry e = items.get(position);
            boolean here = channel == null || e.channel == null || channel.equals(e.channel);
            String time = fmt.format(new Date(e.time));
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(time).append("  ");
            sb.setSpan(new ForegroundColorSpan(secondaryColor), 0, time.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(e.text);
            tv.setText(sb);
            tv.setTextColor(here ? primaryColor : secondaryColor);
            tv.setTypeface(Typeface.DEFAULT, here ? Typeface.NORMAL : Typeface.ITALIC);
            return tv;
        }
    }

    private static Activity findActivity(Context c) {
        while (c instanceof ContextWrapper cw) {
            if (c instanceof Activity a) return a;
            c = cw.getBaseContext();
        }
        return null;
    }

    private static void debug(Context c, String msg) {
        if (c == null || toasts >= 6) return;
        try { if (!KizuSettings.DEBUG_MESSAGES.get()) return; } catch (Throwable ignored) {}
        toasts++;
        try { Toast.makeText(c.getApplicationContext(), "Kizu " + msg, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    private static void fail(String msg, Throwable t) {
        if (!broken) debug(lastContext.get(), "過去コメント: エラーで停止しました（" + msg + "）");
        broken = true;
        Log.e(TAG, msg, t);
    }
}
