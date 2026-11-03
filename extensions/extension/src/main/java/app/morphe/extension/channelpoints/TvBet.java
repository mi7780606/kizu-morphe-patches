package app.morphe.extension.channelpoints;

import android.app.AlertDialog;
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

    private static final String EVENTS_QUERY =
            "query KizuPrediction($id: ID!) { user(id: $id) { login channel {"
            + " self { communityPoints { balance } }"
            + " activePredictionEvents { id status title predictionWindowSeconds createdAt"
            + " outcomes { id title totalPoints totalUsers } } } } }";
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

    // ---------- 予想の読み込みと賭け ----------

    private static void load(Context c) {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) { info(c, "予想", "配信を開いてから押してください。"); return; }
        AlertDialog wait = new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("予想を読み込み中…").create();
        wait.show();
        new Thread(() -> {
            String err = null; JSONObject ev = null; int balance = -1;
            try {
                JSONObject req = new JSONObject()
                        .put("operationName", "KizuPrediction")
                        .put("variables", new JSONObject().put("id", channelId))
                        .put("query", EVENTS_QUERY);
                String[] r = gql(req.toString());
                JSONObject res = parse(r[1]);
                if ("401".equals(r[0])) { KizuSettings.TV_TOKEN.save(""); err = "ログインの期限が切れました。もう一度押してログインしてください。"; }
                else if (res == null) err = "通信失敗 (HTTP " + r[0] + ")";
                else {
                    JSONObject ch = path(res, "data", "user", "channel");
                    JSONObject cp = ch == null ? null : path(ch, "self", "communityPoints");
                    if (cp != null) balance = cp.optInt("balance", -1);
                    JSONArray evs = ch == null ? null : ch.optJSONArray("activePredictionEvents");
                    if (evs != null) {
                        for (int i = 0; i < evs.length(); i++) {
                            JSONObject e = evs.optJSONObject(i);
                            if (e == null) continue;
                            if (ev == null || "ACTIVE".equalsIgnoreCase(e.optString("status"))) ev = e;
                        }
                    }
                    if (ev == null) {
                        JSONArray errors = res.optJSONArray("errors");
                        err = errors != null && errors.length() > 0
                                ? "取得エラー: " + errors.optJSONObject(0).optString("message")
                                : "今は予想がありません。";
                    }
                }
            } catch (Throwable e) {
                err = "読み込み失敗: " + e;
            }
            final String fe = err; final JSONObject fev = ev; final int fb = balance;
            MAIN.post(() -> {
                try { wait.dismiss(); } catch (Throwable ignored) {}
                if (fe != null) info(c, "予想", fe);
                else showBet(c, fev, fb);
            });
        }, "kizu-tv-load").start();
    }

    private static void showBet(Context c, JSONObject ev, int balance) {
        float d = density(c);
        LinearLayout box = box(c, d);
        String status = ev.optString("status");
        box.addView(text(c, d, ev.optString("title"), 16));
        box.addView(text(c, d, ("ACTIVE".equalsIgnoreCase(status) ? "受付中" : "受付終了 (" + status + ")")
                + (balance >= 0 ? "　所持: " + balance + " pt" : ""), 13));

        RadioGroup group = new RadioGroup(c);
        JSONArray outs = ev.optJSONArray("outcomes");
        int def = Math.max(1, KizuSettings.PREDICTION_OUTCOME.get());
        if (outs != null) {
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                RadioButton rb = new RadioButton(c);
                rb.setId(View.generateViewId());
                rb.setTag(o.optString("id"));
                rb.setTextColor(Color.WHITE);
                rb.setText((i + 1) + ". " + o.optString("title") + "（" + o.optLong("totalPoints") + " pt / " + o.optInt("totalUsers") + "人）");
                group.addView(rb);
                if (i + 1 == Math.min(def, outs.length())) rb.setChecked(true);
            }
        }
        box.addView(group);
        box.addView(text(c, d, "賭けるポイント", 14));
        EditText pts = new EditText(c);
        pts.setInputType(InputType.TYPE_CLASS_NUMBER);
        pts.setSingleLine(true);
        pts.setTextColor(Color.WHITE);
        pts.setText(String.valueOf(KizuSettings.PREDICTION_POINTS.get()));
        box.addView(pts);

        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("予想に賭ける")
                .setView(sv)
                .setNegativeButton("閉じる", null)
                .setNeutralButton("ログアウト", (x, w) -> KizuSettings.TV_TOKEN.save(""))
                .setPositiveButton("賭ける", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            View sel = group.findViewById(group.getCheckedRadioButtonId());
            if (sel == null) { info(c, "予想", "選択肢を選んでください。"); return; }
            int p;
            try { p = Integer.parseInt(pts.getText().toString().trim()); } catch (Throwable t) { p = 0; }
            if (p < 1) { info(c, "予想", "ポイントを入れてください。"); return; }
            if (balance >= 0 && p > balance) p = balance;
            final int fp = p;
            final String outcomeId = String.valueOf(sel.getTag());
            v.setEnabled(false);
            new Thread(() -> {
                String result = bet(ev.optString("id"), outcomeId, fp);
                MAIN.post(() -> {
                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                    info(c, "予想", result);
                });
            }, "kizu-tv-bet").start();
        });
    }

    private static String bet(String eventId, String outcomeId, int points) {
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
                // 生クエリが拒否されたときは登録済みクエリ(ハッシュ)でもう一度
                JSONObject req2 = new JSONObject()
                        .put("operationName", "MakePrediction")
                        .put("variables", vars)
                        .put("extensions", new JSONObject().put("persistedQuery",
                                new JSONObject().put("version", 1).put("sha256Hash", BET_HASH)));
                String[] r2 = gql(req2.toString());
                JSONObject res2 = parse(r2[1]);
                mp = res2 == null ? null : path(res2, "data", "makePrediction");
                if (mp == null) {
                    if ("401".equals(r[0]) || "401".equals(r2[0])) { KizuSettings.TV_TOKEN.save(""); return "ログインの期限が切れました。もう一度ログインしてください。"; }
                    return "失敗: HTTP " + r[0] + " / " + r2[0] + "\n" + shorten(r[1]) + "\n" + shorten(r2[1]);
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
                return "賭けられませんでした: " + why;
            }
            JSONObject pr = mp.optJSONObject("prediction");
            return "賭けました: " + (pr != null ? pr.optInt("points", points) : points) + " pt";
        } catch (Throwable e) {
            return "失敗: " + e;
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

    private static void info(Context c, String title, String m) {
        try {
            TextView t = new TextView(c);
            new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title).setMessage(m).setPositiveButton("OK", null).show();
        } catch (Throwable ignored) {}
    }
}
