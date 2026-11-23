package app.morphe.extension.channelpoints;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.Utils;
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

    private static final int BLUE = 0xFF387AFF;
    private static final int PINK = 0xFFF5009B;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean loginRunning;
    private static volatile long loginSessionSeq;

    private TvBet() {}

    public static void open(Context c) {
        try {
            if (KizuSettings.TV_TOKEN.get().trim().isEmpty()) showLogin(c);
            else load(c, null, null, null);
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
        }
    }

    // ---------- ログイン（デバイスコード） ----------

    private static void showLogin(Context c) {
        float d = density(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView msg = label(c, "初回だけ、Kizu用にログインします（Android TV版としてログイン）。\n準備中…",
                14f, SettingsUi.secondaryTextColor(c), false);
        box.addView(msg);

        LinearLayout codeCard = new LinearLayout(c);
        codeCard.setOrientation(LinearLayout.HORIZONTAL);
        codeCard.setGravity(Gravity.CENTER_VERTICAL);
        codeCard.setPadding(dp(d, 14), dp(d, 10), dp(d, 10), dp(d, 10));
        codeCard.setBackground(TwitchUi.roundedRect(
                SettingsUi.inputBackgroundColor(c),
                10 * d,
                Math.max(1, dp(d, 1)),
                SettingsUi.borderColor(c)));
        LinearLayout.LayoutParams codeCardLp = new LinearLayout.LayoutParams(-1, -2);
        codeCardLp.topMargin = dp(d, 12);
        codeCard.setVisibility(View.GONE);

        TextView codeView = label(c, "", 24f, SettingsUi.accentColor(c), true);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setTextIsSelectable(true);
        codeView.setLetterSpacing(0.08f);
        codeCard.addView(codeView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView copyBtn = TwitchUi.createSecondaryButton(c, "コピー");
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        copyBtn.setPadding(dp(d, 12), dp(d, 6), dp(d, 12), dp(d, 6));
        codeCard.addView(copyBtn);
        box.addView(codeCard, codeCardLp);

        TextView openBtn = TwitchUi.createPrimaryButton(c, "twitch.tv/activate を開く");
        openBtn.setEnabled(false);
        openBtn.setAlpha(0.5f);
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, -2);
        openLp.topMargin = dp(d, 12);
        box.addView(openBtn, openLp);

        final boolean[] closed = {false};
        final ScheduledFuture<?>[] pollTask = {null};
        final long session = ++loginSessionSeq;

        Dialog dlg = TwitchUi.showThemedDialog(
                c,
                "Kizu用ログイン",
                box,
                null,
                null,
                null,
                null,
                "閉じる");

        dlg.setOnDismissListener(x -> {
            closed[0] = true;
            ScheduledFuture<?> task = pollTask[0];
            if (task != null) task.cancel(false);
            if (loginSessionSeq == session) loginRunning = false;
        });

        if (loginRunning) {
            msg.setText("ログイン処理中です。少し待ってから開き直してください。");
            return;
        }
        loginRunning = true;

        KizuRuntime.ioExecutor().execute(() -> {
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
                    if (closed[0]) return;
                    msg.setText("下のコードを twitch.tv/activate で入力して「許可」を押してください。許可すると自動で次へ進みます。");
                    codeView.setText(userCode);
                    codeCard.setVisibility(View.VISIBLE);
                    copyBtn.setOnClickListener(v -> {
                        try {
                            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("Twitch activation code", userCode));
                                Toast.makeText(c, "コードをコピーしました", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable ignored) {}
                    });
                    openBtn.setEnabled(true);
                    openBtn.setAlpha(1f);
                    openBtn.setOnClickListener(v -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(uri.contains("?") ? uri : uri + "?device-code=" + userCode));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(i);
                        } catch (Throwable ignored) {}
                    });
                });

                scheduleLoginPoll(c, dlg, msg, closed, pollTask, session, deviceCode, interval, until);
            } catch (Throwable e) {
                MAIN.post(() -> {
                    if (!closed[0]) msg.setText("ログイン失敗: " + e);
                    if (loginSessionSeq == session) loginRunning = false;
                });
            }
        });
    }

    private static void scheduleLoginPoll(
            Context c,
            Dialog dlg,
            TextView msg,
            boolean[] closed,
            ScheduledFuture<?>[] pollTask,
            long session,
            String deviceCode,
            int intervalSec,
            long untilMs
    ) {
        if (closed[0] || loginSessionSeq != session) {
            if (loginSessionSeq == session) loginRunning = false;
            return;
        }
        if (System.currentTimeMillis() >= untilMs) {
            MAIN.post(() -> {
                if (!closed[0]) msg.setText("認証コードの有効期限が切れました。閉じてもう一度試してください。");
                if (loginSessionSeq == session) loginRunning = false;
            });
            return;
        }
        pollTask[0] = KizuRuntime.scheduler().schedule(() -> KizuRuntime.ioExecutor().execute(() -> {
            if (closed[0] || loginSessionSeq != session) {
                if (loginSessionSeq == session) loginRunning = false;
                return;
            }
            try {
                String[] t = form("https://id.twitch.tv/oauth2/token",
                        "client_id=" + CLIENT_ID + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                                + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8"));
                JSONObject tj = parse(t[1]);
                if (tj != null && tj.has("access_token")) {
                    KizuSettings.TV_TOKEN.save(tj.getString("access_token"));
                    if (loginSessionSeq == session) loginRunning = false;
                    MAIN.post(() -> {
                        try { dlg.dismiss(); } catch (Throwable ignored) {}
                        load(c, null, null, null);
                    });
                    return;
                }
                String m = tj == null ? "" : tj.optString("message", "");
                if (m.contains("pending")) {
                    scheduleLoginPoll(c, dlg, msg, closed, pollTask, session, deviceCode, intervalSec, untilMs);
                    return;
                }
                if (m.contains("slow")) {
                    scheduleLoginPoll(c, dlg, msg, closed, pollTask, session, deviceCode, intervalSec + 2, untilMs);
                    return;
                }
                if (!m.isEmpty()) {
                    final String fm = m;
                    if (loginSessionSeq == session) loginRunning = false;
                    MAIN.post(() -> { if (!closed[0]) msg.setText("ログイン失敗: " + fm); });
                    return;
                }
                scheduleLoginPoll(c, dlg, msg, closed, pollTask, session, deviceCode, intervalSec, untilMs);
            } catch (Throwable e) {
                if (loginSessionSeq == session) loginRunning = false;
                MAIN.post(() -> { if (!closed[0]) msg.setText("ログイン失敗: " + e); });
            }
        }), intervalSec, TimeUnit.SECONDS);
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

    private static void load(Context c, Dialog existingDlg, LinearLayout existingRoot, TextView refreshBtn) {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) {
            if (refreshBtn != null) {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("更新");
            }
            info(c, "予想", "配信を開いてから押してください。");
            return;
        }
        KizuRuntime.ioExecutor().execute(() -> {
            State st = fetchState(channelId);
            MAIN.post(() -> {
                try {
                    if (existingDlg != null) {
                        if (!existingDlg.isShowing()) return;
                        if (st.error != null) {
                            if (refreshBtn != null) {
                                refreshBtn.setEnabled(true);
                                refreshBtn.setText("更新");
                            }
                            info(c, "予想", st.error);
                        } else {
                            renderBetContent(c, density(c), existingDlg, existingRoot, st);
                        }
                        return;
                    }
                    if (st.error != null) info(c, "予想", st.error);
                    else showBet(c, st);
                } catch (Throwable t) {
                    Log.e(TAG, "show failed", t);
                    if (refreshBtn != null) {
                        refreshBtn.setEnabled(true);
                        refreshBtn.setText("更新");
                    }
                    info(c, "予想", "表示に失敗しました: " + t);
                }
            });
        });
    }

    // ---------- 賭け画面（Twitch風） ----------

    private static int colorOf(JSONObject o, int index) {
        String col = o == null ? "" : o.optString("color", "");
        if ("PINK".equalsIgnoreCase(col)) return PINK;
        if ("BLUE".equalsIgnoreCase(col)) return BLUE;
        return index == 1 ? PINK : BLUE;
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
        int pad = dp(d, 16);
        root.setPadding(pad, dp(d, 2), pad, pad);

        dlg.setOnDismissListener(x -> cancelCountdown(root));
        renderBetContent(c, d, dlg, root, st);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setBackground(TwitchUi.sheetBackground(c, 16f));
        sv.setClipToOutline(true);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        placeOverChat(dlg, c, d);
        dlg.show();
        placeOverChat(dlg, c, d);
    }

    private static void renderBetContent(Context c, float d, Dialog dlg, LinearLayout root, State st) {
        cancelCountdown(root);
        root.removeAllViews();

        int primary = SettingsUi.primaryTextColor(c);
        int secondary = SettingsUi.secondaryTextColor(c);
        int accent = SettingsUi.accentColor(c);

        root.addView(TwitchUi.dragHandle(c));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(d, 4), 0, 0);
        ImageView icon = new ImageView(c);
        int ic = Utils.getResourceId(c, "ic_prediction_event", "drawable");
        if (ic != 0) {
            icon.setImageResource(ic);
            icon.setColorFilter(accent);
        }
        top.addView(icon, new LinearLayout.LayoutParams(dp(d, 18), dp(d, 18)));
        TextView head = label(c, "  予想" + (st.name != null ? " · " + st.name : ""), 12f, secondary, true);
        top.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(TwitchUi.createCloseButton(c, dlg::dismiss));
        root.addView(top);

        JSONObject ev = st.event;
        if (ev == null) {
            TextView empty = label(c, "今は予想がありません", 16f, primary, true);
            empty.setPadding(0, dp(d, 16), 0, dp(d, 4));
            root.addView(empty);
        } else {
            buildEvent(c, d, dlg, root, st, ev);
        }

        // フッター
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(d, 0.75f)));
        divLp.topMargin = dp(d, 14);
        View div = TwitchUi.divider(c);
        root.addView(div, divLp);

        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(d, 8), 0, 0);

        TextView bal = label(c, st.balance >= 0 ? "所持 " + num(st.balance) + " pt" : "", 13f, secondary, false);
        bal.setFontFeatureSettings("tnum");
        foot.addView(bal, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView refresh = TwitchUi.createGhostButton(c, "更新", accent);
        refresh.setPadding(dp(d, 10), dp(d, 6), dp(d, 10), dp(d, 6));
        refresh.setOnClickListener(v -> {
            refresh.setEnabled(false);
            refresh.setText("更新中…");
            load(c, dlg, root, refresh);
        });
        foot.addView(refresh);

        TextView hist = TwitchUi.createGhostButton(c, "履歴", accent);
        hist.setPadding(dp(d, 10), dp(d, 6), dp(d, 4), dp(d, 6));
        hist.setOnClickListener(v -> showHistory(c, st));
        foot.addView(hist);
        root.addView(foot);

        TextView logout = TwitchUi.createGhostButton(c, "Kizu用ログインを解除", secondary);
        logout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        logout.setTypeface(Typeface.DEFAULT);
        logout.setGravity(Gravity.LEFT);
        logout.setPadding(0, dp(d, 6), dp(d, 8), dp(d, 2));
        logout.setOnClickListener(v -> {
            KizuSettings.TV_TOKEN.save("");
            dlg.dismiss();
        });
        root.addView(logout, new LinearLayout.LayoutParams(-2, -2));
    }

    private static void cancelCountdown(LinearLayout root) {
        Object tag = root.getTag();
        if (tag instanceof Runnable prev) {
            MAIN.removeCallbacks(prev);
            root.setTag(null);
        }
    }

    private static void buildEvent(Context c, float d, Dialog dlg, LinearLayout root, State st, JSONObject ev) {
        int primary = SettingsUi.primaryTextColor(c);
        int secondary = SettingsUi.secondaryTextColor(c);
        int cardBg = SettingsUi.alternateBackgroundColor(c);
        int borderCol = SettingsUi.borderColor(c);
        boolean dark = SettingsUi.isDark(c);

        String status = ev.optString("status");
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        long endMillis = active ? parseEventEndMillis(ev) : -1L;

        TextView titleView = label(c, ev.optString("title"), 17f, primary, true);
        titleView.setPadding(0, dp(d, 8), 0, 0);
        root.addView(titleView);

        String state;
        if (active) {
            if (endMillis > 0L) {
                long left = Math.max(0L, (endMillis - System.currentTimeMillis()) / 1000L);
                state = String.format(Locale.US, "受付中 · 残り %d:%02d", left / 60, left % 60);
            } else {
                state = "受付中";
            }
        } else if ("LOCKED".equalsIgnoreCase(status)) {
            state = "締め切り · 結果待ち";
        } else {
            state = status;
        }
        TextView stateView = label(c, state, 13f, active ? SettingsUi.successColor() : secondary, true);
        stateView.setFontFeatureSettings("tnum");
        stateView.setPadding(0, dp(d, 2), 0, 0);
        root.addView(stateView);

        // 残り時間を1秒ごとに更新（ダイアログ表示中だけ、endMillis を事前計算）
        if (active && endMillis > 0L) {
            Runnable tick = new Runnable() {
                @Override public void run() {
                    if (!dlg.isShowing()) return;
                    long left = Math.max(0L, (endMillis - System.currentTimeMillis()) / 1000L);
                    if (left > 0L) {
                        stateView.setText(String.format(Locale.US, "受付中 · 残り %d:%02d（%d秒）", left / 60, left % 60, left));
                        stateView.setTextColor(left <= 10 ? SettingsUi.dangerColor() : SettingsUi.successColor());
                        MAIN.postDelayed(this, 1000L);
                    } else {
                        stateView.setText("締め切り · 結果待ち");
                        stateView.setTextColor(secondary);
                    }
                }
            };
            root.setTag(tick);
            MAIN.post(tick);
        }

        JSONArray outs = ev.optJSONArray("outcomes");
        int n = outs == null ? 0 : outs.length();
        long total = 0;
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null) total += Math.max(0, o.optLong("totalPoints"));
        }

        // Twitch風割合バー
        if (n >= 2) {
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(TwitchUi.roundedRect(borderCol, dp(d, 3), 0, 0));
            bar.setClipToOutline(true);
            for (int i = 0; i < n; i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                int col = colorOf(o, i);
                long op = Math.max(0, o.optLong("totalPoints"));
                float weight = total > 0 ? Math.max(0.02f, (float) op) : 1f;
                int segColor = total > 0 ? col : Color.argb(0x66, Color.red(col), Color.green(col), Color.blue(col));
                View seg = new View(c);
                seg.setBackgroundColor(segColor);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, weight);
                if (i > 0) sp.leftMargin = Math.max(1, dp(d, 1.5f));
                bar.addView(seg, sp);
            }
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(d, 6));
            barLp.topMargin = dp(d, 10);
            root.addView(bar, barLp);
        }

        JSONObject mine = myPrediction(st, ev.optString("id"));
        String mineOutcome = mine == null || mine.optJSONObject("outcome") == null
                ? null : mine.optJSONObject("outcome").optString("id");

        final int[] selected = {-1};
        final LinearLayout[] cards = new LinearLayout[n];
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(n == 2 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        list.setPadding(0, dp(d, 10), 0, 0);
        int cp = dp(d, 12);
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o == null) continue;
            int col = colorOf(o, i);
            long op = Math.max(0, o.optLong("totalPoints"));
            int pct = total > 0 ? (int) Math.round(op * 100.0 / total) : 0;

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(cp, cp, cp, cp);
            card.setBackground(TwitchUi.ripple(
                    c,
                    TwitchUi.roundedRect(cardBg, 10 * d, Math.max(1, dp(d, 1)), borderCol),
                    10 * d));

            TextView t = label(c, o.optString("title"), 14f, primary, true);
            t.setMaxLines(2);
            card.addView(t);

            TextView pctView = label(c, pct + "%", 23f, col, true);
            pctView.setFontFeatureSettings("tnum");
            pctView.setPadding(0, dp(d, 2), 0, 0);
            card.addView(pctView);

            String ratio = op > 0 ? String.format(Locale.US, "1:%.2f", (double) total / op) : "1:—";
            TextView ratioView = label(c, "倍率 " + ratio, 12.5f, col, true);
            ratioView.setFontFeatureSettings("tnum");
            card.addView(ratioView);

            TextView ptsView = label(c, num(op) + " pt · " + num(o.optInt("totalUsers")) + "人",
                    11.5f, secondary, false);
            ptsView.setFontFeatureSettings("tnum");
            ptsView.setPadding(0, dp(d, 2), 0, 0);
            card.addView(ptsView);

            if (o.optString("id").equals(mineOutcome)) {
                TextView m = label(c, "あなた " + num(mine.optLong("points")) + " pt", 11.5f, Color.WHITE, true);
                m.setFontFeatureSettings("tnum");
                m.setPadding(dp(d, 7), dp(d, 2), dp(d, 7), dp(d, 2));
                m.setBackground(TwitchUi.roundedRect(col, 6 * d, 0, 0));
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
                mp.topMargin = dp(d, 6);
                card.addView(m, mp);
            }

            LinearLayout.LayoutParams lp = n == 2
                    ? new LinearLayout.LayoutParams(0, -1, 1f)
                    : new LinearLayout.LayoutParams(-1, -2);
            if (n == 2 && i == 0) lp.rightMargin = dp(d, 5);
            if (n == 2 && i == 1) lp.leftMargin = dp(d, 5);
            if (n != 2 && i > 0) lp.topMargin = dp(d, 8);
            cards[i] = card;
            list.addView(card, lp);
        }
        root.addView(list);

        if (!active) {
            if (mine == null) {
                TextView notBet = label(c, "この予想には賭けていません", 13f, secondary, false);
                notBet.setPadding(0, dp(d, 10), 0, 0);
                root.addView(notBet);
            }
            return;
        }

        // 賭ける量
        LinearLayout amount = new LinearLayout(c);
        amount.setOrientation(LinearLayout.HORIZONTAL);
        amount.setGravity(Gravity.CENTER_VERTICAL);
        amount.setPadding(0, dp(d, 14), 0, 0);
        EditText pts = TwitchUi.createPillInput(c, "ポイント");
        pts.setInputType(InputType.TYPE_CLASS_NUMBER);
        pts.setSingleLine(true);
        pts.setTypeface(Typeface.DEFAULT_BOLD);
        pts.setFontFeatureSettings("tnum");
        pts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        pts.setText(String.valueOf(KizuSettings.PREDICTION_POINTS.get()));
        amount.addView(pts, new LinearLayout.LayoutParams(0, -2, 1f));

        String[] quick = {"+10", "+100", "+1000", "全部"};
        for (String q : quick) {
            TextView b = label(c, q, 12.5f, primary, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(d, 9), dp(d, 9), dp(d, 9), dp(d, 9));
            b.setBackground(TwitchUi.ripple(
                    c,
                    TwitchUi.roundedRect(cardBg, 8 * d, Math.max(1, dp(d, 0.75f)), borderCol),
                    8 * d));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = dp(d, 5);
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

        TextView expect = label(c, "", 12f, secondary, false);
        expect.setFontFeatureSettings("tnum");
        expect.setPadding(0, dp(d, 6), 0, 0);
        root.addView(expect);

        TextView betBtn = label(c, "選択肢を選んでください", 15f, secondary, true);
        betBtn.setGravity(Gravity.CENTER);
        betBtn.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12));
        betBtn.setBackground(TwitchUi.ripple(
                c,
                TwitchUi.roundedRect(cardBg, 10 * d, Math.max(1, dp(d, 0.75f)), borderCol),
                10 * d));
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(-1, -2);
        bbp.topMargin = dp(d, 10);
        root.addView(betBtn, bbp);

        final long fTotal = total;
        Runnable update = () -> {
            for (int i = 0; i < n; i++) {
                if (cards[i] == null) continue;
                int col = colorOf(outs.optJSONObject(i), i);
                boolean isSel = i == selected[0];
                int fill = isSel
                        ? Color.argb(dark ? 0x26 : 0x16, Color.red(col), Color.green(col), Color.blue(col))
                        : cardBg;
                cards[i].setBackground(TwitchUi.ripple(
                        c,
                        TwitchUi.roundedRect(fill, 10 * d,
                                isSel ? dp(d, 2) : Math.max(1, dp(d, 1)),
                                isSel ? col : borderCol),
                        10 * d));
            }
            int s = selected[0];
            expect.setTextColor(secondary);
            if (s < 0) {
                expect.setText("");
                betBtn.setText("選択肢を選んでください");
                betBtn.setTextColor(secondary);
                betBtn.setBackground(TwitchUi.ripple(
                        c,
                        TwitchUi.roundedRect(cardBg, 10 * d, Math.max(1, dp(d, 0.75f)), borderCol),
                        10 * d));
                return;
            }
            JSONObject o = outs.optJSONObject(s);
            int col = colorOf(o, s);
            String title = o.optString("title");
            if (title.length() > 14) title = title.substring(0, 14) + "…";
            betBtn.setText("「" + title + "」に賭ける");
            betBtn.setTextColor(Color.WHITE);
            betBtn.setBackground(TwitchUi.ripple(c, TwitchUi.roundedRect(col, 10 * d, 0, 0), 10 * d));
            try {
                long p = Long.parseLong(pts.getText().toString().trim());
                long op = Math.max(0, o.optLong("totalPoints"));
                if (p <= 0) { expect.setText(""); return; }
                long win = Math.round((double) (fTotal + p) * p / (op + p));
                expect.setText("当たれば約 " + num(win) + " pt（+" + num(win - p) + "）※今の状況での目安");
            } catch (Throwable t) {
                expect.setText("");
            }
        };
        for (int i = 0; i < n; i++) {
            if (cards[i] == null) continue;
            final int idx = i;
            cards[i].setOnClickListener(v -> {
                selected[0] = idx;
                update.run();
            });
        }
        for (int i = 0; i < n; i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optString("id").equals(mineOutcome)) selected[0] = i;
        }
        pts.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int x, int y) {}
            public void onTextChanged(CharSequence a, int b, int x, int y) {}
            public void afterTextChanged(Editable e) { update.run(); }
        });
        update.run();

        final String eventId = ev.optString("id");
        betBtn.setOnClickListener(v -> {
            int s = selected[0];
            if (s < 0) {
                expect.setTextColor(SettingsUi.dangerColor());
                expect.setText("選択肢をタップして選んでください");
                return;
            }
            int p;
            try { p = Integer.parseInt(pts.getText().toString().trim()); } catch (Throwable t) { p = 0; }
            if (p < 1) {
                expect.setTextColor(SettingsUi.dangerColor());
                expect.setText("ポイントを入れてください");
                return;
            }
            if (st.balance >= 0 && p > st.balance) p = st.balance;
            final int fp = p;
            final String outcomeId = outs.optJSONObject(s).optString("id");
            betBtn.setEnabled(false);
            betBtn.setText("送信中…");
            KizuRuntime.ioExecutor().execute(() -> {
                String[] result = bet(eventId, outcomeId, fp);
                MAIN.post(() -> {
                    if ("ok".equals(result[0])) {
                        watch(c.getApplicationContext(), st.channelId, eventId);
                        Toast.makeText(c, result[1], Toast.LENGTH_SHORT).show();
                        if (dlg.isShowing()) {
                            load(c, dlg, root, null);
                        } else {
                            load(c, null, null, null);
                        }
                    } else {
                        betBtn.setEnabled(true);
                        update.run();
                        info(c, "予想", result[1]);
                    }
                });
            });
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
        int primary = SettingsUi.primaryTextColor(c);
        int secondary = SettingsUi.secondaryTextColor(c);
        int cardBg = SettingsUi.alternateBackgroundColor(c);
        int borderCol = SettingsUi.borderColor(c);
        boolean dark = SettingsUi.isDark(c);

        Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(d, 16);
        root.setPadding(pad, dp(d, 2), pad, pad);

        root.addView(TwitchUi.dragHandle(c));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(d, 4), 0, 0);
        top.addView(label(c, "賭けの履歴", 17f, primary, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(TwitchUi.createCloseButton(c, dlg::dismiss));
        root.addView(top);

        if (st.recent == null) {
            TextView empty = label(c, "履歴を取得できませんでした。", 14f, secondary, false);
            empty.setPadding(0, dp(d, 12), 0, 0);
            root.addView(empty);
        } else {
            long since = parseTime(st.streamStart);
            long streamNet = 0, allNet = 0;
            int streamCount = 0, wins = 0, decided = 0;
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
            LinearLayout summaryCard = new LinearLayout(c);
            summaryCard.setOrientation(LinearLayout.VERTICAL);
            int spPad = dp(d, 12);
            summaryCard.setPadding(spPad, spPad, spPad, spPad);
            summaryCard.setBackground(TwitchUi.roundedRect(cardBg, 10 * d, Math.max(1, dp(d, 0.75f)), borderCol));
            LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(-1, -2);
            scp.topMargin = dp(d, 10);

            TextView sum = label(c, scope + "の累計  " + sign(streamNet) + " pt",
                    19f, streamNet >= 0 ? SettingsUi.successColor() : SettingsUi.dangerColor(), true);
            sum.setFontFeatureSettings("tnum");
            summaryCard.addView(sum);

            TextView sub = label(c, streamCount + "回 · 的中 " + wins + "/" + decided
                    + "　（最近の全体 " + sign(allNet) + " pt）", 12f, secondary, false);
            sub.setFontFeatureSettings("tnum");
            sub.setPadding(0, dp(d, 3), 0, 0);
            summaryCard.addView(sub);
            root.addView(summaryCard, scp);

            if (rows.isEmpty()) {
                TextView empty = label(c, scope + "ではまだ賭けていません", 14f, secondary, false);
                empty.setPadding(0, dp(d, 14), 0, 0);
                root.addView(empty);
            }
            for (JSONObject p : rows) {
                JSONObject e = p.optJSONObject("event");
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                int cp = dp(d, 12);
                card.setPadding(cp, cp, cp, cp);
                card.setBackground(TwitchUi.roundedRect(cardBg, 10 * d, Math.max(1, dp(d, 0.75f)), borderCol));

                card.addView(label(c, e == null ? "予想" : e.optString("title"), 14f, primary, true));

                String oid = p.optJSONObject("outcome") == null ? "" : p.optJSONObject("outcome").optString("id");
                String otitle = "";
                int col = BLUE;
                JSONArray outs = e == null ? null : e.optJSONArray("outcomes");
                for (int i = 0; outs != null && i < outs.length(); i++) {
                    JSONObject o = outs.optJSONObject(i);
                    if (o != null && o.optString("id").equals(oid)) {
                        otitle = o.optString("title");
                        col = colorOf(o, i);
                    }
                }
                TextView pickLine = label(c, "● " + otitle + "　" + num(p.optLong("points")) + " pt", 13f, col, true);
                pickLine.setFontFeatureSettings("tnum");
                pickLine.setPadding(0, dp(d, 4), 0, 0);
                card.addView(pickLine);

                String r = p.optString("result", "");
                String line;
                int lc;
                if (!resolved(p)) {
                    line = "結果待ち";
                    lc = secondary;
                } else if ("WIN".equalsIgnoreCase(r)) {
                    line = "的中  " + sign(net(p)) + " pt";
                    lc = SettingsUi.successColor();
                } else if ("REFUND".equalsIgnoreCase(r)) {
                    line = "返金  ±0 pt";
                    lc = secondary;
                } else {
                    line = "ハズレ  " + sign(net(p)) + " pt";
                    lc = SettingsUi.dangerColor();
                }
                TextView statusBadge = label(c, line, 12.5f, lc, true);
                statusBadge.setFontFeatureSettings("tnum");
                int pillBg = Color.argb(dark ? 0x22 : 0x16, Color.red(lc), Color.green(lc), Color.blue(lc));
                statusBadge.setBackground(TwitchUi.roundedRect(pillBg, 6 * d, 0, 0));
                statusBadge.setPadding(dp(d, 8), dp(d, 3), dp(d, 8), dp(d, 3));
                LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-2, -2);
                sbp.topMargin = dp(d, 6);
                card.addView(statusBadge, sbp);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(d, 8);
                root.addView(card, lp);
            }
        }

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setBackground(TwitchUi.sheetBackground(c, 16f));
        sv.setClipToOutline(true);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        placeOverChat(dlg, c, d);
        dlg.show();
        placeOverChat(dlg, c, d);
    }

    /** プレイヤーの下（コメント欄の位置）にだけ重ねる。プレイヤーが見つからなければ画面下60%。 */
    private static void placeOverChat(Dialog dlg, Context c, float d) {
        Window w = dlg.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        int top = -1;
        Activity a = Utils.findActivity(c);
        if (a != null) {
            int id = Utils.getResourceId(a, "player_overlay_container", "id");
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
        int width = landscape ? Math.min(dm.widthPixels, dp(d, 420)) : WindowManager.LayoutParams.MATCH_PARENT;
        w.setGravity(Gravity.BOTTOM | (landscape ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL));
        w.getDecorView().setPadding(0, 0, 0, 0);
        w.setLayout(width, h);
    }

    private static String sign(long v) {
        return (v > 0 ? "+" : v < 0 ? "-" : "±") + num(Math.abs(v));
    }

    private static long parseTime(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        try { return Instant.parse(s).toEpochMilli(); } catch (Throwable t) { return 0; }
    }

    // ---------- 結果の見張り（賭けたときだけ、30秒ごと） ----------

    private static final Set<String> WATCHING = Collections.synchronizedSet(new HashSet<>());

    private static void watch(Context app, String channelId, String eventId) {
        if (eventId == null || !WATCHING.add(eventId)) return;
        long until = System.currentTimeMillis() + 3L * 3600_000L;
        scheduleWatchPoll(app, channelId, eventId, until);
    }

    private static void scheduleWatchPoll(Context app, String channelId, String eventId, long until) {
        if (System.currentTimeMillis() >= until) {
            WATCHING.remove(eventId);
            return;
        }
        KizuRuntime.scheduler().schedule(() -> KizuRuntime.ioExecutor().execute(() -> {
            if (System.currentTimeMillis() >= until || KizuSettings.TV_TOKEN.get().trim().isEmpty()) {
                WATCHING.remove(eventId);
                return;
            }
            try {
                JSONObject req = new JSONObject()
                        .put("operationName", "KizuRecentPredictions")
                        .put("variables", new JSONObject().put("id", channelId))
                        .put("query", "query KizuRecentPredictions($id: ID!) { user(id: $id) { channel { self {"
                                + " recentPredictions { points pointsWon result event { id title } } } } } }");
                JSONObject res = parse(gql(req.toString())[1]);
                JSONObject self = res == null ? null : path(res, "data", "user", "channel", "self");
                JSONArray rec = self == null ? null : self.optJSONArray("recentPredictions");
                if (rec != null) {
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
                        WATCHING.remove(eventId);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
            scheduleWatchPoll(app, channelId, eventId, until);
        }), 30L, TimeUnit.SECONDS);
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
                    if ("401".equals(r[0]) || "401".equals(r2[0])) {
                        KizuSettings.TV_TOKEN.save("");
                        return new String[]{"ng", "ログインの期限が切れました。もう一度ログインしてください。"};
                    }
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
                    while ((n = s.read(buf)) != -1 && total < 256 * 1024) {
                        total += n;
                        out.write(buf, 0, n);
                    }
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
        for (String k : keys) {
            if (o == null) return null;
            o = o.optJSONObject(k);
        }
        return o;
    }

    private static long parseEventEndMillis(JSONObject ev) {
        try {
            long created = Instant.parse(ev.optString("createdAt")).toEpochMilli();
            return created + ev.optLong("predictionWindowSeconds") * 1000L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    private static int dp(float d, int value) {
        return Math.round(value * d);
    }

    private static int dp(float d, float value) {
        return Math.round(value * d);
    }

    private static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static void info(Context c, String title, String m) {
        try {
            TwitchUi.showAlert(c, title, m);
        } catch (Throwable ignored) {}
    }
}
