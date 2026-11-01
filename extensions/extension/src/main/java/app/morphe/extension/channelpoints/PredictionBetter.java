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

    private static volatile boolean started;

    private PredictionBetter() {}

    public static synchronized void start(Context context) {
        if (started || context == null) return;
        started = true;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (KizuSettings.PREDICTION_AUTO.get()) tick(app);
                } catch (Throwable e) {
                    Log.e(TAG, "tick failed", e);
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "kizu-prediction");
        t.setDaemon(true);
        t.start();
    }

    private static void tick(Context context) throws Exception {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) return;
        String token = readAuthToken(context);
        if (token.isEmpty()) return;

        JSONObject req = new JSONObject()
                .put("operationName", "CommunityPointsPredictionQuery")
                .put("variables", new JSONObject().put("id", channelId))
                .put("query", EVENTS_QUERY);
        JSONObject res = parse(post(token, req.toString()));
        if (res == null) return;
        JSONObject data = res.optJSONObject("data");
        JSONObject user = data == null ? null : data.optJSONObject("user");
        JSONObject channel = user == null ? null : user.optJSONObject("channel");
        JSONArray events = channel == null ? null : channel.optJSONArray("activePredictionEvents");
        if (events == null) return;

        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;
            String eventId = ev.optString("id", "");
            if (eventId.isEmpty() || DONE.contains(eventId)) continue;
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
        JSONObject res = parse(post(token, req.toString()));
        if (res == null) return; // 通信失敗は次回やり直す
        JSONObject data = res.optJSONObject("data");
        JSONObject mp = data == null ? null : data.optJSONObject("makePrediction");
        if (mp == null) return;
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
            Log.i(TAG, "bet error " + code + " event=" + eventId);
            DONE.add(eventId);
            return;
        }
        DONE.add(eventId);
        Log.i(TAG, "bet ok event=" + eventId + " points=" + points);
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

    private static String post(String token, String body) {
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
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.getOutputStream().write(b);
            if (c.getResponseCode() / 100 != 2) return null;
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
