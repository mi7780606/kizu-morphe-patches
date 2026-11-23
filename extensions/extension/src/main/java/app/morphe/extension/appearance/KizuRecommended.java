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
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.Utils;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;
import io.github.bakwudo.uyu.extension.settings.Setting;

/** フォロー中の「ライブ」を選んだとき、一番上に「あなたにおすすめのチャンネル」を出す */
public final class KizuRecommended {
    private static final String TAG = "KizuRecommended";
    private static final String VIEW_TAG = "kizu_recommended";
    private static final int TAG_ITEMS = 0x7F7E0001;
    private static final int TAG_THEME = 0x7F7E0002;
    private static final int TAG_BOUND = 0x7F7E0003;
    private static final int COUNT = 4;
    private static final long CACHE_MS = 5 * 60_000L;
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    private static final String QUERY =
            "query KizuRecommendedStreams($first: Int, $recRequestID: ID!, $recLocation: String!,"
            + " $recContext: RecommendationsContext!) {"
            + " recommendedStreams(first: $first, recRequestID: $recRequestID, location: $recLocation,"
            + " context: $recContext) { edges { node { id title viewersCount"
            + " previewImageURL(width: 320, height: 180) game { displayName }"
            + " broadcaster { login displayName profileImageURL(width: 70) } } } } }";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private static final class Item {
        String login, name, title, game, preview, avatar;
        int viewers;
    }

    private static List<Item> cache;
    private static long cachedAt;
    private static boolean loading;
    private static WeakReference<View> liveTabRef = new WeakReference<>(null);
    private static WeakReference<View> boundRecyclerRef = new WeakReference<>(null);
    private static WeakReference<View> boundDelegateRef = new WeakReference<>(null);
    private static boolean prefListenerRegistered;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key == null || KizuSettings.RECOMMENDED_CHANNELS.key.equals(key)) {
                    MAIN.post(() -> {
                        View del = boundDelegateRef.get();
                        View rec = boundRecyclerRef.get();
                        if (del != null && rec != null && rec.isAttachedToWindow()) {
                            update(del, rec);
                        }
                    });
                }
            };

    private KizuRecommended() {}

    /**
     * Binds directly to Twitch's Following RecyclerView created in HidePromotionsPatch.onViewCreated.
     * Avoids any DecorView OnGlobalLayoutListener polling.
     */
    public static void bindFollowingRoot(View delegateRoot, View followingRecycler) {
        if (delegateRoot == null || followingRecycler == null) return;
        ensurePrefListener();
        boundDelegateRef = new WeakReference<>(delegateRoot);
        boundRecyclerRef = new WeakReference<>(followingRecycler);
        if (!Boolean.TRUE.equals(followingRecycler.getTag(TAG_BOUND))) {
            followingRecycler.setTag(TAG_BOUND, Boolean.TRUE);
            WeakReference<View> delRef = new WeakReference<>(delegateRoot);
            WeakReference<View> recRef = new WeakReference<>(followingRecycler);
            final boolean[] updatePosted = {false};
            Runnable trigger = () -> {
                updatePosted[0] = false;
                View del = delRef.get();
                View rec = recRef.get();
                if (del != null && rec != null && rec.isAttachedToWindow()) {
                    update(del, rec);
                }
            };
            View.OnLayoutChangeListener listener = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (!updatePosted[0]) {
                    updatePosted[0] = true;
                    v.post(trigger);
                }
            };
            followingRecycler.addOnLayoutChangeListener(listener);
            View tabStrip = findTabStrip(delegateRoot);
            if (tabStrip != null) tabStrip.addOnLayoutChangeListener(listener);
            followingRecycler.post(trigger);
        } else {
            update(delegateRoot, followingRecycler);
        }
    }

    private static void ensurePrefListener() {
        if (prefListenerRegistered) return;
        prefListenerRegistered = true;
        Setting.addChangeListener(PREF_LISTENER);
    }

    /** Compatibility entry point if called directly. */
    static void update(View root) {
        if (root == null) return;
        int listId = Utils.getResourceId(root.getContext(), "following_list_recycler_view", "id");
        View list = listId != 0 ? root.findViewById(listId) : null;
        if (list != null) update(root, list);
    }

    private static void update(View delegateRoot, View list) {
        boolean on;
        try { on = KizuSettings.RECOMMENDED_CHANNELS.get(); } catch (Throwable t) { on = true; }
        Context c = list.getContext();
        int headerId = Utils.getResourceId(c, "header_view_container", "id");
        if (headerId == 0) return;

        View hv = delegateRoot.findViewById(headerId);
        if (hv == null) {
            View top = delegateRoot.getRootView();
            if (top != null) hv = top.findViewById(headerId);
        }
        if (!(hv instanceof FrameLayout header)) return;
        View existing = header.findViewWithTag(VIEW_TAG);

        boolean show = on && list.isShown() && liveSelected(delegateRoot);
        if (!show) {
            if (existing != null && existing.getVisibility() != View.GONE) {
                existing.setVisibility(View.GONE);
            }
            return;
        }
        if (existing == null) {
            existing = buildSection(c);
            header.addView(existing, new FrameLayout.LayoutParams(-1, -2));
        }
        if (existing.getVisibility() != View.VISIBLE) existing.setVisibility(View.VISIBLE);
        if (header.getVisibility() != View.VISIBLE) header.setVisibility(View.VISIBLE);
        load(c, (LinearLayout) existing);
    }

    private static View findTabStrip(View delegateRoot) {
        Context c = delegateRoot.getContext();
        View top = delegateRoot.getRootView();
        int id1 = Utils.getResourceId(c, "tab_layout", "id");
        if (id1 != 0) {
            View v = delegateRoot.findViewById(id1);
            if (v == null && top != null) v = top.findViewById(id1);
            if (v != null) return v;
        }
        int id2 = Utils.getResourceId(c, "tab_layout_no_tab_width", "id");
        if (id2 != 0) {
            View v = delegateRoot.findViewById(id2);
            if (v == null && top != null) v = top.findViewById(id2);
            if (v != null) return v;
        }
        return null;
    }

    /** 画面上部の「ライブ」が選ばれているか。見つからなければ表示する */
    private static boolean liveSelected(View delegateRoot) {
        View cachedTab = liveTabRef.get();
        if (cachedTab != null && cachedTab.isAttachedToWindow() && cachedTab.isShown()) {
            return isSelectedInAncestors(cachedTab);
        }
        View tabStrip = findTabStrip(delegateRoot);
        View searchRoot = tabStrip != null ? tabStrip : delegateRoot.getRootView();
        if (searchRoot == null) return true;

        List<View> found = new ArrayList<>(2);
        int maxY = tabStrip != null ? Integer.MAX_VALUE
                : searchRoot.getResources().getDisplayMetrics().heightPixels / 3;
        findLabel(searchRoot, found, 0, maxY);
        if (found.isEmpty()) return true;
        liveTabRef = new WeakReference<>(found.get(0));
        for (View v : found) {
            if (isSelectedInAncestors(v)) {
                liveTabRef = new WeakReference<>(v);
                return true;
            }
        }
        return false;
    }

    private static boolean isSelectedInAncestors(View v) {
        for (View cur = v; cur != null; ) {
            if (cur.isSelected() || cur.isActivated()
                    || (cur instanceof Checkable && ((Checkable) cur).isChecked())) return true;
            Object p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static void findLabel(View v, List<View> out, int depth, int maxY) {
        if (depth > 16 || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView tv) {
            CharSequence t = tv.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.equals("ライブ") || s.equalsIgnoreCase("live")) {
                    if (maxY == Integer.MAX_VALUE) {
                        out.add(v);
                    } else {
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        if (loc[1] < maxY) out.add(v);
                    }
                }
            }
            return;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                findLabel(g.getChildAt(i), out, depth + 1, maxY);
            }
        }
    }

    private static LinearLayout buildSection(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setTag(VIEW_TAG);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(d, 10), 0, dp(d, 4));
        TextView title = new TextView(c);
        title.setText("あなたにおすすめのチャンネル");
        title.setTextColor(SettingsUi.primaryTextColor(c));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setPadding(dp(d, 16), 0, dp(d, 16), dp(d, 6));
        box.addView(title);
        return box;
    }

    private static void load(Context c, LinearLayout box) {
        boolean dark = SettingsUi.isDark(c);
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < CACHE_MS) {
            boolean sameItems = box.getTag(TAG_ITEMS) == cache;
            boolean sameTheme = Boolean.valueOf(dark).equals(box.getTag(TAG_THEME));
            if (!sameItems || !sameTheme) fill(box, cache, dark);
            return;
        }
        if (loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        KizuRuntime.ioExecutor().execute(() -> {
            List<Item> items = fetch(app);
            MAIN.post(() -> {
                loading = false;
                if (items == null) return;
                cache = items;
                cachedAt = System.currentTimeMillis();
                fill(box, items, SettingsUi.isDark(box.getContext()));
            });
        });
    }

    private static void fill(LinearLayout box, List<Item> items, boolean dark) {
        box.setTag(TAG_ITEMS, items);
        box.setTag(TAG_THEME, dark);
        Context c = box.getContext();
        if (box.getChildCount() > 0 && box.getChildAt(0) instanceof TextView title) {
            title.setTextColor(SettingsUi.primaryTextColor(c));
        }
        while (box.getChildCount() > 1) box.removeViewAt(1);
        for (Item it : items) box.addView(row(c, it));
        if (items.isEmpty()) box.setVisibility(View.GONE);
    }

    private static View row(Context c, Item it) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 16), dp(d, 7), dp(d, 16), dp(d, 7));
        row.setBackground(TwitchUi.selectableBackground(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> open(v.getContext(), it.login));

        int thumbBgColor = SettingsUi.alternateBackgroundColor(c);
        FrameLayout thumbBox = new FrameLayout(c);
        thumbBox.setBackground(TwitchUi.roundedRect(thumbBgColor, 6f * d, 0, 0));
        thumbBox.setClipToOutline(true);

        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

        TextView live = badge(c, d, "ライブ", 0xFFEB0400);
        FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        lp1.setMargins(dp(d, 5), dp(d, 5), 0, 0);
        thumbBox.addView(live, lp1);

        TextView viewers = badge(c, d, formatViewers(it.viewers), 0xCC0E0E10);
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lp2.setMargins(dp(d, 5), 0, 0, dp(d, 5));
        thumbBox.addView(viewers, lp2);

        row.addView(thumbBox, new LinearLayout.LayoutParams(dp(d, 144), dp(d, 81)));
        image(it.preview, thumb);

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(d, 12), 0, 0, 0);

        LinearLayout nameRow = new LinearLayout(c);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(c);
        avatar.setClipToOutline(true);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(thumbBgColor);
        avatar.setBackground(circle);
        nameRow.addView(avatar, new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20)));
        image(it.avatar, avatar);

        TextView name = text(c, it.name, 14.5f, SettingsUi.primaryTextColor(c), true);
        name.setPadding(dp(d, 7), 0, 0, 0);
        nameRow.addView(name);
        info.addView(nameRow);

        TextView titleTv = text(c, it.title, 13f, SettingsUi.secondaryTextColor(c), false);
        titleTv.setPadding(0, dp(d, 3), 0, dp(d, 1));
        info.addView(titleTv);

        if (it.game != null && !it.game.isEmpty()) {
            info.addView(text(c, it.game, 12.5f, SettingsUi.secondaryTextColor(c), false));
        }
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private static TextView badge(Context c, float d, String s, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(d, 5), dp(d, 1), dp(d, 5), dp(d, 1));
        t.setBackground(TwitchUi.roundedRect(color, 4f * d, 0, 0));
        return t;
    }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
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
        WeakReference<ImageView> targetRef = new WeakReference<>(into);
        KizuRuntime.ioExecutor().execute(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(7000);
                con.setReadTimeout(10000);
                try (InputStream in = con.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp == null) return;
                    IMAGES.put(url, bmp);
                    MAIN.post(() -> {
                        ImageView target = targetRef.get();
                        if (target != null && url.equals(target.getTag())) {
                            target.setImageBitmap(bmp);
                        }
                    });
                } finally {
                    con.disconnect();
                }
            } catch (Throwable ignored) {}
        });
    }

    private static List<Item> fetch(Context c) {
        try {
            String token = KizuRuntime.readAuthToken(c);
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

    private static String deviceId() {
        if (deviceId != null) return deviceId;
        try {
            Context c = Utils.getContext();
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
