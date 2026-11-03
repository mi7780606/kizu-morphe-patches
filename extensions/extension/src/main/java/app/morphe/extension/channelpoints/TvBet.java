package app.morphe.extension.channelpoints;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * 手動で予想に賭ける。スマホ版の通信は改造検知(Client-Integrity)で弾かれるため、
 * Android TV版Twitchとしてデバイスコードでログインし、そのトークンで賭ける。
 * ボタンを押したときだけ動く（常駐処理なし）。
 */
public final class TvBet {
    private static final String TAG = "KizuTvBet";
    private static final String CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.1; Smart Box C1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36";
    private static final String SCOPES =
            "channel_read chat:read user_blocks_edit user_blocks_read user_follows_edit user_read";
    private static final String GQL_URL = "https://gql.twitch.tv/gql";

    private static final String BET_MUTATION =
            "mutation MakeCommunityPointsPredictionMutation($input: MakePredictionInput!) {"
            + " makePrediction(input: $input) { error { code maxPointsPerEvent userPointsSpent }"
            + " prediction { id points outcome { id } } } }";
    private static final String BET_HASH = "b44682ecc88358817009f20e69d75081b1e58825bb40aa53d5dbadcc17c881d8";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean loginRunning;

    private TvBet() {}

    public static void open(Context c) {
        try {
            if (KizuSettings.TV_TOKEN.get().trim().isEmpty()) showLogin(c);
            else load(c);
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
        }
    }

    // ---------- ログイン（デバイスコード） ----------

    private static void showLogin(Context c) {
        float d = density(c);
        LinearLayout box = box(c, d);
        TextView msg = text(c, d, "初回だけ、Kizu用にログインします（Android TV版としてログイン）。\n準備中…", 14);
        box.addView(msg);
        TextView code = text(c, d, "", 30);
        code.setTextColor(0xFFBF94FF);
        code.setTextIsSelectable(true);
        box.addView(code);
        Button openBtn = new Button(c);
        openBtn.setText("twitch.tv/activate を開く");
        openBtn.setEnabled(false);
        box.addView(openBtn);

        final boolean[] closed = {false};
        AlertDialog dlg = new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kizu用ログイン")
                .setView(box)
                .setNegativeButton("閉じる", null)
                .create();
        dlg.setOnDismissListener(x -> closed[0] = true);
        dlg.show();

        if (loginRunning) { msg.setText("ログイン処理中です。少し待ってから開き直してください。"); return; }
        loginRunning = true;
        new Thread(() -> {
            try {
                String r = form("https://id.twitch.tv/oauth2/device",
                        "client_id=" + CLIENT_ID + "&scopes=" + URLEncoder.encode(SCOPES, "UTF-8"))[1];
                JSONObject j = new JSONObject(r);
                String deviceCode = j.getString("device_code");
                String userCode = j.getString("user_code");
                String uri = j.optString("verification_uri", "https://www.twitch.tv/activate");
                int interval = Math.max(2, j.optInt("interval", 5));
                long until = System.currentTimeMillis() + j.optInt("expires_in", 1800) * 1000L;
                MAIN.post(() -> {
                    msg.setText("下のコードを twitch.tv/activate で入力して「許可」を押してください。許可すると自動で次へ進みます。");
                    code.setText(userCode);
                    openBtn.setEnabled(true);
                    openBtn.setOnClickListener(v -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri.contains("?") ? uri : uri + "?device-code=" + userCode));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(i);
                        } catch (Throwable ignored) {}
                    });
                });
                while (System.currentTimeMillis() < until && !closed[0]) {
                    Thread.sleep(interval * 1000L);
                    String[] t = form("https://id.twitch.tv/oauth2/token",
                            "client_id=" + CLIENT_ID + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                                    + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8"));
                    JSONObject tj = parse(t[1]);
                    if (tj != null && tj.has("access_token")) {
                        KizuSettings.TV_TOKEN.save(tj.getString("access_token"));
                        MAIN.post(() -> { try { dlg.dismiss(); } catch (Throwable ignored) {} load(c); });
                        return;
                    }
                    String m = tj == null ? "" : tj.optString("message", "");
                    if (m.contains("pending")) continue;
                    if (m.contains("slow")) { interval += 2; continue; }
                    if (!m.isEmpty()) { final String fm = m; MAIN.post(() -> msg.setText("ログイン失敗: " + fm)); return; }
                }
            } catch (Throwable e) {
                MAIN.post(() -> msg.setText("ログイン失敗: " + e));
            } finally {
                loginRunning = false;
            }
        }, "kizu-tv-login").start();
    }

    // ---------- 状態の取得 ----------

    private static final String EVENT_FIELDS =
            "fragment E on PredictionEvent { id status title createdAt predictionWindowSeconds winningOutcome { id }"
            + " outcomes { id title color totalPoints totalUsers } }";
    private static final String RECENT_FIELDS =
            "recentPredictions { id points pointsWon result predictedAt outcome { id }"
            + " event { id title status winningOutcome { id } outcomes { id title color } } }";
    private static final String STATE_QUERY =
            "query KizuPrediction($id: ID!) { user(id: $id) { login displayName stream { createdAt } channel {"
            + " self { communityPoints { balance } RECENT }"
            + " activePredictionEvents { ...E } lockedPredictionEvents { ...E } } } } " + EVENT_FIELDS;

    private static final class State {
        String channelId, name, streamStart, error;
        JSONObject event;
        JSONArray recent;
        int balance = -1;
    }

    private static State fetchState(String channelId) {
        State st = new State();
        st.channelId = channelId;
        try {
            JSONObject req = new JSONObject()
                    .put("operationName", "KizuPrediction")
                    .put("variables", new JSONObject().put("id", channelId))
                    .put("query", STATE_QUERY.replace("RECENT", RECENT_FIELDS));
            String[] r = gql(req.toString());
            JSONObject res = parse(r[1]);
            if (res != null && path(res, "data", "user", "channel") == null && res.optJSONArray("errors") != null) {
                req.put("query", STATE_QUERY.replace("RECENT", "")); // 履歴が取れないときは外す
                r = gql(req.toString());
                res = parse(r[1]);
            }
            if ("401".equals(r[0])) {
                KizuSettings.TV_TOKEN.save("");
                st.error = "ログインの期限が切れました。もう一度開いてログインしてください。";
                return st;
            }
            JSONObject user = res == null ? null : path(res, "data", "user");
            JSONObject ch = user == null ? null : user.optJSONObject("channel");
            if (ch == null) {
                JSONArray errors = res == null ? null : res.optJSONArray("errors");
                st.error = errors != null && errors.length() > 0
                        ? "取得エラー: " + errors.optJSONObject(0).optString("message")
                        : "通信失敗 (HTTP " + r[0] + ")";
                return st;
            }
            st.name = user.optString("displayName", user.optString("login"));
            JSONObject stream = user.optJSONObject("stream");
            st.streamStart = stream == null ? null : stream.optString("createdAt", null);
            JSONObject self = ch.optJSONObject("self");
            if (self != null) {
                JSONObject cp = self.optJSONObject("communityPoints");
                if (cp != null) st.balance = cp.optInt("balance", -1);
                st.recent = self.optJSONArray("recentPredictions");
            }
            st.event = pick(ch.optJSONArray("activePredictionEvents"));
            if (st.event == null) st.event = pick(ch.optJSONArray("lockedPredictionEvents"));
        } catch (Throwable e) {
            st.error = "読み込み失敗: " + e;
        }
        return st;
    }

    private static JSONObject pick(JSONArray a) {
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null) return e;
        }
        return null;
    }

    private static JSONObject myPrediction(State st, String eventId) {
        if (st.recent == null || eventId == null) return null;
        for (int i = 0; i < st.recent.length(); i++) {
            JSONObject p = st.recent.optJSONObject(i);
            JSONObject e = p == null ? null : p.optJSONObject("event");
            if (e != null && eventId.equals(e.optString("id"))) return p;
        }
        return null;
    }

    private static void load(Context c) {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) { info(c, "予想", "配信を開いてから押してください。"); return; }
        new Thread(() -> {
            State st = fetchState(channelId);
            MAIN.post(() -> {
                try {
                    if (st.error != null) info(c, "予想", st.error);
                    else showBet(c, st);
                } catch (Throwable t) {
                    Log.e(TAG, "show failed", t);
                    info(c, "予想", "表示に失敗しました: " + t);
                }
            });
        }, "kizu-tv-load").start();
    }

    // ---------- 賭け画面（Twitch風） ----------

    private static final int BG = 0xFF18181B;
    private static final int CARD = 0xFF26262C;
    private static final int SUB = 0xFFADADB8;
    private static final int PURPLE = 0xFFBF94FF;
    private static final int BLUE = 0xFF387AFF;
    private static final int PINK = 0xFFF5009B;

    private static int colorOf(JSONObject o, int index) {
        String col = o == null ? "" : o.optString("color", "");
        if ("PINK".equalsIgnoreCase(col)) return PINK;
        if ("BLUE".equalsIgnoreCase(col)) return BLUE;
        return index == 1 ? PINK : BLUE;
    }

    private static GradientDrawable round(int color, float radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    private static String num(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    private static void showBet(Context c, State st) {
        float d = density(c);
        Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(c);
        int ic = c.getResources().getIdentifier("ic_prediction_event", "drawable", c.getPackageName());
        if (ic != 0) { icon.setImageResource(ic); icon.setColorFilter(PURPLE); }
        top.addView(icon, new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d)));
        TextView head = label(c, "  予想" + (st.name != null ? " · " + st.name : ""), 12, SUB, false);
        top.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = label(c, "✕", 16, SUB, false);
        close.setPadding((int) (8 * d), 0, 0, 0);
        close.setOnClickListener(v -> dlg.dismiss());
        top.addView(close);
        root.addView(top);

        JSONObject ev = st.event;
        if (ev == null) {
            root.addView(spaced(label(c, "今は予想がありません", 16, Color.WHITE, true), d, 12));
        } else {
            buildEvent(c, d, dlg, root, st, ev);
        }

        // フッター
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, (int) (14 * d), 0, 0);
        foot.addView(label(c, st.balance >= 0 ? "所持 " + num(st.balance) + " pt" : "", 13, SUB, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = label(c, "更新", 14, PURPLE, true);
        refresh.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        refresh.setOnClickListener(v -> { dlg.dismiss(); load(c); });
        foot.addView(refresh);
        TextView hist = label(c, "履歴", 14, PURPLE, true);
        hist.setPadding((int) (12 * d), (int) (6 * d), 0, (int) (6 * d));
        hist.setOnClickListener(v -> showHistory(c, st));
        foot.addView(hist);
        root.addView(foot);

        TextView logout = label(c, "Kizu用ログインを解除", 11, 0xFF6E6E78, false);
        logout.setPadding(0, (int) (10 * d), 0, 0);
        logout.setOnClickListener(v -> { KizuSettings.TV_TOKEN.save(""); dlg.dismiss(); });
        root.addView(logout);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        placeOverChat(dlg, c, sv, root, d);
        dlg.show();
        placeOverChat(dlg, c, sv, root, d);
    }

    private static void buildEvent(Context c, float d, Dialog dlg, LinearLayout root, State st, JSONObject ev) {
        String status = ev.optString("status");
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        String state;
        if (active) {
            long left = secondsLeft(ev);
            state = left >= 0 ? String.format(Locale.US, "受付中 · 残り %d:%02d", left / 60, left % 60) : "受付中";
        } else if ("LOCKED".equalsIgnoreCase(status)) state = "締め切り · 結果待ち";
        else state = status;
        root.addView(spaced(label(c, ev.optString("title"), 17, Color.WHITE, true), d, 10));
        TextView stateView = label(c, state, 13, active ? 0xFF00C85A : SUB, true);
        root.addView(spaced(stateView, d, 2));
        // 残り時間を1秒ごとに更新（ダイアログ表示中だけ）
        if (active && secondsLeft(ev) >= 0) {
            Runnable tick = new Runnable() {
                @Override public void run() {
                    if (!dlg.isShowing()) return;
                    long left = secondsLeft(ev);
                    if (left > 0) {
                        stateView.setText(String.format(Locale.US, "受付中 · 残り %d:%02d（%d秒）", left / 60, left % 60, left));
                        stateView.setTextColor(left <= 10 ? 0xFFEB0400 : 0xFF00C85A);
                        MAIN.postDelayed(this, 1000);
                    } else {
                        stateView.setText("締め切り · 結果待ち");
                        stateView.setTextColor(SUB);
                    }
                }
            };
            MAIN.post(tick);
        }

        JSONArray outs = ev.optJSONArray("outcomes");
        int n = outs == null ? 0 : outs.length();
        long total = 0;
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null) total += Math.max(0, o.optLong("totalPoints"));
        }
        JSONObject mine = myPrediction(st, ev.optString("id"));
        String mineOutcome = mine == null || mine.optJSONObject("outcome") == null ? null : mine.optJSONObject("outcome").optString("id");

        final int[] selected = {-1};
        final LinearLayout[] cards = new LinearLayout[n];
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(n == 2 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        list.setPadding(0, (int) (12 * d), 0, 0);
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o == null) continue;
            int col = colorOf(o, i);
            long op = Math.max(0, o.optLong("totalPoints"));
            int pct = total > 0 ? (int) Math.round(op * 100.0 / total) : 0;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            int cp = (int) (10 * d);
            card.setPadding(cp, cp, cp, cp);
            card.setBackground(round(CARD, 8 * d, (int) (2 * d), CARD));
            TextView t = label(c, o.optString("title"), 14, Color.WHITE, true);
            t.setMaxLines(2);
            card.addView(t);
            card.addView(label(c, pct + "%", 24, col, true));
            String ratio = op > 0 ? String.format(Locale.US, "1:%.2f", (double) total / op) : "1:—";
            card.addView(label(c, "🏆 " + ratio, 13, col, true));
            card.addView(label(c, "● " + num(op) + " pt", 12, SUB, false));
            card.addView(label(c, "👤 " + num(o.optInt("totalUsers")) + "人", 12, SUB, false));
            if (o.optString("id").equals(mineOutcome)) {
                TextView m = label(c, "あなた " + num(mine.optLong("points")) + " pt", 12, Color.WHITE, true);
                m.setPadding((int) (6 * d), (int) (2 * d), (int) (6 * d), (int) (2 * d));
                m.setBackground(round(col, 4 * d, 0, 0));
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
                mp.topMargin = (int) (6 * d);
                card.addView(m, mp);
            }
            LinearLayout.LayoutParams lp = n == 2
                    ? new LinearLayout.LayoutParams(0, -1, 1)
                    : new LinearLayout.LayoutParams(-1, -2);
            if (n == 2 && i == 0) lp.rightMargin = (int) (6 * d);
            if (n == 2 && i == 1) lp.leftMargin = (int) (6 * d);
            if (n != 2 && i > 0) lp.topMargin = (int) (8 * d);
            cards[i] = card;
            list.addView(card, lp);
        }
        root.addView(list);

        if (!active) {
            if (mine == null) root.addView(spaced(label(c, "この予想には賭けていません", 13, SUB, false), d, 12));
            return;
        }

        // 賭ける量
        LinearLayout amount = new LinearLayout(c);
        amount.setOrientation(LinearLayout.HORIZONTAL);
        amount.setGravity(Gravity.CENTER_VERTICAL);
        amount.setPadding(0, (int) (14 * d), 0, 0);
        EditText pts = new EditText(c);
        pts.setInputType(InputType.TYPE_CLASS_NUMBER);
        pts.setSingleLine(true);
        pts.setTextColor(Color.WHITE);
        pts.setHintTextColor(SUB);
        pts.setHint("ポイント");
        pts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pts.setBackground(round(0xFF0E0E10, 6 * d, (int) (1 * d), 0xFF3A3A3D));
        pts.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        pts.setText(String.valueOf(KizuSettings.PREDICTION_POINTS.get()));
        amount.addView(pts, new LinearLayout.LayoutParams(0, -2, 1));
        String[] quick = {"+10", "+100", "+1000", "全部"};
        for (String q : quick) {
            TextView b = label(c, q, 13, Color.WHITE, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
            b.setBackground(round(0xFF3A3A3D, 6 * d, 0, 0));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = (int) (5 * d);
            b.setOnClickListener(v -> {
                long cur;
                try { cur = Long.parseLong(pts.getText().toString().trim()); } catch (Throwable t) { cur = 0; }
                long next = "全部".equals(q) ? Math.max(0, st.balance) : cur + Long.parseLong(q.substring(1));
                if (st.balance >= 0) next = Math.min(next, st.balance);
                pts.setText(String.valueOf(next));
                pts.setSelection(pts.getText().length());
            });
            amount.addView(b, bp);
        }
        root.addView(amount);

        TextView expect = label(c, "", 12, SUB, false);
        expect.setPadding(0, (int) (6 * d), 0, 0);
        root.addView(expect);

        TextView betBtn = label(c, "選択肢を選んでください", 15, Color.WHITE, true);
        betBtn.setGravity(Gravity.CENTER);
        betBtn.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        betBtn.setBackground(round(0xFF3A3A3D, 6 * d, 0, 0));
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(-1, -2);
        bbp.topMargin = (int) (10 * d);
        root.addView(betBtn, bbp);

        final long fTotal = total;
        Runnable update = () -> {
            for (int i = 0; i < n; i++) {
                if (cards[i] == null) continue;
                int col = colorOf(outs.optJSONObject(i), i);
                cards[i].setBackground(round(CARD, 8 * d, (int) (2 * d), i == selected[0] ? col : CARD));
            }
            int s = selected[0];
            expect.setTextColor(SUB);
            if (s < 0) { expect.setText(""); return; }
            JSONObject o = outs.optJSONObject(s);
            int col = colorOf(o, s);
            String title = o.optString("title");
            if (title.length() > 12) title = title.substring(0, 12) + "…";
            betBtn.setText("「" + title + "」に賭ける");
            betBtn.setBackground(round(col, 6 * d, 0, 0));
            try {
                long p = Long.parseLong(pts.getText().toString().trim());
                long op = Math.max(0, o.optLong("totalPoints"));
                if (p <= 0) { expect.setText(""); return; }
                long win = Math.round((double) (fTotal + p) * p / (op + p));
                expect.setText("当たれば約 " + num(win) + " pt（+" + num(win - p) + "）※今の状況での目安");
            } catch (Throwable t) { expect.setText(""); }
        };
        for (int i = 0; i < n; i++) {
            if (cards[i] == null) continue;
            final int idx = i;
            cards[i].setOnClickListener(v -> { selected[0] = idx; update.run(); });
        }
        int def = Math.max(1, KizuSettings.PREDICTION_OUTCOME.get());
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optString("id").equals(mineOutcome)) selected[0] = i;
        }
        if (selected[0] < 0 && n > 0 && mineOutcome == null && n <= 2) selected[0] = -1;
        pts.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int x, int y) {}
            public void onTextChanged(CharSequence a, int b, int x, int y) {}
            public void afterTextChanged(Editable e) { update.run(); }
        });
        update.run();

        final String eventId = ev.optString("id");
        betBtn.setOnClickListener(v -> {
            int s = selected[0];
            if (s < 0) { expect.setTextColor(0xFFEB0400); expect.setText("選択肢をタップして選んでください"); return; }
            int p;
            try { p = Integer.parseInt(pts.getText().toString().trim()); } catch (Throwable t) { p = 0; }
            if (p < 1) { expect.setTextColor(0xFFEB0400); expect.setText("ポイントを入れてください"); return; }
            if (st.balance >= 0 && p > st.balance) p = st.balance;
            final int fp = p;
            final String outcomeId = outs.optJSONObject(s).optString("id");
            betBtn.setEnabled(false);
            betBtn.setText("送信中…");
            new Thread(() -> {
                String[] result = bet(eventId, outcomeId, fp);
                MAIN.post(() -> {
                    if ("ok".equals(result[0])) {
                        watch(c.getApplicationContext(), st.channelId, eventId);
                        try { dlg.dismiss(); } catch (Throwable ignored) {}
                        load(c);
                    } else {
                        betBtn.setEnabled(true);
                        update.run();
                        info(c, "予想", result[1]);
                    }
                });
            }, "kizu-tv-bet").start();
        });
    }

    // ---------- 履歴 ----------

    private static long net(JSONObject p) {
        return p.optLong("pointsWon") - p.optLong("points");
    }

    private static boolean resolved(JSONObject p) {
        String r = p.isNull("result") ? "" : p.optString("result", "");
        return !r.isEmpty() && !"null".equals(r);
    }

    private static void showHistory(Context c, State st) {
        float d = density(c);
        Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label(c, "賭けの履歴", 17, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = label(c, "✕", 16, SUB, false);
        close.setOnClickListener(v -> dlg.dismiss());
        top.addView(close);
        root.addView(top);

        if (st.recent == null) {
            root.addView(spaced(label(c, "履歴を取得できませんでした。", 14, SUB, false), d, 12));
        } else {
            long since = parseTime(st.streamStart);
            long streamNet = 0, allNet = 0; int streamCount = 0, wins = 0, decided = 0;
            List<JSONObject> rows = new ArrayList<>();
            for (int i = 0; i < st.recent.length(); i++) {
                JSONObject p = st.recent.optJSONObject(i);
                if (p == null) continue;
                boolean res = resolved(p);
                if (res) allNet += net(p);
                long at = parseTime(p.optString("predictedAt"));
                boolean inStream = since > 0 ? at >= since : at >= System.currentTimeMillis() - 12L * 3600_000L;
                if (!inStream) continue;
                rows.add(p);
                streamCount++;
                if (res) {
                    streamNet += net(p);
                    decided++;
                    if ("WIN".equalsIgnoreCase(p.optString("result"))) wins++;
                }
            }
            String scope = since > 0 ? "この配信" : "直近12時間";
            TextView sum = label(c, scope + "の累計  " + sign(streamNet) + " pt", 20, streamNet >= 0 ? 0xFF00C85A : 0xFFEB0400, true);
            root.addView(spaced(sum, d, 12));
            root.addView(spaced(label(c, streamCount + "回 · 的中 " + wins + "/" + decided
                    + "　（最近の全体 " + sign(allNet) + " pt）", 12, SUB, false), d, 2));

            if (rows.isEmpty()) root.addView(spaced(label(c, scope + "ではまだ賭けていません", 14, SUB, false), d, 14));
            for (JSONObject p : rows) {
                JSONObject e = p.optJSONObject("event");
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                int cp = (int) (10 * d);
                card.setPadding(cp, cp, cp, cp);
                card.setBackground(round(CARD, 8 * d, 0, 0));
                card.addView(label(c, e == null ? "予想" : e.optString("title"), 14, Color.WHITE, true));
                String oid = p.optJSONObject("outcome") == null ? "" : p.optJSONObject("outcome").optString("id");
                String otitle = ""; int col = BLUE;
                JSONArray outs = e == null ? null : e.optJSONArray("outcomes");
                for (int i = 0; outs != null && i < outs.length(); i++) {
                    JSONObject o = outs.optJSONObject(i);
                    if (o != null && o.optString("id").equals(oid)) { otitle = o.optString("title"); col = colorOf(o, i); }
                }
                card.addView(label(c, "● " + otitle + "　" + num(p.optLong("points")) + " pt", 13, col, false));
                String r = p.optString("result", "");
                String line; int lc;
                if (!resolved(p)) { line = "結果待ち"; lc = SUB; }
                else if ("WIN".equalsIgnoreCase(r)) { line = "的中  " + sign(net(p)) + " pt"; lc = 0xFF00C85A; }
                else if ("REFUND".equalsIgnoreCase(r)) { line = "返金  ±0 pt"; lc = SUB; }
                else { line = "ハズレ  " + sign(net(p)) + " pt"; lc = 0xFFEB0400; }
                card.addView(label(c, line, 14, lc, true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = (int) (8 * d);
                root.addView(card, lp);
            }
        }

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        placeOverChat(dlg, c, sv, root, d);
        dlg.show();
        placeOverChat(dlg, c, sv, root, d);
    }

    /** プレイヤーの下（コメント欄の位置）にだけ重ねる。プレイヤーが見つからなければ画面下60%。 */
    private static void placeOverChat(Dialog dlg, Context c, ScrollView sv, LinearLayout root, float d) {
        Window w = dlg.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        int top = -1;
        Activity a = activity(c);
        if (a != null) {
            int id = a.getResources().getIdentifier("player_overlay_container", "id", a.getPackageName());
            View player = id != 0 ? a.findViewById(id) : null;
            if (player != null && player.isShown() && player.getHeight() > 0) {
                int[] loc = new int[2];
                player.getLocationOnScreen(loc);
                top = loc[1] + player.getHeight();
            }
            android.graphics.Rect frame = new android.graphics.Rect();
            a.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            if (frame.bottom > 0) screenH = frame.bottom;
        }
        int h = top > 0 && top < screenH * 0.8f ? screenH - top : (int) (screenH * 0.6f);
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int width = landscape ? Math.min(dm.widthPixels, (int) (420 * d)) : WindowManager.LayoutParams.MATCH_PARENT;
        w.setGravity(Gravity.BOTTOM | (landscape ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL));
        w.getDecorView().setPadding(0, 0, 0, 0);
        w.setLayout(width, h);
    }

    private static Activity activity(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static String sign(long v) {
        return (v > 0 ? "+" : v < 0 ? "-" : "±") + num(Math.abs(v));
    }

    private static long parseTime(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        try { return java.time.Instant.parse(s).toEpochMilli(); } catch (Throwable t) { return 0; }
    }

    // ---------- 結果の見張り（賭けたときだけ、30秒ごと） ----------

    private static final Set<String> WATCHING = Collections.synchronizedSet(new HashSet<>());

    private static void watch(Context app, String channelId, String eventId) {
        if (eventId == null || !WATCHING.add(eventId)) return;
        Thread t = new Thread(() -> {
            long until = System.currentTimeMillis() + 3L * 3600_000L;
            try {
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(30_000L);
                    if (KizuSettings.TV_TOKEN.get().trim().isEmpty()) return;
                    JSONObject req = new JSONObject()
                            .put("operationName", "KizuRecentPredictions")
                            .put("variables", new JSONObject().put("id", channelId))
                            .put("query", "query KizuRecentPredictions($id: ID!) { user(id: $id) { channel { self {"
                                    + " recentPredictions { points pointsWon result event { id title } } } } } }");
                    JSONObject res = parse(gql(req.toString())[1]);
                    JSONObject self = res == null ? null : path(res, "data", "user", "channel", "self");
                    JSONArray rec = self == null ? null : self.optJSONArray("recentPredictions");
                    if (rec == null) continue;
                    for (int i = 0; i < rec.length(); i++) {
                        JSONObject p = rec.optJSONObject(i);
                        JSONObject e = p == null ? null : p.optJSONObject("event");
                        if (e == null || !eventId.equals(e.optString("id"))) continue;
                        if (!resolved(p)) break;
                        if ("WIN".equalsIgnoreCase(p.optString("result"))) {
                            long plus = net(p);
                            String msg = "予想的中！ +" + num(plus) + " pt（" + e.optString("title") + "）";
                            MAIN.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                WATCHING.remove(eventId);
            }
        }, "kizu-tv-watch");
        t.setDaemon(true);
        t.start();
    }

    // ---------- 賭ける ----------

    /** [ok|ng, メッセージ] */
    private static String[] bet(String eventId, String outcomeId, int points) {
        try {
            JSONObject vars = new JSONObject().put("input", new JSONObject()
                    .put("eventID", eventId)
                    .put("outcomeID", outcomeId)
                    .put("points", points)
                    .put("transactionID", UUID.randomUUID().toString().replace("-", "")));
            JSONObject req = new JSONObject()
                    .put("operationName", "MakeCommunityPointsPredictionMutation")
                    .put("variables", vars)
                    .put("query", BET_MUTATION);
            String[] r = gql(req.toString());
            JSONObject res = parse(r[1]);
            JSONObject mp = res == null ? null : path(res, "data", "makePrediction");
            if (mp == null) {
                JSONObject req2 = new JSONObject()
                        .put("operationName", "MakePrediction")
                        .put("variables", vars)
                        .put("extensions", new JSONObject().put("persistedQuery",
                                new JSONObject().put("version", 1).put("sha256Hash", BET_HASH)));
                String[] r2 = gql(req2.toString());
                JSONObject res2 = parse(r2[1]);
                mp = res2 == null ? null : path(res2, "data", "makePrediction");
                if (mp == null) {
                    if ("401".equals(r[0]) || "401".equals(r2[0])) { KizuSettings.TV_TOKEN.save(""); return new String[]{"ng", "ログインの期限が切れました。もう一度ログインしてください。"}; }
                    return new String[]{"ng", "失敗: HTTP " + r[0] + " / " + r2[0] + "\n" + shorten(r[1]) + "\n" + shorten(r2[1])};
                }
            }
            JSONObject err = mp.optJSONObject("error");
            if (err != null) {
                String code = err.optString("code");
                int max = err.optInt("maxPointsPerEvent", 0);
                int spent = err.optInt("userPointsSpent", 0);
                String why;
                switch (code) {
                    case "NOT_ENOUGH_POINTS": why = "ポイントが足りません"; break;
                    case "EVENT_NOT_ACTIVE": why = "受付が終わっています"; break;
                    case "MAX_POINTS_PER_EVENT": why = "上限を超えています（上限 " + max + "、使用済み " + spent + "）"; break;
                    case "DUPLICATE_PREDICTION": why = "もう賭けています"; break;
                    default: why = code;
                }
                return new String[]{"ng", "賭けられませんでした: " + why};
            }
            JSONObject pr = mp.optJSONObject("prediction");
            return new String[]{"ok", "賭けました: " + num(pr != null ? pr.optInt("points", points) : points) + " pt"};
        } catch (Throwable e) {
            return new String[]{"ng", "失敗: " + e};
        }
    }

    // ---------- 通信 ----------

    private static String deviceId() {
        String id = KizuSettings.TV_DEVICE_ID.get();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            KizuSettings.TV_DEVICE_ID.save(id);
        }
        return id;
    }

    private static String[] gql(String body) {
        return http(GQL_URL, body, "application/json", true);
    }

    private static String[] form(String url, String body) {
        return http(url, body, "application/x-www-form-urlencoded", false);
    }

    /** [HTTPコード, 本文] */
    private static String[] http(String url, String body, String type, boolean auth) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Client-Id", CLIENT_ID);
            if (auth) {
                c.setRequestProperty("Authorization", "OAuth " + KizuSettings.TV_TOKEN.get().trim());
                c.setRequestProperty("X-Device-Id", deviceId());
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.getOutputStream().write(b);
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = s.read(buf)) != -1 && total < 256 * 1024) { total += n; out.write(buf, 0, n); }
                    text = out.toString("UTF-8");
                }
            }
            return new String[]{String.valueOf(code), text};
        } catch (Throwable e) {
            return new String[]{"0", String.valueOf(e)};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- 小物 ----------

    private static JSONObject parse(String s) {
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Throwable e) { return null; }
    }

    private static JSONObject path(JSONObject o, String... keys) {
        for (String k : keys) { if (o == null) return null; o = o.optJSONObject(k); }
        return o;
    }

    private static long secondsLeft(JSONObject ev) {
        try {
            long created = java.time.Instant.parse(ev.optString("createdAt")).toEpochMilli();
            long end = created + ev.optLong("predictionWindowSeconds") * 1000L;
            return Math.max(0, (end - System.currentTimeMillis()) / 1000L);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static float density(Context c) { return c.getResources().getDisplayMetrics().density; }

    private static LinearLayout box(Context c, float d) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        return box;
    }

    private static TextView text(Context c, float d, String s, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xFFDDDDDD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, (int) (10 * d), 0, 0);
        return t;
    }

    private static TextView label(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private static TextView spaced(TextView t, float d, int topDp) {
        t.setPadding(t.getPaddingLeft(), (int) (topDp * d), t.getPaddingRight(), t.getPaddingBottom());
        return t;
    }

    private static void info(Context c, String title, String m) {
        try {
            TextView t = new TextView(c);
            new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title).setMessage(m).setPositiveButton("OK", null).show();
        } catch (Throwable ignored) {}
    }
}
