package app.morphe.extension.channelpoints;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import app.morphe.extension.ads.StreamProxy;
import app.morphe.extension.settings.Settings;

/**
 * UI-independent Channel Points bonus claimant for Twitch 31.3.1.
 * Uses the shared Kizu scheduler and cached auth-token lookup, only polling while active
 * in the foreground on a channel.
 */
public final class ChannelPoints {
    private static final String TAG = "kizu-cp";

    private static final long POLL_INTERVAL_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String GQL_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    private static final String CONTEXT_QUERY =
            "query CommunityPointsSettingsQuery($id: ID!) {" +
            " user(id: $id) {" +
            "  channel { communityPointsSettings { isEnabled isAvailable } }" +
            "  self {" +
            "   communityPoints {" +
            "    balance" +
            "    availableClaim {" +
            "     id pointsEarnedTotal pointsEarnedBaseline" +
            "     multipliers { factor reasonCode }" +
            "    }" +
            "    activeMultipliers { factor reasonCode }" +
            "   }" +
            "  }" +
            " }" +
            "}";

    private static final String CLAIM_QUERY =
            "mutation ClaimCommunityPointsMutation($input: ClaimCommunityPointsInput!) {" +
            " claimCommunityPoints(input: $input) {" +
            "  claim {" +
            "   id" +
            "   multipliers { factor reasonCode }" +
            "   pointsEarnedTotal" +
            "   pointsEarnedBaseline" +
            "  }" +
            "  error { code }" +
            " }" +
            "}";

    private static final String FALLBACK_CONTEXT_HASH =
            "1530a003a7d374b0380b79db0be0534f30ff46e61cffa2bc0e2468a909fbc024";
    private static final String FALLBACK_CLAIM_HASH =
            "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0";

    private static final Object STATE_LOCK = new Object();

    private static volatile String channelId;
    private static volatile String channelLogin;
    private static volatile String lastSuccessfulClaimKey;
    private static ScheduledFuture<?> pollFuture;

    private ChannelPoints() {}

    public static void start(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        synchronized (STATE_LOCK) {
            if (pollFuture != null) return;
            pollFuture = KizuRuntime.scheduler().scheduleWithFixedDelay(() -> {
                try {
                    if (Settings.AUTO_CLAIM_CHANNEL_POINTS.get() && Utils.isAppInForeground()) {
                        claimAvailable(app);
                    }
                } catch (Throwable error) {
                    log("watcher error: " + error);
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            log("GraphQL auto-claim watcher scheduled; poll=" + (POLL_INTERVAL_MS / 1000L) + "s");
        }
    }

    /** Kizu: 視聴中チャンネルのID（予想の自動参加・手動賭けで使う） */
    public static String currentChannelId() {
        return channelId;
    }

    /** Called by the existing stable ChannelChatConnectionKey(String,String) hook. */
    public static void onChannelChanged(String id, String login) {
        app.morphe.extension.watchlog.WatchTimeTracker.onChannelChanged(id, login);
        String newId = normalize(id);
        String newLogin = normalize(login);
        if (newId == null && newLogin == null) return;

        boolean changed = !same(newId, channelId) || !same(newLogin, channelLogin);
        channelId = newId;
        channelLogin = newLogin;

        if (changed) {
            lastSuccessfulClaimKey = null;
            log("active channel id=" + newId + " login=" + newLogin);
        }
        if (newLogin != null) {
            try { StreamProxy.prefetchSubscription(newLogin); } catch (Throwable ignored) {}
        }
    }

    private static void claimAvailable(Context context) {
        String id = channelId;
        String login = channelLogin;
        if (id == null || id.isEmpty() || login == null || login.isEmpty()) return;

        String token = KizuRuntime.readAuthToken(context);
        if (token.isEmpty()) return;

        JSONObject available = fetchAvailableClaim(token, id, login);
        if (available == null) return;

        String claimId = available.optString("id", "");
        if (claimId.isEmpty()) return;

        String claimKey = id + ":" + claimId;
        if (claimKey.equals(lastSuccessfulClaimKey)) return;

        log("bonus available channel=" + login + " claim=" + claimId);

        ClaimResult result = submitClaim(token, id, claimId);
        if (!result.success) {
            log("claim failed: " + result.error);
            Utils.showClaimStatus("Channel Points claim failed");
            return;
        }

        lastSuccessfulClaimKey = claimKey;
        String message = result.points > 0
                ? "Channel Points +" + result.points + " claimed"
                : "Channel Points +50 claimed";
        log("claim success: " + message);
        Utils.showClaimStatus(message);
    }

    private static JSONObject fetchAvailableClaim(String token, String id, String login) {
        try {
            JSONObject request = new JSONObject()
                    .put("operationName", "CommunityPointsSettingsQuery")
                    .put("variables", new JSONObject().put("id", id))
                    .put("query", CONTEXT_QUERY);

            JSONObject response = parse(post(token, request.toString()));
            if (response != null && !hasErrors(response)) {
                JSONObject data = response.optJSONObject("data");
                JSONObject user = data == null ? null : data.optJSONObject("user");
                JSONObject self = user == null ? null : user.optJSONObject("self");
                JSONObject points = self == null ? null : self.optJSONObject("communityPoints");
                if (points != null) {
                    // Valid response received; null availableClaim simply means no bonus chest is ready.
                    return points.optJSONObject("availableClaim");
                }
            }

            // Fallback only if the primary context query failed or omitted communityPoints.
            JSONObject fallback = new JSONObject()
                    .put("operationName", "ChannelPointsContext")
                    .put("variables", new JSONObject().put("channelLogin", login))
                    .put("extensions", new JSONObject().put(
                            "persistedQuery",
                            new JSONObject()
                                    .put("version", 1)
                                    .put("sha256Hash", FALLBACK_CONTEXT_HASH)));

            return extractPurpleClaim(parse(post(token, fallback.toString())));
        } catch (Throwable error) {
            log("context request error: " + error);
            return null;
        }
    }

    private static JSONObject extractPurpleClaim(JSONObject response) {
        if (response == null || hasErrors(response)) return null;

        JSONObject data = response.optJSONObject("data");
        JSONObject community = data == null ? null : data.optJSONObject("community");
        JSONObject channel = community == null ? null : community.optJSONObject("channel");
        JSONObject self = channel == null ? null : channel.optJSONObject("self");
        JSONObject points = self == null ? null : self.optJSONObject("communityPoints");
        return points == null ? null : points.optJSONObject("availableClaim");
    }

    private static ClaimResult submitClaim(String token, String id, String claimId) {
        try {
            JSONObject input = new JSONObject()
                    .put("channelID", id)
                    .put("claimID", claimId);

            JSONObject request = new JSONObject()
                    .put("operationName", "ClaimCommunityPointsMutation")
                    .put("variables", new JSONObject().put("input", input))
                    .put("query", CLAIM_QUERY);

            ClaimResult result = parseClaimResult(parse(post(token, request.toString())));
            if (result.success || result.authoritativeFailure) {
                return result;
            }

            JSONObject fallback = new JSONObject()
                    .put("operationName", "ClaimCommunityPoints")
                    .put("variables", new JSONObject().put("input", input))
                    .put("extensions", new JSONObject().put(
                            "persistedQuery",
                            new JSONObject()
                                    .put("version", 1)
                                    .put("sha256Hash", FALLBACK_CLAIM_HASH)));

            return parseClaimResult(parse(post(token, fallback.toString())));
        } catch (Throwable error) {
            return ClaimResult.failed(error.toString());
        }
    }

    private static ClaimResult parseClaimResult(JSONObject response) {
        if (response == null) return ClaimResult.retryable("invalid response");
        if (hasErrors(response)) return ClaimResult.retryable(firstError(response));

        JSONObject data = response.optJSONObject("data");
        JSONObject payload = data == null ? null : data.optJSONObject("claimCommunityPoints");
        if (payload == null) return ClaimResult.retryable("missing claim payload");

        JSONObject error = payload.optJSONObject("error");
        if (error != null) {
            return ClaimResult.failed(error.optString("code", "unknown"));
        }

        JSONObject claim = payload.optJSONObject("claim");
        if (claim == null) return ClaimResult.failed("missing returned claim");

        int total = claim.optInt("pointsEarnedTotal", 0);
        int baseline = claim.optInt("pointsEarnedBaseline", 0);
        int points = total >= baseline ? total - baseline : 0;
        return ClaimResult.success(points);
    }

    private static String post(String token, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(GQL_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Client-ID", GQL_CLIENT_ID);
            connection.setRequestProperty("Authorization", "OAuth " + token);

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                drainQuietly(connection.getErrorStream());
                log("GQL HTTP " + status);
                return null;
            }

            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        log("GQL response too large");
                        return null;
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Throwable error) {
            log("GQL request failed: " + error);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        try (InputStream s = stream) {
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < 8192 && (n = s.read(buf)) != -1) {
                total += n;
            }
        } catch (Throwable ignored) {}
    }

    private static JSONObject parse(String response) {
        if (response == null || response.isEmpty()) return null;
        try {
            return new JSONObject(response);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasErrors(JSONObject root) {
        JSONArray errors = root.optJSONArray("errors");
        return errors != null && errors.length() > 0;
    }

    private static String firstError(JSONObject root) {
        JSONArray errors = root.optJSONArray("errors");
        if (errors == null || errors.length() == 0) return "unknown error";
        JSONObject first = errors.optJSONObject(0);
        return first == null ? "unknown error" : first.optString("message", "unknown error");
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void log(String message) {
        android.util.Log.d(TAG, message);
    }

    private static final class ClaimResult {
        final boolean success;
        final boolean authoritativeFailure;
        final int points;
        final String error;

        private ClaimResult(boolean success, boolean authoritativeFailure, int points, String error) {
            this.success = success;
            this.authoritativeFailure = authoritativeFailure;
            this.points = points;
            this.error = error;
        }

        static ClaimResult success(int points) {
            return new ClaimResult(true, true, points, null);
        }

        static ClaimResult failed(String error) {
            return new ClaimResult(false, true, 0, error);
        }

        static ClaimResult retryable(String error) {
            return new ClaimResult(false, false, 0, error);
        }
    }
}
