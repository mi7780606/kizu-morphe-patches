package app.morphe.extension.appearance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** フォロー中の「ライブ」を選んだとき、一番上に「あなたにおすすめのチャンネル」を出す */
public final class KizuRecommended {
    private static final String TAG = "KizuRecommended";
    private static final String VIEW_TAG = "kizu_recommended";
    private static final int COUNT = 4;
    private static final long CACHE_MS = 5 * 60_000L;
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    // Twitch 31.3.1 (r530) TabletHomeRowChannelRecsQuery の recommendedStreams を必要な項目だけにしたもの
    private static final String QUERY =
            "query KizuRecommendedStreams($first: Int, $recRequestID: ID!, $recLocation: String!,"
            + " $recContext: RecommendationsContext!) {"
            + " recommendedStreams(first: $first, recRequestID: $recRequestID, location: $recLocation,"
            + " context: $recContext) { edges { node { id title viewersCount"
            + " previewImageURL(width: 320, height: 180) game { displayName }"
            + " broadcaster { login displayName profileImageURL(width: 70) } } } } }";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<>(40);

    private static final class Item {
        String login, name, title, game, preview, avatar;
        int viewers;
    }

    private static List<Item> cache;
    private static long cachedAt;
    private static boolean loading;

    private KizuRecommended() {}

    /** KizuUiTweaks の画面スキャンから呼ぶ */
    static void update(View root) {
        boolean on;
        try { on = KizuSettings.RECOMMENDED_CHANNELS.get(); } catch (Throwable t) { on = true; }
        Context c = root.getContext();
        int listId = c.getResources().getIdentifier("following_list_recycler_view", "id", c.getPackageName());
        int headerId = c.getResources().getIdentifier("header_view_container", "id", c.getPackageName());
        if (listId == 0 || headerId == 0) return;
        View list = root.findViewById(listId);
        if (list == null) return;
        View hv = root.findViewById(headerId);
        if (!(hv instanceof FrameLayout)) return;
        FrameLayout header = (FrameLayout) hv;
        View existing = header.findViewWithTag(VIEW_TAG);

        boolean show = on && list.isShown() && liveSelected(root);
        if (!show) {
            if (existing != null) existing.setVisibility(View.GONE);
            return;
        }
        if (existing == null) {
            existing = buildSection(c);
            header.addView(existing, new FrameLayout.LayoutParams(-1, -2));
        }
        existing.setVisibility(View.VISIBLE);
        if (header.getVisibility() != View.VISIBLE) header.setVisibility(View.VISIBLE);
        load(c, (LinearLayout) existing);
    }

    /** 画面上部の「ライブ」が選ばれているか。見つからなければ表示する */
    private static boolean liveSelected(View root) {
        List<View> found = new ArrayList<>();
        findLabel(root, found, 0);
        if (found.isEmpty()) return true;
        for (View v : found) {
            for (View cur = v; cur != null && cur != root; ) {
                if (cur.isSelected() || cur.isActivated()
                        || (cur instanceof Checkable && ((Checkable) cur).isChecked())) return true;
                Object p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
                if (cur != null && cur.getParent() == null) break;
            }
        }
        return false;
    }

    private static void findLabel(View v, List<View> out, int depth) {
        if (depth > 30 || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.equals("ライブ") || s.equalsIgnoreCase("live")) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    // 画面の上の方（タブ・フィルター）にあるものだけ
                    if (loc[1] < v.getResources().getDisplayMetrics().heightPixels / 3) out.add(v);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findLabel(g.getChildAt(i), out, depth + 1);
        }
    }

    private static LinearLayout buildSection(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setTag(VIEW_TAG);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(d, 16), dp(d, 12), dp(d, 16), dp(d, 4));
        TextView title = new TextView(c);
        title.setText("あなたにおすすめのチャンネル");
        title.setTextColor(0xFFEFEFF1);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setPadding(0, 0, 0, dp(d, 8));
        box.addView(title);
        return box;
    }

    private static void load(Context c, LinearLayout box) {
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < CACHE_MS) {
            if (box.getTag(0x7f7e0001) != cache) fill(box, cache);
            return;
        }
        if (loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            List<Item> items = fetch(app);
            MAIN.post(() -> {
                loading = false;
                if (items == null) return;
                cache = items;
                cachedAt = System.currentTimeMillis();
                fill(box, items);
            });
        });
    }

    private static void fill(LinearLayout box, List<Item> items) {
        box.setTag(0x7f7e0001, items);
        while (box.getChildCount() > 1) box.removeViewAt(1);
        for (Item it : items) box.addView(row(box.getContext(), it));
        if (items.isEmpty()) box.setVisibility(View.GONE);
    }

    private static View row(Context c, Item it) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 6), 0, dp(d, 6));
        row.setClickable(true);
        row.setOnClickListener(v -> open(v.getContext(), it.login));

        FrameLayout thumbBox = new FrameLayout(c);
        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF26262C);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        TextView live = badge(c, d, "ライブ", 0xFFEB0400);
        FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        lp1.setMargins(dp(d, 4), dp(d, 4), 0, 0);
        thumbBox.addView(live, lp1);
        TextView viewers = badge(c, d, formatViewers(it.viewers), 0xCC000000);
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lp2.setMargins(dp(d, 4), 0, 0, dp(d, 4));
        thumbBox.addView(viewers, lp2);
        row.addView(thumbBox, new LinearLayout.LayoutParams(dp(d, 144), dp(d, 81)));
        image(it.preview, thumb);

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(d, 10), 0, 0, 0);
        LinearLayout nameRow = new LinearLayout(c);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(c);
        avatar.setClipToOutline(true);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF26262C);
        avatar.setBackground(circle);
        nameRow.addView(avatar, new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20)));
        image(it.avatar, avatar);
        TextView name = text(c, it.name, 14, 0xFFEFEFF1, true);
        name.setPadding(dp(d, 6), 0, 0, 0);
        nameRow.addView(name);
        info.addView(nameRow);
        info.addView(text(c, it.title, 13, 0xFFADADB8, false));
        info.addView(text(c, it.game, 13, 0xFFADADB8, false));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private static TextView badge(Context c, float d, String s, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(d, 4), dp(d, 1), dp(d, 4), dp(d, 1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(3 * d);
        t.setBackground(bg);
        return t;
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String formatViewers(int n) {
        if (n >= 10000) return String.format(Locale.JAPAN, "%.1f万人", n / 10000f);
        return String.format(Locale.JAPAN, "%,d人", n);
    }

    private static void open(Context c, String login) {
        if (login == null || login.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/" + login));
            i.setPackage(c.getPackageName());
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
        }
    }

    private static void image(String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap b = IMAGES.get(url);
        if (b != null) { into.setImageBitmap(b); return; }
        into.setTag(url);
        IO.execute(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(7000);
                con.setReadTimeout(10000);
                try (InputStream in = con.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp == null) return;
                    IMAGES.put(url, bmp);
                    MAIN.post(() -> { if (url.equals(into.getTag())) into.setImageBitmap(bmp); });
                } finally {
                    con.disconnect();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static List<Item> fetch(Context c) {
        try {
            String token = readAuthToken(c);
            JSONObject vars = new JSONObject()
                    .put("first", COUNT)
                    .put("recRequestID", UUID.randomUUID().toString())
                    .put("recLocation", "ANDROID_MEDIA_ROW")
                    .put("recContext", new JSONObject().put("platform", "android"));
            JSONObject req = new JSONObject()
                    .put("operationName", "KizuRecommendedStreams")
                    .put("variables", vars)
                    .put("query", QUERY);
            JSONObject res = post(token, req.toString());
            if (res == null) return null;
            JSONObject data = res.optJSONObject("data");
            JSONObject rs = data == null ? null : data.optJSONObject("recommendedStreams");
            JSONArray edges = rs == null ? null : rs.optJSONArray("edges");
            if (edges == null) {
                Log.i(TAG, "no data: " + res.optJSONArray("errors"));
                return null;
            }
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < edges.length() && out.size() < COUNT; i++) {
                JSONObject e = edges.optJSONObject(i);
                JSONObject node = e == null ? null : e.optJSONObject("node");
                if (node == null) continue;
                JSONObject b = node.optJSONObject("broadcaster");
                if (b == null) continue;
                Item it = new Item();
                it.login = b.optString("login", "");
                it.name = b.optString("displayName", it.login);
                it.avatar = b.optString("profileImageURL", "");
                it.title = node.optString("title", "");
                it.viewers = node.optInt("viewersCount", 0);
                it.preview = node.optString("previewImageURL", "");
                JSONObject g = node.optJSONObject("game");
                it.game = g == null ? "" : g.optString("displayName", "");
                if (!it.login.isEmpty()) out.add(it);
            }
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "fetch failed", t);
            return null;
        }
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

    private static JSONObject post(String token, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(GQL_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Client-ID", CLIENT_ID);
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "OAuth " + token);
            c.setRequestProperty("X-Device-ID", deviceId());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.getOutputStream().write(b);
            if (c.getResponseCode() / 100 != 2) {
                Log.i(TAG, "HTTP " + c.getResponseCode());
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } catch (Throwable e) {
            Log.i(TAG, "request failed: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String deviceId;

    /** おすすめの取得に必要な端末ID（このアプリ用に1回作って使い回す） */
    private static String deviceId() {
        if (deviceId != null) return deviceId;
        try {
            Context c = app.morphe.extension.Utils.getContext();
            SharedPreferences p = c.getSharedPreferences("uyu_settings", Context.MODE_PRIVATE);
            String id = p.getString("kizu_device_id", null);
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "");
                p.edit().putString("kizu_device_id", id).apply();
            }
            deviceId = id;
        } catch (Throwable t) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
        }
        return deviceId;
    }

    private static int dp(float d, int v) { return Math.round(v * d); }
}
