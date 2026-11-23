package app.morphe.extension.channelpoints;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;
import io.github.bakwudo.uyu.extension.settings.Setting;

/** 視聴中チャンネルの予想（賭け）に、設定した番号・ポイントで自動参加する */
public final class PredictionBetter {
    private static final String TAG = "KizuPrediction";
    private static final long POLL_MS = 10_000L;
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    private static final String EVENTS_QUERY =
            "query CommunityPointsPredictionQuery($id: ID!) { user(id: $id) { channel {"
            + " activePredictionEvents { id status createdAt predictionWindowSeconds title"
            + " outcomes { id title } } } } }";

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

    private static Context appContext;
    private static ScheduledFuture<?> pollFuture;
    private static boolean listenerRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || KizuSettings.PREDICTION_AUTO.key.equals(key)) {
                    syncSchedule();
                }
            };

    private PredictionBetter() {}

    public static synchronized void start(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (!listenerRegistered) {
            listenerRegistered = true;
            Setting.addChangeListener(PREF_LISTENER);
        }
        syncSchedule();
    }

    private static synchronized void syncSchedule() {
        if (appContext == null) return;
        boolean enabled = false;
        try {
            enabled = KizuSettings.PREDICTION_AUTO.get();
        } catch (Throwable ignored) {}

        if (!enabled) {
            if (pollFuture != null) {
                pollFuture.cancel(false);
                pollFuture = null;
            }
            return;
        }
        if (pollFuture != null) return;
        final Context app = appContext;
        pollFuture = KizuRuntime.scheduler().scheduleWithFixedDelay(() -> {
            try {
                if (KizuSettings.PREDICTION_AUTO.get() && Utils.isAppInForeground()) {
                    tick(app);
                }
            } catch (Throwable e) {
                Log.e(TAG, "tick failed", e);
            }
        }, 0L, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private static void tick(Context context) throws Exception {
        String channelId = ChannelPoints.currentChannelId();
        if (channelId == null) return;
        String token = KizuRuntime.readAuthToken(context);
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
        if (res == null) return;
        JSONObject data = res.optJSONObject("data");
        JSONObject mp = data == null ? null : data.optJSONObject("makePrediction");
        if (mp == null) return;
        JSONObject err = mp.optJSONObject("error");
        if (err != null) {
            String code = err.optString("code", "");
            int max = err.optInt("maxPointsPerEvent", 0);
            int spent = err.optInt("userPointsSpent", 0);
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
            if (c.getResponseCode() / 100 != 2) {
                drainQuietly(c.getErrorStream());
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
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        try (InputStream s = stream) {
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < 8192 && (n = s.read(buf)) != -1) total += n;
        } catch (Throwable ignored) {}
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
