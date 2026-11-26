package app.morphe.extension.multistream;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.ads.StreamProxy;
import app.morphe.extension.settings.Settings;

/**
 * Twitch GQL + Usher helpers for multi-stream playback, followed live listing, and user login
 * resolution. Uses the logged-in user's OAuth token via {@link KizuRuntime#readAuthToken(Context)}.
 */
final class MultiStreamApi {
    private static final String TAG = "KizuMultiApi";
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final String WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Random RANDOM = new Random();

    private static final String PLAYBACK_TOKEN_QUERY =
            "query PlaybackAccessToken($login: String!, $platform: String!, $playerType: String!) {"
            + " streamPlaybackAccessToken(channelName: $login, params: {"
            + " platform: $platform, playerBackend: \"mediaplayer\", playerType: $playerType"
            + " }) { value signature }"
            + " }";

    private static final String FOLLOWED_LIVE_QUERY =
            "query KizuFollowedLive($limit: Int!) {"
            + " currentUser {"
            + "  login"
            + "  followedLiveUsers(first: $limit) {"
            + "   edges { node {"
            + "    login displayName profileImageURL(width: 70)"
            + "    stream {"
            + "     title viewersCount previewImageURL(width: 320, height: 180)"
            + "     game { displayName }"
            + "    }"
            + "   } }"
            + "  }"
            + " }"
            + " }";

    private static volatile String cachedTokenForLogin;
    private static volatile String cachedUserLogin;

    private MultiStreamApi() {}

    static final class ChannelInfo {
        final String login;
        final String displayName;
        final String title;
        final String game;
        final int viewers;
        final String avatarUrl;
        final String previewUrl;

        ChannelInfo(String login, String displayName, String title, String game,
                    int viewers, String avatarUrl, String previewUrl) {
            this.login = login;
            this.displayName = displayName;
            this.title = title;
            this.game = game;
            this.viewers = viewers;
            this.avatarUrl = avatarUrl;
            this.previewUrl = previewUrl;
        }
    }

    static final class StreamLoadResult {
        final Uri playUri;
        final Uri directUsherUri;
        final String proxySessionId;
        final String error;

        StreamLoadResult(Uri playUri, Uri directUsherUri, String proxySessionId, String error) {
            this.playUri = playUri;
            this.directUsherUri = directUsherUri;
            this.proxySessionId = proxySessionId;
            this.error = error;
        }
    }

    static StreamLoadResult fetchStreamUri(Context context, String rawLogin) {
        if (rawLogin == null) return new StreamLoadResult(null, null, null, "チャンネル名が空です");
        String login = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty()) return new StreamLoadResult(null, null, null, "チャンネル名が空です");

        try {
            String authToken = KizuRuntime.readAuthToken(context);
            String playerType = Settings.ADS_EMBED_PLAYER_TYPE.get() ? "embed" : "mobile_player";
            Uri usherUri = fetchUsherUri(login, "android", playerType, authToken, CLIENT_ID);
            if (usherUri == null && !authToken.isEmpty()) {
                usherUri = fetchUsherUri(login, "android", playerType, "", CLIENT_ID);
            }
            if (usherUri == null) {
                return new StreamLoadResult(null, null, null, "配信トークンを取得できませんでした");
            }

            if (!Settings.BLOCK_ADS.get()) {
                return new StreamLoadResult(usherUri, usherUri, null, null);
            }

            // Never interfere with subscribed / Turbo / ad-free streams.
            if (StreamProxy.isAdFreeStream(usherUri, login)) {
                return new StreamLoadResult(usherUri, usherUri, null, null);
            }

            // Respect user-configured external proxy when set.
            if (StreamProxy.hasExternalProxyConfigured()) {
                Uri resolved = StreamProxy.resolveForMultiView(usherUri);
                return new StreamLoadResult(resolved != null ? resolved : usherUri, usherUri, null, null);
            }

            // Hybrid local 127.0.0.1 HLS ad-swap proxy for non-subscribed streams without external proxy.
            MultiStreamHlsProxy.SessionHandle handle = MultiStreamHlsProxy.getInstance()
                    .registerSession(context, login, playerType, usherUri);
            if (handle != null && handle.localMasterUri != null) {
                return new StreamLoadResult(handle.localMasterUri, usherUri, handle.sessionId, null);
            }
            return new StreamLoadResult(usherUri, usherUri, null, null);
        } catch (Throwable t) {
            Log.e(TAG, "fetchStreamUri failed for " + login, t);
            return new StreamLoadResult(null, null, null, "ストリーム取得エラー");
        }
    }

    /**
     * Fetches a fresh primary usher URI for session recovery (e.g. weaver playlist token expiry).
     */
    static Uri fetchPrimaryUsherUri(Context context, String login, String playerType) {
        String authToken = context == null ? "" : KizuRuntime.readAuthToken(context);
        Uri uri = fetchUsherUri(login, "android", playerType, authToken, CLIENT_ID);
        if (uri == null && !authToken.isEmpty()) {
            uri = fetchUsherUri(login, "android", playerType, "", CLIENT_ID);
        }
        return uri;
    }

    /**
     * Fetches a backup master usher URI using an alternate playerType (embed / popout / frontpage / etc.).
     * Tries web client ID anonymously first (cleanest VAFT backup session), falling back to Android client ID.
     */
    static Uri fetchBackupUsherUri(String login, String playerType) {
        if (login == null || login.isEmpty() || playerType == null || playerType.isEmpty()) return null;
        Uri uri = fetchUsherUri(login, "web", playerType, "", WEB_CLIENT_ID);
        if (uri != null) return uri;
        return fetchUsherUri(login, "android", playerType, "", CLIENT_ID);
    }

    private static Uri fetchUsherUri(String login, String platform, String playerType,
                                     String authToken, String clientId) {
        JSONObject tokenObj = requestPlaybackToken(login, platform, playerType, authToken, clientId);
        if (tokenObj == null) return null;
        String value = tokenObj.optString("value", "");
        String sig = tokenObj.optString("signature", "");
        if (value.isEmpty() || sig.isEmpty()) return null;

        int p = 1000000 + RANDOM.nextInt(8999999);
        return Uri.parse("https://usher.ttvnw.net/api/channel/hls/" + Uri.encode(login) + ".m3u8")
                .buildUpon()
                .appendQueryParameter("token", value)
                .appendQueryParameter("sig", sig)
                .appendQueryParameter("allow_source", "true")
                .appendQueryParameter("allow_audio_only", "true")
                .appendQueryParameter("fast_bread", "true")
                .appendQueryParameter("player_backend", "mediaplayer")
                .appendQueryParameter("p", String.valueOf(p))
                .build();
    }

    private static JSONObject requestPlaybackToken(String login, String platform, String playerType,
                                                   String authToken, String clientId) {
        try {
            JSONObject vars = new JSONObject()
                    .put("login", login)
                    .put("platform", platform)
                    .put("playerType", playerType);
            JSONObject req = new JSONObject()
                    .put("operationName", "PlaybackAccessToken")
                    .put("variables", vars)
                    .put("query", PLAYBACK_TOKEN_QUERY);
            JSONObject res = postGql(authToken, clientId, req.toString());
            if (res == null) return null;
            JSONObject data = res.optJSONObject("data");
            return data == null ? null : data.optJSONObject("streamPlaybackAccessToken");
        } catch (Throwable ignored) {
            return null;
        }
    }

    static List<ChannelInfo> fetchFollowedLive(Context context) {
        List<ChannelInfo> out = new ArrayList<>();
        try {
            String authToken = KizuRuntime.readAuthToken(context);
            if (authToken.isEmpty()) return out;

            JSONObject req = new JSONObject()
                    .put("operationName", "KizuFollowedLive")
                    .put("variables", new JSONObject().put("limit", 40))
                    .put("query", FOLLOWED_LIVE_QUERY);
            JSONObject res = postGql(authToken, CLIENT_ID, req.toString());
            if (res == null) return out;
            JSONObject data = res.optJSONObject("data");
            JSONObject currentUser = data == null ? null : data.optJSONObject("currentUser");
            if (currentUser == null) return out;

            String myLogin = currentUser.optString("login", "").trim().toLowerCase(Locale.ROOT);
            if (!myLogin.isEmpty()) {
                cachedTokenForLogin = authToken;
                cachedUserLogin = myLogin;
            }

            JSONObject followed = currentUser.optJSONObject("followedLiveUsers");
            JSONArray edges = followed == null ? null : followed.optJSONArray("edges");
            if (edges == null) return out;

            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                JSONObject node = edge == null ? null : edge.optJSONObject("node");
                if (node == null) continue;
                String login = node.optString("login", "").trim().toLowerCase(Locale.ROOT);
                if (login.isEmpty()) continue;
                String displayName = node.optString("displayName", login);
                String avatar = node.optString("profileImageURL", "");
                JSONObject stream = node.optJSONObject("stream");
                if (stream == null) continue;
                String title = stream.optString("title", "");
                int viewers = stream.optInt("viewersCount", 0);
                String preview = stream.optString("previewImageURL", "");
                JSONObject gameObj = stream.optJSONObject("game");
                String game = gameObj == null ? "" : gameObj.optString("displayName", "");
                out.add(new ChannelInfo(login, displayName, title, game, viewers, avatar, preview));
            }
        } catch (Throwable t) {
            Log.e(TAG, "fetchFollowedLive failed", t);
        }
        return out;
    }

    static String resolveCurrentUserLogin(Context context) {
        String authToken = KizuRuntime.readAuthToken(context);
        if (authToken.isEmpty()) return "";
        if (authToken.equals(cachedTokenForLogin) && cachedUserLogin != null && !cachedUserLogin.isEmpty()) {
            return cachedUserLogin;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(VALIDATE_URL).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(CONNECT_TIMEOUT_MS);
            con.setReadTimeout(READ_TIMEOUT_MS);
            con.setRequestProperty("Authorization", "OAuth " + authToken);
            if (con.getResponseCode() / 100 != 2) return "";
            String body = readBody(con.getInputStream());
            if (body == null) return "";
            JSONObject json = new JSONObject(body);
            String login = json.optString("login", "").trim().toLowerCase(Locale.ROOT);
            if (!login.isEmpty()) {
                cachedTokenForLogin = authToken;
                cachedUserLogin = login;
            }
            return login;
        } catch (Throwable t) {
            Log.e(TAG, "resolveCurrentUserLogin failed", t);
            return "";
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static JSONObject postGql(String authToken, String clientId, String body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(GQL_URL).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(CONNECT_TIMEOUT_MS);
            con.setReadTimeout(READ_TIMEOUT_MS);
            con.setDoOutput(true);
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Client-ID", clientId != null && !clientId.isEmpty() ? clientId : CLIENT_ID);
            if (authToken != null && !authToken.isEmpty()) {
                con.setRequestProperty("Authorization", "OAuth " + authToken);
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(payload.length);
            con.getOutputStream().write(payload);
            if (con.getResponseCode() / 100 != 2) return null;
            String raw = readBody(con.getInputStream());
            return raw == null ? null : new JSONObject(raw);
        } catch (Throwable t) {
            Log.e(TAG, "GQL request failed", t);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String readBody(InputStream in) {
        if (in == null) return null;
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = stream.read(buf)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
