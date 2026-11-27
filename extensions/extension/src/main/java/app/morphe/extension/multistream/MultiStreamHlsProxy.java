package app.morphe.extension.multistream;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app local HTTP/1.1 HLS proxy bound strictly to {@code 127.0.0.1} for Mode A and Mode B
 * multi-stream playback.
 *
 * <p>Only tiny {@code .m3u8} playlists flow through this proxy; video/audio media segments remain
 * direct HTTPS Twitch CDN URLs so bandwidth and latency overhead are negligible.</p>
 *
 * <p>When a Twitch stitched-ad marker appears in the primary media playlist, the proxy fetches a
 * clean backup stream using alternate {@code playerType}s ({@code embed}, {@code popout},
 * {@code frontpage}, {@code autoplay}, {@code site}) and splices it seamlessly with RFC 8216
 * {@code #EXT-X-DISCONTINUITY} continuity, then returns to the original high-quality primary
 * stream once the ad ends. If backup streams also contain ads, it notifies the slot listener to
 * mute and display {@link MultiStreamAdCoverView}.</p>
 */
final class MultiStreamHlsProxy {
    private static final String TAG = "KizuMultiHlsProxy";

    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 3500;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 4500;
    private static final int CLIENT_SO_TIMEOUT_MS = 5000;
    private static final int MAX_PLAYLIST_BYTES = 512 * 1024;
    private static final long SESSION_IDLE_EXPIRE_MS = 20L * 60_000L;
    private static final long BACKUP_CACHE_TTL_MS = 4L * 60_000L;
    private static final long CANDIDATE_COOLDOWN_MS = 15_000L;
    private static final int MAX_CANDIDATES_PER_POLL = 2;
    private static final int CLEAN_POLLS_BEFORE_RETURN = 2;

    private static final String[] BACKUP_PLAYER_TYPES = {
            "embed",
            "popout",
            "frontpage",
            "autoplay",
            "site"
    };

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern VIDEO_GROUP_PATTERN = Pattern.compile("VIDEO=\"([^\"]+)\"");
    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
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
    }

    static final class SessionHandle {
        final String sessionId;
        final Uri localMasterUri;

        SessionHandle(String sessionId, Uri localMasterUri) {
            this.sessionId = sessionId;
            this.localMasterUri = localMasterUri;
        }
    }

    private static final MultiStreamHlsProxy INSTANCE = new MultiStreamHlsProxy();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, ProxySession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger workerCounter = new AtomicInteger(1);
    private final ExecutorService workers = new ThreadPoolExecutor(
            2, 16, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "kizu-hls-w" + workerCounter.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private ServerSocket serverSocket;
    private volatile int port = -1;

    private MultiStreamHlsProxy() {}

    static MultiStreamHlsProxy getInstance() {
        return INSTANCE;
    }

    SessionHandle registerSession(Context context, String login, String primaryPlayerType, Uri primaryMasterUri) {
        if (login == null || login.isEmpty() || primaryMasterUri == null) return null;
        if (!ensureStarted()) return null;

        pruneExpiredSessions();
        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Context appCtx = context == null ? null : context.getApplicationContext();
        ProxySession session = new ProxySession(sid, appCtx, login, primaryPlayerType, primaryMasterUri.toString());
        sessions.put(sid, session);

        Uri localUri = Uri.parse("http://127.0.0.1:" + port + "/s/" + sid + "/master.m3u8");
        return new SessionHandle(sid, localUri);
    }

    /**
     * Probes alternate {@code playerType} backup streams ({@code embed}, {@code popout},
     * {@code frontpage}, {@code autoplay}, {@code site}) and registers a local HLS proxy session
     * on the first verified ad-free backup stream. Used by {@link AdBypassOverlayPlayer} during
     * normal playback stitched ads so the overlay opens directly on clean content.
     *
     * @return Session handle if a clean backup stream was found, or {@code null} if all backups
     *         currently contain stitched ads or could not be fetched.
     */
    SessionHandle registerBackupFirstSession(Context context, String rawLogin) {
        if (rawLogin == null) return null;
        String login = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty()) return null;
        if (!ensureStarted()) return null;

        for (String candidate : BACKUP_PLAYER_TYPES) {
            Uri backupMasterUri = MultiStreamApi.fetchBackupUsherUri(login, candidate);
            if (backupMasterUri == null) continue;
            HttpResult masterRes = httpGetText(backupMasterUri.toString());
            if (masterRes.body == null) continue;
            List<VariantEntry> variants = parseMasterVariants(masterRes.body, backupMasterUri.toString());
            VariantEntry probeVariant = pickBestVariant(variants, 720, null);
            if (probeVariant == null || probeVariant.upstreamUrl == null) continue;
            HttpResult mediaRes = httpGetText(probeVariant.upstreamUrl);
            if (mediaRes.body == null || detectStitchedAd(mediaRes.body).hasAd) continue;

            Log.i(TAG, "Ad-bypass overlay found clean backup for " + login + " via playerType=" + candidate);
            return registerSession(context, login, candidate, backupMasterUri);
        }
        return null;
    }

    void setAdStateListener(String sessionId, AdStateListener listener) {
        if (sessionId == null) return;
        ProxySession s = sessions.get(sessionId);
        if (s != null) {
            s.listener = listener;
        }
    }

    void unregisterSession(String sessionId) {
        if (sessionId == null) return;
        ProxySession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.closed = true;
            removed.listener = null;
        }
    }

    private synchronized boolean ensureStarted() {
        if (serverSocket != null && !serverSocket.isClosed() && port > 0) {
            return true;
        }
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ServerSocket ss = new ServerSocket(0, 32, loopback);
            serverSocket = ss;
            port = ss.getLocalPort();
            Thread acceptThread = new Thread(() -> acceptLoop(ss), "kizu-hls-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "Local HLS ad-swap proxy listening on 127.0.0.1:" + port);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start local HLS proxy", t);
            serverSocket = null;
            port = -1;
            return false;
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket client = ss.accept();
                workers.execute(() -> handleClient(client));
            } catch (Throwable t) {
                if (!ss.isClosed()) {
                    Log.w(TAG, "Accept error: " + t.getMessage());
                }
            }
        }
    }

    private void pruneExpiredSessions() {
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<String, ProxySession> entry : sessions.entrySet()) {
            ProxySession s = entry.getValue();
            if (s == null || s.closed || now - s.lastAccessMs > SESSION_IDLE_EXPIRE_MS) {
                sessions.remove(entry.getKey());
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket sock = client) {
            sock.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = sock.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            // Drain HTTP headers.
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // ignore
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeStatus(out, 400, "Bad Request", false, "");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            boolean headOnly = "HEAD".equals(method);
            if (!"GET".equals(method) && !headOnly) {
                writeStatus(out, 405, "Method Not Allowed", headOnly, "");
                return;
            }

            String path = parts[1];
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) path = path.substring(0, qIdx);

            // Expected routes:
            //   /s/{sid}/master.m3u8
            //   /s/{sid}/v/{idx}.m3u8
            if (!path.startsWith("/s/")) {
                writeStatus(out, 404, "Not Found", headOnly, "");
                return;
            }
            String rest = path.substring(3);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                writeStatus(out, 404, "Not Found", headOnly, "");
                return;
            }
            String sid = rest.substring(0, slash);
            String sub = rest.substring(slash + 1);

            ProxySession session = sessions.get(sid);
            if (session == null || session.closed) {
                writeStatus(out, 404, "Not Found", headOnly, "");
                return;
            }
            session.lastAccessMs = SystemClock.uptimeMillis();

            if ("master.m3u8".equals(sub)) {
                String body = serveMaster(session);
                if (body == null) {
                    writeStatus(out, 502, "Bad Gateway", headOnly, "");
                } else {
                    writePlaylist(out, headOnly, body);
                }
                return;
            }

            if (sub.startsWith("v/") && sub.endsWith(".m3u8")) {
                String idxStr = sub.substring(2, sub.length() - 5);
                int idx;
                try {
                    idx = Integer.parseInt(idxStr);
                } catch (NumberFormatException ignored) {
                    writeStatus(out, 404, "Not Found", headOnly, "");
                    return;
                }
                String body = serveVariant(session, idx);
                if (body == null) {
                    writeStatus(out, 502, "Bad Gateway", headOnly, "");
                } else {
                    writePlaylist(out, headOnly, body);
                }
                return;
            }

            writeStatus(out, 404, "Not Found", headOnly, "");
        } catch (Throwable ignored) {
            // Client closed connection or timed out.
        }
    }

    private String serveMaster(ProxySession session) {
        HttpResult res = httpGetText(session.primaryMasterUrl);
        if (res.code == 403 || res.code == 404 || res.code == 410) {
            Uri refreshed = MultiStreamApi.fetchPrimaryUsherUri(
                    session.appContext, session.login, session.primaryPlayerType);
            if (refreshed != null) {
                session.primaryMasterUrl = refreshed.toString();
                res = httpGetText(session.primaryMasterUrl);
            }
        }
        if (res.body == null) return null;

        List<VariantEntry> parsed = new ArrayList<>();
        String rewritten = rewriteMasterPlaylist(session, res.body, session.primaryMasterUrl, parsed);
        synchronized (session.lock) {
            session.variants = parsed;
        }
        return rewritten;
    }

    private String rewriteMasterPlaylist(ProxySession session, String masterText, String baseUrl,
                                         List<VariantEntry> outVariants) {
        StringBuilder sb = new StringBuilder(masterText.length() + 512);
        String[] lines = masterText.split("\\r?\\n");
        int pendingHeight = 0;
        int pendingBandwidth = 0;
        String pendingGroup = "";
        boolean expectStreamUri = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                pendingHeight = extractInt(RESOLUTION_PATTERN, trimmed, 0);
                pendingBandwidth = extractInt(BANDWIDTH_PATTERN, trimmed, 0);
                pendingGroup = extractString(VIDEO_GROUP_PATTERN, trimmed, "");
                expectStreamUri = true;
                sb.append(line).append('\n');
                continue;
            }

            if (trimmed.startsWith("#EXT-X-MEDIA:")) {
                Matcher m = URI_ATTR_PATTERN.matcher(line);
                if (m.find()) {
                    String abs = resolveUrl(baseUrl, m.group(1));
                    int idx = outVariants.size();
                    outVariants.add(new VariantEntry(idx, abs, 0, 0, "media_" + idx));
                    String localUrl = "http://127.0.0.1:" + port + "/s/" + session.id + "/v/" + idx + ".m3u8";
                    String replaced = line.substring(0, m.start(1)) + localUrl + line.substring(m.end(1));
                    sb.append(replaced).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
                continue;
            }

            if (!trimmed.startsWith("#")) {
                String abs = resolveUrl(baseUrl, trimmed);
                int idx = outVariants.size();
                outVariants.add(new VariantEntry(
                        idx,
                        abs,
                        expectStreamUri ? pendingHeight : 0,
                        expectStreamUri ? pendingBandwidth : 0,
                        expectStreamUri ? pendingGroup : ""));
                expectStreamUri = false;
                pendingHeight = 0;
                pendingBandwidth = 0;
                pendingGroup = "";
                sb.append("http://127.0.0.1:").append(port)
                        .append("/s/").append(session.id)
                        .append("/v/").append(idx).append(".m3u8\n");
                continue;
            }

            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String serveVariant(ProxySession session, int variantIdx) {
        VariantEntry variant;
        synchronized (session.lock) {
            if (variantIdx < 0 || variantIdx >= session.variants.size()) return null;
            variant = session.variants.get(variantIdx);
        }

        HttpResult primaryRes = httpGetText(variant.upstreamUrl);
        if (primaryRes.code == 403 || primaryRes.code == 404 || primaryRes.code == 410) {
            if (refreshPrimaryVariants(session)) {
                synchronized (session.lock) {
                    if (variantIdx < session.variants.size()) {
                        variant = session.variants.get(variantIdx);
                    }
                }
                primaryRes = httpGetText(variant.upstreamUrl);
            }
        }
        if (primaryRes.body == null) return null;

        AdDetection primaryAd = detectStitchedAd(primaryRes.body);
        if (!primaryAd.hasAd) {
            boolean stayOnBackupForHysteresis = false;
            synchronized (variant) {
                if (variant.servingBackup) {
                    variant.cleanPrimaryStreak++;
                    if (variant.cleanPrimaryStreak < CLEAN_POLLS_BEFORE_RETURN) {
                        stayOnBackupForHysteresis = true;
                    }
                } else {
                    variant.cleanPrimaryStreak = CLEAN_POLLS_BEFORE_RETURN;
                }
            }
            if (stayOnBackupForHysteresis) {
                BackupResult held = fetchCleanBackupPlaylist(session, variant.height, true);
                if (held != null) {
                    return rewriteMediaPlaylist(held.body, held.url, variant, held.sourceKey);
                }
            }
            notifyAdState(session, AdState.NONE, 0f);
            return rewriteMediaPlaylist(primaryRes.body, variant.upstreamUrl, variant, "primary");
        }

        // Primary media playlist contains a Twitch stitched ad!
        synchronized (variant) {
            variant.cleanPrimaryStreak = 0;
        }

        BackupResult backup = fetchCleanBackupPlaylist(session, variant.height, false);
        if (backup != null) {
            notifyAdState(session, AdState.SWAPPED_TO_BACKUP, primaryAd.remainingSeconds);
            return rewriteMediaPlaylist(backup.body, backup.url, variant, backup.sourceKey);
        }

        // Backup streams also contained ads or were unavailable -> mute & cover slot during ad.
        notifyAdState(session, AdState.AD_BLOCKED_COVER, primaryAd.remainingSeconds);
        return rewriteMediaPlaylist(primaryRes.body, variant.upstreamUrl, variant, "primary");
    }

    private boolean refreshPrimaryVariants(ProxySession session) {
        Uri refreshed = MultiStreamApi.fetchPrimaryUsherUri(
                session.appContext, session.login, session.primaryPlayerType);
        if (refreshed == null) return false;
        HttpResult res = httpGetText(refreshed.toString());
        if (res.body == null) return false;

        List<VariantEntry> fresh = new ArrayList<>();
        rewriteMasterPlaylist(session, res.body, refreshed.toString(), fresh);
        if (fresh.isEmpty()) return false;

        synchronized (session.lock) {
            session.primaryMasterUrl = refreshed.toString();
            for (int i = 0; i < session.variants.size(); i++) {
                VariantEntry oldV = session.variants.get(i);
                VariantEntry match = pickBestVariant(fresh, oldV.height, oldV.videoGroup);
                if (match != null) {
                    oldV.upstreamUrl = match.upstreamUrl;
                }
            }
        }
        return true;
    }

    private BackupResult fetchCleanBackupPlaylist(ProxySession session, int targetHeight, boolean cacheOnly) {
        long now = SystemClock.uptimeMillis();

        // 1. Try currently cached backup variant first for fast ~150ms response.
        String cachedUrl;
        String cachedType;
        long cachedAt;
        synchronized (session.lock) {
            cachedUrl = session.cachedBackupUrl;
            cachedType = session.cachedBackupPlayerType;
            cachedAt = session.cachedBackupFetchedAtMs;
        }
        if (cachedUrl != null && now - cachedAt < BACKUP_CACHE_TTL_MS) {
            HttpResult res = httpGetText(cachedUrl);
            if (res.body != null && !detectStitchedAd(res.body).hasAd) {
                return new BackupResult(res.body, cachedUrl, "backup:" + cachedType);
            }
            synchronized (session.lock) {
                if (cachedUrl.equals(session.cachedBackupUrl)) {
                    session.cachedBackupUrl = null;
                }
                if (cachedType != null) {
                    session.candidateFailedAtMs.put(cachedType, now);
                }
            }
        }
        if (cacheOnly) return null;

        // 2. Try up to MAX_CANDIDATES_PER_POLL alternate playerTypes not in cooldown.
        int tried = 0;
        for (String candidate : BACKUP_PLAYER_TYPES) {
            if (session.closed) return null;
            if (tried >= MAX_CANDIDATES_PER_POLL) break;

            Long failedAt;
            synchronized (session.lock) {
                failedAt = session.candidateFailedAtMs.get(candidate);
            }
            if (failedAt != null && now - failedAt < CANDIDATE_COOLDOWN_MS) {
                continue;
            }
            tried++;

            Uri backupMasterUri = MultiStreamApi.fetchBackupUsherUri(session.login, candidate);
            if (backupMasterUri == null) {
                markCandidateFailed(session, candidate, now);
                continue;
            }
            HttpResult masterRes = httpGetText(backupMasterUri.toString());
            if (masterRes.body == null) {
                markCandidateFailed(session, candidate, now);
                continue;
            }
            List<VariantEntry> backupVariants = parseMasterVariants(masterRes.body, backupMasterUri.toString());
            VariantEntry picked = pickBestVariant(backupVariants, targetHeight, null);
            if (picked == null || picked.upstreamUrl == null) {
                markCandidateFailed(session, candidate, now);
                continue;
            }
            HttpResult mediaRes = httpGetText(picked.upstreamUrl);
            if (mediaRes.body == null || detectStitchedAd(mediaRes.body).hasAd) {
                markCandidateFailed(session, candidate, now);
                continue;
            }

            synchronized (session.lock) {
                session.cachedBackupUrl = picked.upstreamUrl;
                session.cachedBackupPlayerType = candidate;
                session.cachedBackupFetchedAtMs = SystemClock.uptimeMillis();
                session.candidateFailedAtMs.remove(candidate);
            }
            Log.i(TAG, "Swapped " + session.login + " to clean backup playerType=" + candidate
                    + " (" + picked.height + "p)");
            return new BackupResult(mediaRes.body, picked.upstreamUrl, "backup:" + candidate);
        }
        return null;
    }

    private static void markCandidateFailed(ProxySession session, String candidate, long now) {
        synchronized (session.lock) {
            session.candidateFailedAtMs.put(candidate, now);
        }
    }

    private static List<VariantEntry> parseMasterVariants(String masterText, String baseUrl) {
        List<VariantEntry> out = new ArrayList<>();
        String[] lines = masterText.split("\\r?\\n");
        int pendingHeight = 0;
        int pendingBandwidth = 0;
        String pendingGroup = "";
        boolean expectStreamUri = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                pendingHeight = extractInt(RESOLUTION_PATTERN, trimmed, 0);
                pendingBandwidth = extractInt(BANDWIDTH_PATTERN, trimmed, 0);
                pendingGroup = extractString(VIDEO_GROUP_PATTERN, trimmed, "");
                expectStreamUri = true;
            } else if (!trimmed.startsWith("#")) {
                if (expectStreamUri) {
                    out.add(new VariantEntry(out.size(), resolveUrl(baseUrl, trimmed),
                            pendingHeight, pendingBandwidth, pendingGroup));
                }
                expectStreamUri = false;
                pendingHeight = 0;
                pendingBandwidth = 0;
                pendingGroup = "";
            }
        }
        return out;
    }

    private static VariantEntry pickBestVariant(List<VariantEntry> variants, int targetHeight, String preferredGroup) {
        if (variants == null || variants.isEmpty()) return null;
        if (preferredGroup != null && !preferredGroup.isEmpty()) {
            for (VariantEntry v : variants) {
                if (preferredGroup.equals(v.videoGroup)) return v;
            }
        }
        VariantEntry best = null;
        if (targetHeight > 0) {
            int bestDiff = Integer.MAX_VALUE;
            for (VariantEntry v : variants) {
                if (v.height <= 0) continue;
                int diff = Math.abs(v.height - targetHeight);
                if (best == null || diff < bestDiff || (diff == bestDiff && v.bandwidth > best.bandwidth)) {
                    best = v;
                    bestDiff = diff;
                }
            }
        }
        if (best != null) return best;
        // Fallback: highest video variant (preferring video over audio-only).
        for (VariantEntry v : variants) {
            if (best == null || v.height > best.height
                    || (v.height == best.height && v.bandwidth > best.bandwidth)) {
                best = v;
            }
        }
        return best;
    }

    /**
     * Rewrites a media playlist so that:
     * 1. Every relative URI (segments, EXT-X-MAP, EXT-X-PART, EXT-X-TWITCH-PREFETCH) is resolved
     *    against the upstream CDN playlist URL (so segments never hit 127.0.0.1).
     * 2. Primary <-> Backup stream transitions maintain monotonic {@code EXT-X-MEDIA-SEQUENCE} and
     *    RFC 8216 {@code EXT-X-DISCONTINUITY} / {@code EXT-X-DISCONTINUITY-SEQUENCE}.
     */
    private static String rewriteMediaPlaylist(String rawPlaylist, String upstreamUrl,
                                               VariantEntry state, String sourceKey) {
        String[] lines = rawPlaylist.split("\\r?\\n");
        long parsedSeq = 0L;
        boolean hasMediaSeq = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    parsedSeq = Long.parseLong(t.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    hasMediaSeq = true;
                } catch (NumberFormatException ignored) {}
                break;
            }
        }

        long adjustedSeq;
        int discSeqHeader;
        int insertDiscBeforeSegIdx = -1;

        synchronized (state) {
            boolean isBackup = !"primary".equals(sourceKey);
            boolean switched = state.activeSourceKey != null && !state.activeSourceKey.equals(sourceKey);

            if (!hasMediaSeq) {
                adjustedSeq = Math.max(0L, state.lastServedMediaSeq);
            } else if (switched) {
                long minNext = state.lastServedMediaSeq >= 0L ? state.lastServedMediaSeq + 1L : parsedSeq;
                adjustedSeq = Math.max(parsedSeq, minNext);
                state.mediaSeqOffset = adjustedSeq - parsedSeq;
                if (state.discontinuityAtSeq >= 0L) {
                    state.baseDiscontinuitySeq = state.currentDiscontinuitySeq;
                }
                state.currentDiscontinuitySeq++;
                state.discontinuityAtSeq = adjustedSeq;
            } else {
                adjustedSeq = parsedSeq + state.mediaSeqOffset;
                if (state.lastServedMediaSeq >= 0L && adjustedSeq < state.lastServedMediaSeq) {
                    state.mediaSeqOffset += (state.lastServedMediaSeq - adjustedSeq);
                    adjustedSeq = state.lastServedMediaSeq;
                }
            }

            if (state.discontinuityAtSeq >= 0L) {
                if (adjustedSeq > state.discontinuityAtSeq) {
                    state.baseDiscontinuitySeq = state.currentDiscontinuitySeq;
                    state.discontinuityAtSeq = -1L;
                } else {
                    long offset = state.discontinuityAtSeq - adjustedSeq;
                    if (offset >= 0L && offset < 128L) {
                        insertDiscBeforeSegIdx = (int) offset;
                    }
                }
            }

            discSeqHeader = state.baseDiscontinuitySeq;
            state.lastServedMediaSeq = adjustedSeq;
            state.activeSourceKey = sourceKey;
            state.servingBackup = isBackup;
        }

        StringBuilder sb = new StringBuilder(rawPlaylist.length() + 256);
        boolean wroteDiscSeq = false;
        int segmentIndex = 0;
        boolean discInserted = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sb.append("#EXT-X-MEDIA-SEQUENCE:").append(adjustedSeq).append('\n');
                if (discSeqHeader > 0) {
                    sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discSeqHeader).append('\n');
                    wroteDiscSeq = true;
                }
                continue;
            }
            if (trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                if (!wroteDiscSeq && discSeqHeader > 0) {
                    sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discSeqHeader).append('\n');
                    wroteDiscSeq = true;
                }
                continue;
            }
            if (trimmed.startsWith("#EXT-X-TWITCH-PREFETCH:")) {
                String prefetchUri = trimmed.substring("#EXT-X-TWITCH-PREFETCH:".length()).trim();
                sb.append("#EXT-X-TWITCH-PREFETCH:").append(resolveUrl(upstreamUrl, prefetchUri)).append('\n');
                continue;
            }
            if (trimmed.startsWith("#EXT-X-MAP:")
                    || trimmed.startsWith("#EXT-X-PART:")
                    || trimmed.startsWith("#EXT-X-PRELOAD-HINT:")
                    || trimmed.startsWith("#EXT-X-KEY:")) {
                sb.append(resolveUriAttribute(line, upstreamUrl)).append('\n');
                continue;
            }
            if (trimmed.startsWith("#EXTINF:")) {
                if (!discInserted && insertDiscBeforeSegIdx == segmentIndex) {
                    sb.append("#EXT-X-DISCONTINUITY\n");
                    discInserted = true;
                }
                sb.append(line).append('\n');
                segmentIndex++;
                continue;
            }
            if (!trimmed.startsWith("#")) {
                sb.append(resolveUrl(upstreamUrl, trimmed)).append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
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
        // Strip sub-millisecond fractional digits if present (e.g. .123456Z -> .123Z).
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

    private void notifyAdState(ProxySession session, AdState nextState, float remainingSeconds) {
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

    private static void writePlaylist(OutputStream out, boolean headOnly, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            out.write(bytes);
        }
        out.flush();
    }

    private static void writeStatus(OutputStream out, int code, String reason,
                                    boolean headOnly, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly && bytes.length > 0) {
            out.write(bytes);
        }
        out.flush();
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

    private static String resolveUriAttribute(String line, String baseUrl) {
        Matcher m = URI_ATTR_PATTERN.matcher(line);
        if (!m.find()) return line;
        String resolved = resolveUrl(baseUrl, m.group(1));
        return line.substring(0, m.start(1)) + resolved + line.substring(m.end(1));
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

    private static String extractString(Pattern pattern, String input, String fallback) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : fallback;
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

    private static final class BackupResult {
        final String body;
        final String url;
        final String sourceKey;

        BackupResult(String body, String url, String sourceKey) {
            this.body = body;
            this.url = url;
            this.sourceKey = sourceKey;
        }
    }

    private static final class VariantEntry {
        final int index;
        volatile String upstreamUrl;
        final int height;
        final int bandwidth;
        final String videoGroup;

        String activeSourceKey;
        boolean servingBackup;
        int cleanPrimaryStreak = CLEAN_POLLS_BEFORE_RETURN;
        long lastServedMediaSeq = -1L;
        long mediaSeqOffset;
        int baseDiscontinuitySeq;
        int currentDiscontinuitySeq;
        long discontinuityAtSeq = -1L;

        VariantEntry(int index, String upstreamUrl, int height, int bandwidth, String videoGroup) {
            this.index = index;
            this.upstreamUrl = upstreamUrl;
            this.height = height;
            this.bandwidth = bandwidth;
            this.videoGroup = videoGroup == null ? "" : videoGroup;
        }
    }

    private static final class ProxySession {
        final String id;
        final Context appContext;
        final String login;
        final String primaryPlayerType;
        final Object lock = new Object();
        final ConcurrentHashMap<String, Long> candidateFailedAtMs = new ConcurrentHashMap<>();

        volatile String primaryMasterUrl;
        volatile AdStateListener listener;
        volatile boolean closed;
        volatile long lastAccessMs = SystemClock.uptimeMillis();

        List<VariantEntry> variants = new ArrayList<>();
        String cachedBackupUrl;
        String cachedBackupPlayerType;
        long cachedBackupFetchedAtMs;
        AdState lastAdState = AdState.NONE;
        float lastReportedRemainingSec;

        ProxySession(String id, Context appContext, String login, String primaryPlayerType, String primaryMasterUrl) {
            this.id = id;
            this.appContext = appContext;
            this.login = login;
            this.primaryPlayerType = primaryPlayerType;
            this.primaryMasterUrl = primaryMasterUrl;
        }
    }
}
