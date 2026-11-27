package app.morphe.extension.multistream;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.KizuRuntime;

/**
 * Direct-HTTPS HLS stitched-ad monitor and backup stream swapper for normal playback ad-bypass
 * ({@link AdBypassOverlayPlayer}), Mode A Sub-PiP, and Mode B Multi-View.
 *
 * <p>Android 9+ blocks cleartext {@code http://127.0.0.1} traffic in Twitch's APK
 * ({@code usesCleartextTraffic="false"}). Instead of routing playlists through a local HTTP socket,
 * players load {@code https://usher.ttvnw.net/...} directly while this monitor polls a lightweight
 * variant {@code .m3u8} (~4 KB) over HTTPS every 2.5 seconds. When a Twitch stitched-ad marker is
 * detected, it probes clean backup {@code playerType} streams over HTTPS and hot-swaps the player
 * to a verified ad-free {@code https://usher.ttvnw.net/...} URI (or activates the mute + dark cover
 * fallback if all backup types also carry ads), then swaps back to primary once ads finish.</p>
 */
final class MultiStreamHlsProxy {
    private static final String TAG = "KizuHlsAdMonitor";

    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 3500;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 4500;
    private static final int MAX_PLAYLIST_BYTES = 512 * 1024;
    private static final long POLL_INTERVAL_MS = 2500L;
    private static final long CANDIDATE_COOLDOWN_MS = 12_000L;
    private static final int CLEAN_POLLS_BEFORE_RETURN = 2;

    private static final String[] BACKUP_PLAYER_TYPES = {
            "embed",
            "popout",
            "frontpage",
            "channel_home_carousel",
            "thunderdome",
            "autoplay",
            "picture-by-picture"
    };

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern DURATION_ATTR_PATTERN =
            Pattern.compile("(?:PLANNED-DURATION|DURATION)=([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern START_DATE_ATTR_PATTERN =
            Pattern.compile("START-DATE=\"([^\"]+)\"");

    enum AdState {
        NONE,
        SWAPPED_TO_BACKUP,
        AD_BLOCKED_COVER
    }

    interface AdStateListener {
        void onAdStateChanged(AdState state, float remainingSeconds);
        default void onSwapStreamUri(Uri nextPlayUri) {}
    }

    static final class SessionHandle {
        final String sessionId;
        final Uri initialPlayUri;

        SessionHandle(String sessionId, Uri initialPlayUri) {
            this.sessionId = sessionId;
            this.initialPlayUri = initialPlayUri;
        }
    }

    private static final MultiStreamHlsProxy INSTANCE = new MultiStreamHlsProxy();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, MonitorSession> sessions = new ConcurrentHashMap<>();

    private MultiStreamHlsProxy() {}

    static MultiStreamHlsProxy getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a Mode A / Mode B stream session starting on {@code primaryMasterUri} (direct HTTPS)
     * and monitors its playlist in the background for stitched ads.
     */
    SessionHandle registerSession(Context context, String login, String primaryPlayerType, Uri primaryMasterUri) {
        if (login == null || login.isEmpty() || primaryMasterUri == null) return null;
        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Context appCtx = context == null ? null : context.getApplicationContext();
        MonitorSession session = new MonitorSession(
                sid, appCtx, login, primaryPlayerType, primaryMasterUri, false);
        sessions.put(sid, session);
        startPolling(session, 1200L);
        return new SessionHandle(sid, primaryMasterUri);
    }

    /**
     * Probes alternate {@code playerType} backup streams over HTTPS and registers a backup-only
     * monitor session on the first verified ad-free {@code https://usher.ttvnw.net/...} stream.
     * Used by {@link AdBypassOverlayPlayer} during normal playback stitched ads.
     */
    SessionHandle registerBackupFirstSession(Context context, String rawLogin) {
        if (rawLogin == null) return null;
        String login = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty()) return null;

        CleanBackupCandidate found = findCleanBackup(login, null, null);
        if (found == null) return null;

        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Context appCtx = context == null ? null : context.getApplicationContext();
        MonitorSession session = new MonitorSession(
                sid, appCtx, login, found.playerType, found.masterUri, true);
        session.activeBackupType = found.playerType;
        session.activeBackupMasterUri = found.masterUri;
        session.activeBackupProbeUrl = found.probeVariantUrl;
        session.servingBackup = true;
        sessions.put(sid, session);
        startPolling(session, POLL_INTERVAL_MS);

        Log.i(TAG, "Ad-bypass overlay selected clean HTTPS backup for " + login
                + " via playerType=" + found.playerType);
        return new SessionHandle(sid, found.masterUri);
    }

    void setAdStateListener(String sessionId, AdStateListener listener) {
        if (sessionId == null) return;
        MonitorSession s = sessions.get(sessionId);
        if (s != null) {
            s.listener = listener;
        }
    }

    void unregisterSession(String sessionId) {
        if (sessionId == null) return;
        MonitorSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.closed = true;
            removed.listener = null;
            ScheduledFuture<?> f = removed.pollFuture;
            if (f != null) {
                f.cancel(false);
            }
        }
    }

    private void startPolling(MonitorSession session, long initialDelayMs) {
        session.pollFuture = KizuRuntime.scheduler().scheduleWithFixedDelay(() -> {
            if (session.closed || !sessions.containsKey(session.id)) return;
            try {
                pollSession(session);
            } catch (Throwable t) {
                Log.w(TAG, "Monitor poll error for " + session.login + ": " + t.getMessage());
            }
        }, initialDelayMs, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollSession(MonitorSession session) {
        if (session.closed) return;
        if (session.backupOnlyMode) {
            pollBackupOnlySession(session);
        } else {
            pollPrimaryWithBackupSwap(session);
        }
    }

    /**
     * Used by {@link AdBypassOverlayPlayer}: monitors the currently playing backup stream; if an
     * ad ever appears on it mid-break, switches to another clean backup playerType or triggers cover.
     */
    private void pollBackupOnlySession(MonitorSession session) {
        String probeUrl = session.activeBackupProbeUrl;
        if (probeUrl != null) {
            HttpResult res = httpGetText(probeUrl);
            if (res.body != null) {
                AdDetection ad = detectStitchedAd(res.body);
                if (!ad.hasAd) {
                    notifyAdState(session, AdState.SWAPPED_TO_BACKUP, 0f);
                    return;
                }
                session.markCandidateFailed(session.activeBackupType);
            } else if (res.code == 403 || res.code == 404 || res.code == 410) {
                session.markCandidateFailed(session.activeBackupType);
            } else {
                // Transient network hiccup; don't disrupt playing stream.
                return;
            }
        }

        CleanBackupCandidate next = findCleanBackup(
                session.login, session.activeBackupType, session.candidateFailedAtMs);
        if (next != null) {
            session.activeBackupType = next.playerType;
            session.activeBackupMasterUri = next.masterUri;
            session.activeBackupProbeUrl = next.probeVariantUrl;
            notifySwapUri(session, next.masterUri);
            notifyAdState(session, AdState.SWAPPED_TO_BACKUP, 0f);
        } else {
            notifyAdState(session, AdState.AD_BLOCKED_COVER, 15f);
        }
    }

    /**
     * Used by Mode A & Mode B: monitors the primary stream's media playlist and hot-swaps to a
     * verified ad-free HTTPS backup stream during stitched ads, returning to primary once clean.
     */
    private void pollPrimaryWithBackupSwap(MonitorSession session) {
        if (session.primaryProbeUrl == null) {
            session.primaryProbeUrl = resolveProbeVariantUrl(session.primaryMasterUri.toString());
            if (session.primaryProbeUrl == null) return;
        }

        HttpResult primaryRes = httpGetText(session.primaryProbeUrl);
        if (primaryRes.code == 403 || primaryRes.code == 404 || primaryRes.code == 410) {
            Uri refreshed = MultiStreamApi.fetchPrimaryUsherUri(
                    session.appContext, session.login, session.primaryPlayerType);
            if (refreshed != null) {
                session.primaryMasterUri = refreshed;
                session.primaryProbeUrl = resolveProbeVariantUrl(refreshed.toString());
                if (session.primaryProbeUrl != null) {
                    primaryRes = httpGetText(session.primaryProbeUrl);
                }
            }
        }
        if (primaryRes.body == null) return;

        AdDetection primaryAd = detectStitchedAd(primaryRes.body);
        if (!primaryAd.hasAd) {
            if (session.servingBackup) {
                session.cleanPrimaryStreak++;
                if (session.cleanPrimaryStreak < CLEAN_POLLS_BEFORE_RETURN) {
                    return;
                }
                session.servingBackup = false;
                session.activeBackupType = null;
                session.activeBackupMasterUri = null;
                session.activeBackupProbeUrl = null;
                Log.i(TAG, "Ad ended on " + session.login + ", restoring primary HTTPS stream");
                notifySwapUri(session, session.primaryMasterUri);
            }
            notifyAdState(session, AdState.NONE, 0f);
            return;
        }

        // Primary has a stitched ad!
        session.cleanPrimaryStreak = 0;

        if (session.servingBackup && session.activeBackupProbeUrl != null) {
            HttpResult backupRes = httpGetText(session.activeBackupProbeUrl);
            if (backupRes.body != null && !detectStitchedAd(backupRes.body).hasAd) {
                notifyAdState(session, AdState.SWAPPED_TO_BACKUP, primaryAd.remainingSeconds);
                return;
            }
            session.markCandidateFailed(session.activeBackupType);
            session.servingBackup = false;
        }

        CleanBackupCandidate backup = findCleanBackup(
                session.login, session.primaryPlayerType, session.candidateFailedAtMs);
        if (backup != null) {
            session.servingBackup = true;
            session.activeBackupType = backup.playerType;
            session.activeBackupMasterUri = backup.masterUri;
            session.activeBackupProbeUrl = backup.probeVariantUrl;
            Log.i(TAG, "Swapping " + session.login + " to clean HTTPS backup playerType=" + backup.playerType);
            notifySwapUri(session, backup.masterUri);
            notifyAdState(session, AdState.SWAPPED_TO_BACKUP, primaryAd.remainingSeconds);
        } else {
            notifyAdState(session, AdState.AD_BLOCKED_COVER, primaryAd.remainingSeconds);
        }
    }

    private static CleanBackupCandidate findCleanBackup(
            String login, String skipType, Map<String, Long> cooldowns) {
        long now = SystemClock.uptimeMillis();
        for (String candidate : BACKUP_PLAYER_TYPES) {
            if (candidate.equals(skipType)) continue;
            if (cooldowns != null) {
                Long failedAt = cooldowns.get(candidate);
                if (failedAt != null && now - failedAt < CANDIDATE_COOLDOWN_MS) {
                    continue;
                }
            }
            Uri masterUri = MultiStreamApi.fetchBackupUsherUri(login, candidate);
            if (masterUri == null) {
                if (cooldowns != null) cooldowns.put(candidate, now);
                continue;
            }
            String probeUrl = resolveProbeVariantUrl(masterUri.toString());
            if (probeUrl == null) {
                if (cooldowns != null) cooldowns.put(candidate, now);
                continue;
            }
            HttpResult mediaRes = httpGetText(probeUrl);
            if (mediaRes.body == null || detectStitchedAd(mediaRes.body).hasAd) {
                if (cooldowns != null) cooldowns.put(candidate, now);
                continue;
            }
            if (cooldowns != null) cooldowns.remove(candidate);
            return new CleanBackupCandidate(candidate, masterUri, probeUrl);
        }
        return null;
    }

    private static String resolveProbeVariantUrl(String masterUrl) {
        HttpResult masterRes = httpGetText(masterUrl);
        if (masterRes.body == null) return null;
        List<VariantEntry> variants = parseMasterVariants(masterRes.body, masterUrl);
        VariantEntry picked = pickBestVariant(variants, 720);
        return picked == null ? null : picked.upstreamUrl;
    }

    private static List<VariantEntry> parseMasterVariants(String masterText, String baseUrl) {
        List<VariantEntry> out = new ArrayList<>();
        String[] lines = masterText.split("\\r?\\n");
        int pendingHeight = 0;
        int pendingBandwidth = 0;
        boolean expectStreamUri = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                pendingHeight = extractInt(RESOLUTION_PATTERN, trimmed, 0);
                pendingBandwidth = extractInt(BANDWIDTH_PATTERN, trimmed, 0);
                expectStreamUri = true;
            } else if (!trimmed.startsWith("#")) {
                if (expectStreamUri) {
                    out.add(new VariantEntry(resolveUrl(baseUrl, trimmed), pendingHeight, pendingBandwidth));
                }
                expectStreamUri = false;
                pendingHeight = 0;
                pendingBandwidth = 0;
            }
        }
        return out;
    }

    private static VariantEntry pickBestVariant(List<VariantEntry> variants, int targetHeight) {
        if (variants == null || variants.isEmpty()) return null;
        VariantEntry best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (VariantEntry v : variants) {
            if (v.height <= 0) continue;
            int diff = Math.abs(v.height - targetHeight);
            if (best == null || diff < bestDiff || (diff == bestDiff && v.bandwidth > best.bandwidth)) {
                best = v;
                bestDiff = diff;
            }
        }
        return best != null ? best : variants.get(0);
    }

    private static AdDetection detectStitchedAd(String playlistText) {
        if (playlistText == null || playlistText.isEmpty()) {
            return AdDetection.NONE;
        }
        String[] lines = playlistText.split("\\r?\\n");
        boolean foundAd = false;
        float dateRangeRemainingSec = 0f;
        float adSegmentSumSec = 0f;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.contains("twitch-stitched-ad")
                        || lower.contains("twitch-maf-ad")
                        || lower.contains("twitch-ad-quartile")
                        || lower.contains("stitched-ad")
                        || lower.contains("x-tv-twitch-ad")) {
                    foundAd = true;
                    float rem = parseDateRangeRemainingSeconds(trimmed);
                    if (rem > dateRangeRemainingSec) {
                        dateRangeRemainingSec = rem;
                    }
                }
                continue;
            }

            if (trimmed.startsWith("#EXT-X-CUE-OUT")
                    || trimmed.startsWith("#EXT-X-SCTE35-OUT")
                    || trimmed.contains("S_AD_BREAK")) {
                foundAd = true;
                continue;
            }

            if (trimmed.startsWith("#EXTINF:")) {
                int comma = trimmed.indexOf(',');
                if (comma > 0 && comma < trimmed.length() - 1) {
                    String title = trimmed.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
                    if (!"live".equals(title)
                            && (title.contains("amazon")
                            || title.contains("stitched")
                            || title.contains("ad_break"))) {
                        foundAd = true;
                        try {
                            adSegmentSumSec += Float.parseFloat(trimmed.substring(8, comma).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        if (!foundAd) return AdDetection.NONE;
        float estSec = dateRangeRemainingSec > 0f
                ? dateRangeRemainingSec
                : (adSegmentSumSec > 0f ? Math.max(6f, adSegmentSumSec) : 15f);
        return new AdDetection(true, estSec);
    }

    private static float parseDateRangeRemainingSeconds(String line) {
        Matcher dm = DURATION_ATTR_PATTERN.matcher(line);
        float durationSec = 0f;
        if (dm.find()) {
            try {
                durationSec = Float.parseFloat(dm.group(1));
            } catch (NumberFormatException ignored) {}
        }
        if (durationSec <= 0f) return 0f;

        Matcher sm = START_DATE_ATTR_PATTERN.matcher(line);
        if (sm.find()) {
            long startEpochMs = parseIso8601Utc(sm.group(1));
            if (startEpochMs > 0L) {
                long endEpochMs = startEpochMs + (long) (durationSec * 1000f);
                long leftMs = endEpochMs - System.currentTimeMillis();
                if (leftMs > 1000L && leftMs <= (long) (durationSec * 1000f) + 5000L) {
                    return leftMs / 1000f;
                }
            }
        }
        return durationSec;
    }

    private static long parseIso8601Utc(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        String clean = iso.trim();
        int dot = clean.indexOf('.');
        if (dot > 0 && clean.endsWith("Z")) {
            String frac = clean.substring(dot + 1, clean.length() - 1);
            if (frac.length() > 3) {
                clean = clean.substring(0, dot + 1) + frac.substring(0, 3) + "Z";
            } else if (frac.length() == 2) {
                clean = clean.substring(0, dot + 1) + frac + "0Z";
            } else if (frac.length() == 1) {
                clean = clean.substring(0, dot + 1) + frac + "00Z";
            }
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(p, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(clean);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    private void notifySwapUri(MonitorSession session, Uri nextUri) {
        if (session.closed || nextUri == null) return;
        AdStateListener l = session.listener;
        if (l == null) return;
        main.post(() -> {
            if (!session.closed) {
                AdStateListener cur = session.listener;
                if (cur != null) cur.onSwapStreamUri(nextUri);
            }
        });
    }

    private void notifyAdState(MonitorSession session, AdState nextState, float remainingSeconds) {
        if (session.closed) return;
        boolean shouldDispatch;
        synchronized (session.lock) {
            boolean stateChanged = session.lastAdState != nextState;
            boolean refreshTimer = nextState == AdState.AD_BLOCKED_COVER
                    && remainingSeconds > 0f
                    && Math.abs(remainingSeconds - session.lastReportedRemainingSec) >= 2.5f;
            shouldDispatch = stateChanged || refreshTimer;
            session.lastAdState = nextState;
            session.lastReportedRemainingSec = remainingSeconds;
        }
        if (!shouldDispatch) return;
        AdStateListener l = session.listener;
        if (l == null) return;
        main.post(() -> {
            if (!session.closed) {
                AdStateListener cur = session.listener;
                if (cur != null) cur.onAdStateChanged(nextState, remainingSeconds);
            }
        });
    }

    private static HttpResult httpGetText(String urlStr) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
            con.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*");
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                return new HttpResult(code, null);
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_PLAYLIST_BYTES) return new HttpResult(code, null);
                    out.write(buf, 0, n);
                }
                return new HttpResult(code, out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Throwable ignored) {
            return new HttpResult(0, null);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String resolveUrl(String baseUrl, String target) {
        if (target == null) return "";
        String t = target.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        try {
            return URI.create(baseUrl).resolve(t).toString();
        } catch (Throwable ignored) {
            return t;
        }
    }

    private static int extractInt(Pattern pattern, String input, int fallback) {
        Matcher m = pattern.matcher(input);
        if (!m.find()) return fallback;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static final class AdDetection {
        static final AdDetection NONE = new AdDetection(false, 0f);
        final boolean hasAd;
        final float remainingSeconds;

        AdDetection(boolean hasAd, float remainingSeconds) {
            this.hasAd = hasAd;
            this.remainingSeconds = remainingSeconds;
        }
    }

    private static final class CleanBackupCandidate {
        final String playerType;
        final Uri masterUri;
        final String probeVariantUrl;

        CleanBackupCandidate(String playerType, Uri masterUri, String probeVariantUrl) {
            this.playerType = playerType;
            this.masterUri = masterUri;
            this.probeVariantUrl = probeVariantUrl;
        }
    }

    private static final class VariantEntry {
        final String upstreamUrl;
        final int height;
        final int bandwidth;

        VariantEntry(String upstreamUrl, int height, int bandwidth) {
            this.upstreamUrl = upstreamUrl;
            this.height = height;
            this.bandwidth = bandwidth;
        }
    }

    private static final class MonitorSession {
        final String id;
        final Context appContext;
        final String login;
        final String primaryPlayerType;
        final boolean backupOnlyMode;
        final Object lock = new Object();
        final ConcurrentHashMap<String, Long> candidateFailedAtMs = new ConcurrentHashMap<>();

        volatile Uri primaryMasterUri;
        volatile String primaryProbeUrl;
        volatile AdStateListener listener;
        volatile ScheduledFuture<?> pollFuture;
        volatile boolean closed;

        boolean servingBackup;
        int cleanPrimaryStreak = CLEAN_POLLS_BEFORE_RETURN;
        String activeBackupType;
        Uri activeBackupMasterUri;
        String activeBackupProbeUrl;
        AdState lastAdState = AdState.NONE;
        float lastReportedRemainingSec;

        MonitorSession(String id, Context appContext, String login, String primaryPlayerType,
                       Uri primaryMasterUri, boolean backupOnlyMode) {
            this.id = id;
            this.appContext = appContext;
            this.login = login;
            this.primaryPlayerType = primaryPlayerType;
            this.primaryMasterUri = primaryMasterUri;
            this.backupOnlyMode = backupOnlyMode;
        }

        void markCandidateFailed(String type) {
            if (type != null) {
                candidateFailedAtMs.put(type, SystemClock.uptimeMillis());
            }
        }
    }
}
