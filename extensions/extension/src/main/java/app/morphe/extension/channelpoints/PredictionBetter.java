package app.morphe.extension.channelpoints;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** 視聴中チャンネルの予想（賭け）に、設定した番号・ポイントで自動参加する */
public final class PredictionBetter {
    private static final String TAG = "KizuPrediction";
    private static final long POLL_MS = 10_000L;
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    // Twitch 31.3.1 (aj9) CommunityPointsPredictionQuery を必要な項目だけにしたもの
    private static final String EVENTS_QUERY =
            "query CommunityPointsPredictionQuery($id: ID!) { user(id: $id) { channel {"
            + " activePredictionEvents { id status createdAt predictionWindowSeconds title"
            + " outcomes { id title } } } } }";

    // Twitch 31.3.1 (iio) MakeCommunityPointsPredictionMutation
    private static final String BET_MUTATION =
            "mutation MakeCommunityPointsPredictionMutation($input: MakePredictionInput!) {"
            + " makePrediction(input: $input) { error { code maxPointsPerEvent userPointsSpent }"
            + " prediction { id points outcome { id } } } }";

    private static final Set<String> DONE = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 200;
                }
            });

    /** 同じ予想で失敗した回数。3回でやめる */
    private static final Map<String, Integer> FAILS = new LinkedHashMap<String, Integer>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 100; }
    };
    private static final int MAX_FAILS = 3;

    private static volatile boolean started;
    private static volatile Thread worker;
    private static volatile Context appContext;
    private static volatile boolean manualRun;
    private static volatile String lastNoEventChannel;

    private PredictionBetter() {}

    public static synchronized void start(Context context) {
        if (started || context == null) return;
        started = true;
        final Context app = context.getApplicationContext();
        appContext = app;
        CpLog.init(app);
        Thread t = new Thread(() -> {
            while (true) {
                boolean manual = manualRun;
                manualRun = false;
                try {
                    if (manual || KizuSettings.PREDICTION_AUTO.get()) tick(app, manual);
                } catch (Throwable e) {
                    Log.e(TAG, "tick failed", e);
                    CpLog.add("予想: 処理エラー " + e);
                }
                Thread.interrupted(); // 「今すぐ確認」の割り込みを消してから待つ
                if (manualRun) continue;
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ignored) {
                    // 「今すぐ確認」で起こされた
                }
            }
        }, "kizu-prediction");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 予想ボタンの画面の「今すぐ確認」。自動参加がオフでも1回だけ確認して賭ける */
    public static void runNow() {
        manualRun = true;
        synchronized (FAILS) { FAILS.clear(); }
        lastNoEventChannel = null;
        Thread t = worker;
        if (t != null) t.interrupt();
        else if (appContext != null) start(appContext);
        CpLog.add("予想: 今すぐ確認を開始（Integrity: "
                + (!IntegrityBridge.hooked() ? "フック未接続" : IntegrityBridge.token() == null ? "トークンなし" : "トークンあり") + "）");
    }

    private static void tick(Context context, boolean manual) throws Exception {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) {
            if (manual) CpLog.add("予想: 視聴中のチャンネルが分かりません（配信を開いてから試してください）");
            return;
        }
        String token = readAuthToken(context);
        if (token.isEmpty()) {
            if (manual) CpLog.add("予想: ログイン情報が読めません");
            return;
        }

        JSONObject req = new JSONObject()
                .put("operationName", "CommunityPointsPredictionQuery")
                .put("variables", new JSONObject().put("id", channelId))
                .put("query", EVENTS_QUERY);
        JSONObject res = parse(post(token, req.toString(), "予想の取得"));
        if (res == null) return;
        logErrors(res, "予想の取得");
        JSONObject data = res.optJSONObject("data");
        JSONObject user = data == null ? null : data.optJSONObject("user");
        JSONObject channel = user == null ? null : user.optJSONObject("channel");
        JSONArray events = channel == null ? null : channel.optJSONArray("activePredictionEvents");
        if (events == null) return;
        if (events.length() == 0) {
            if (manual || !channelId.equals(lastNoEventChannel)) CpLog.add("予想: 進行中の予想はありません (ch " + channelId + ")");
            lastNoEventChannel = channelId;
            return;
        }
        lastNoEventChannel = null;

        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;
            String eventId = ev.optString("id", "");
            if (eventId.isEmpty() || DONE.contains(eventId)) continue;
            synchronized (FAILS) {
                Integer f = FAILS.get(eventId);
                if (f != null && f >= MAX_FAILS) continue;
            }
            if (!"ACTIVE".equalsIgnoreCase(ev.optString("status", ""))) continue;
            JSONArray outcomes = ev.optJSONArray("outcomes");
            if (outcomes == null || outcomes.length() == 0) continue;

            int index = Math.max(1, KizuSettings.PREDICTION_OUTCOME.get());
            if (index > outcomes.length()) index = outcomes.length();
            JSONObject outcome = outcomes.optJSONObject(index - 1);
            if (outcome == null) continue;
            int points = Math.max(1, KizuSettings.PREDICTION_POINTS.get());
            bet(token, eventId, outcome.optString("id", ""), points);
        }
    }

    private static void bet(String token, String eventId, String outcomeId, int points) throws Exception {
        if (outcomeId.isEmpty()) return;
        JSONObject input = new JSONObject()
                .put("eventID", eventId)
                .put("outcomeID", outcomeId)
                .put("points", points)
                .put("transactionID", UUID.randomUUID().toString());
        JSONObject req = new JSONObject()
                .put("operationName", "MakeCommunityPointsPredictionMutation")
                .put("variables", new JSONObject().put("input", input))
                .put("query", BET_MUTATION);
        JSONObject res = parse(post(token, req.toString(), "賭け"));
        if (res == null) { fail(eventId); return; } // 通信失敗は次回やり直す（3回まで）
        boolean hadErrors = logErrors(res, "賭け");
        JSONObject data = res.optJSONObject("data");
        JSONObject mp = data == null ? null : data.optJSONObject("makePrediction");
        if (mp == null) {
            if (!hadErrors) CpLog.add("予想: 賭けの応答が空でした");
            fail(eventId);
            return;
        }
        JSONObject err = mp.optJSONObject("error");
        if (err != null) {
            String code = err.optString("code", "");
            int max = err.optInt("maxPointsPerEvent", 0);
            int spent = err.optInt("userPointsSpent", 0);
            // 上限を超えたときは上限で1回だけやり直す
            if (max > 0 && points > max - spent && max - spent > 0 && !"DUPLICATE_PREDICTION".equals(code)) {
                Log.i(TAG, "retry with max " + (max - spent));
                DONE.add(eventId);
                bet(token, eventId, outcomeId, max - spent);
                return;
            }
            CpLog.add("予想: 賭けを断られました (" + code + ")");
            DONE.add(eventId);
            return;
        }
        DONE.add(eventId);
        CpLog.add("予想: " + points + "ポイント賭けました");
    }

    private static void fail(String eventId) {
        synchronized (FAILS) {
            Integer f = FAILS.get(eventId);
            int n = f == null ? 1 : f + 1;
            FAILS.put(eventId, n);
            if (n >= MAX_FAILS) CpLog.add("予想: " + MAX_FAILS + "回失敗したので、この予想はあきらめます");
        }
    }

    /** GraphQL の errors を記録する。あれば true */
    private static boolean logErrors(JSONObject res, String what) {
        JSONArray errors = res.optJSONArray("errors");
        if (errors == null || errors.length() == 0) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.length() && i < 3; i++) {
            JSONObject e = errors.optJSONObject(i);
            if (e == null) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(e.optString("message", "?"));
        }
        CpLog.add("予想: " + what + "でエラー: " + sb
                + (IntegrityBridge.token() == null ? "（Integrityなし）" : "（Integrityあり）"));
        return true;
    }

    private static String readAuthToken(Context context) {
        File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = dir.listFiles();
        if (files == null) return "";
        for (File f : files) {
            if (f == null || !f.getName().endsWith(".xml")) continue;
            String name = f.getName().substring(0, f.getName().length() - 4);
            try {
                SharedPreferences p = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                String t = p.getString("authToken_v2", null);
                if (t != null && !t.trim().isEmpty()) return t.trim();
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String post(String token, String body, String what) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(GQL_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Client-ID", CLIENT_ID);
            c.setRequestProperty("Authorization", "OAuth " + token);
            String integrity = IntegrityBridge.token();
            if (integrity != null) c.setRequestProperty("Client-Integrity", integrity);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.getOutputStream().write(b);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                String err = "";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) {
                        byte[] eb = new byte[300];
                        int en = es.read(eb);
                        if (en > 0) err = new String(eb, 0, en, StandardCharsets.UTF_8);
                    }
                } catch (Throwable ignored) {}
                CpLog.add("予想: " + what + " HTTP " + code + " " + err.replace('\n', ' ')
                        + (integrity == null ? "（Integrityなし）" : "（Integrityあり）"));
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 512 * 1024) return null;
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } catch (Throwable e) {
            Log.i(TAG, "request failed: " + e);
            CpLog.add("予想: " + what + " 通信失敗 " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject parse(String s) {
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable e) {
            return null;
        }
    }
}
