package app.morphe.extension.multistream;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;

/**
 * Twitch-styled BottomSheet for picking a followed live channel or typing any channel login
 * to add into Sub-PiP or Multi-View.
 */
final class ChannelPickerSheet {
    interface OnChannelPicked {
        void onPicked(String login, String displayName);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private ChannelPickerSheet() {}

    static void show(Context context, String titleText, Set<String> activeLogins, OnChannelPicked callback) {
        if (context == null || callback == null) return;
        Dialog dlg = TwitchUi.createBottomSheet(context);
        int pad = TwitchUi.dp(context, 16f);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, pad);
        root.addView(TwitchUi.dragHandle(context));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, TwitchUi.dp(context, 4f), 0, TwitchUi.dp(context, 10f));

        TextView title = new TextView(context);
        title.setText(titleText != null ? titleText : "配信を追加");
        title.setTextColor(SettingsUi.primaryTextColor(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(TwitchUi.createCloseButton(context, dlg::dismiss));
        root.addView(header);

        // Manual channel input row
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText edit = TwitchUi.createPillInput(context, "チャンネルIDを入力（例: fps_shaka）");
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, -2, 1f);
        editLp.rightMargin = TwitchUi.dp(context, 8f);
        inputRow.addView(edit, editLp);

        Runnable submitManual = () -> {
            String raw = edit.getText() == null ? "" : edit.getText().toString().trim();
            if (raw.startsWith("https://") || raw.startsWith("http://")) {
                int slash = raw.lastIndexOf('/');
                if (slash >= 0 && slash < raw.length() - 1) raw = raw.substring(slash + 1);
            }
            int q = raw.indexOf('?');
            if (q > 0) raw = raw.substring(0, q);
            String login = raw.replace("@", "").trim().toLowerCase(Locale.ROOT);
            if (login.isEmpty()) return;
            dlg.dismiss();
            callback.onPicked(login, login);
        };

        edit.setOnEditorActionListener((v, actionId, event) -> {
            submitManual.run();
            return true;
        });

        TextView addBtn = TwitchUi.createPrimaryButton(context, "追加");
        addBtn.setOnClickListener(v -> submitManual.run());
        inputRow.addView(addBtn, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(-1, -2);
        irLp.bottomMargin = TwitchUi.dp(context, 12f);
        root.addView(inputRow, irLp);

        TextView subTitle = new TextView(context);
        subTitle.setText("フォロー中のライブ配信");
        subTitle.setTextColor(SettingsUi.secondaryTextColor(context));
        subTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        subTitle.setTypeface(Typeface.DEFAULT_BOLD);
        subTitle.setPadding(0, 0, 0, TwitchUi.dp(context, 6f));
        root.addView(subTitle);

        int maxListH = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.48f);
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int bounded = View.MeasureSpec.makeMeasureSpec(maxListH, View.MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, bounded);
            }
        };
        LinearLayout listBox = new LinearLayout(context);
        listBox.setOrientation(LinearLayout.VERTICAL);

        ProgressBar progress = new ProgressBar(context);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(TwitchUi.dp(context, 28f), TwitchUi.dp(context, 28f));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = TwitchUi.dp(context, 18f);
        pLp.bottomMargin = TwitchUi.dp(context, 18f);
        listBox.addView(progress, pLp);

        scroll.addView(listBox, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TwitchUi.prepareBottomSheet(dlg, context, root);
        dlg.setContentView(root);
        dlg.show();

        Context appCtx = context.getApplicationContext();
        KizuRuntime.ioExecutor().execute(() -> {
            List<MultiStreamApi.ChannelInfo> items = MultiStreamApi.fetchFollowedLive(appCtx);
            MAIN.post(() -> {
                if (!dlg.isShowing()) return;
                listBox.removeAllViews();
                if (items.isEmpty()) {
                    TextView empty = new TextView(context);
                    empty.setText("フォロー中のライブ配信が見つかりませんでした。\n上の入力欄からチャンネルIDを直接追加できます。");
                    empty.setTextColor(SettingsUi.secondaryTextColor(context));
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, TwitchUi.dp(context, 16f), 0, TwitchUi.dp(context, 16f));
                    listBox.addView(empty);
                    return;
                }
                for (MultiStreamApi.ChannelInfo info : items) {
                    boolean active = activeLogins != null && activeLogins.contains(info.login);
                    listBox.addView(buildRow(context, info, active, () -> {
                        dlg.dismiss();
                        callback.onPicked(info.login, info.displayName);
                    }));
                }
            });
        });
    }

    private static View buildRow(Context c, MultiStreamApi.ChannelInfo info, boolean active, Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(TwitchUi.dp(c, 4f), TwitchUi.dp(c, 8f), TwitchUi.dp(c, 4f), TwitchUi.dp(c, 8f));
        row.setBackground(TwitchUi.selectableBackground(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        int bgAlt = SettingsUi.alternateBackgroundColor(c);
        FrameLayout thumbBox = new FrameLayout(c);
        thumbBox.setBackground(TwitchUi.roundedRect(bgAlt, TwitchUi.dp(c, 6f), 0, 0));
        thumbBox.setClipToOutline(true);

        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        loadImage(info.previewUrl, thumb);

        TextView viewers = new TextView(c);
        viewers.setText(formatViewers(info.viewers));
        viewers.setTextColor(Color.WHITE);
        viewers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        viewers.setTypeface(Typeface.DEFAULT_BOLD);
        viewers.setPadding(TwitchUi.dp(c, 4f), TwitchUi.dp(c, 1f), TwitchUi.dp(c, 4f), TwitchUi.dp(c, 1f));
        viewers.setBackground(TwitchUi.roundedRect(0xCC0E0E10, TwitchUi.dp(c, 3f), 0, 0));
        FrameLayout.LayoutParams vLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        vLp.setMargins(TwitchUi.dp(c, 4f), 0, 0, TwitchUi.dp(c, 4f));
        thumbBox.addView(viewers, vLp);

        row.addView(thumbBox, new LinearLayout.LayoutParams(TwitchUi.dp(c, 104f), TwitchUi.dp(c, 58f)));

        LinearLayout infoCol = new LinearLayout(c);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(TwitchUi.dp(c, 10f), 0, TwitchUi.dp(c, 6f), 0);

        LinearLayout nameRow = new LinearLayout(c);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(bgAlt);
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        nameRow.addView(avatar, new LinearLayout.LayoutParams(TwitchUi.dp(c, 18f), TwitchUi.dp(c, 18f)));
        loadImage(info.avatarUrl, avatar);

        TextView name = new TextView(c);
        name.setText(info.displayName);
        name.setTextColor(SettingsUi.primaryTextColor(c));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(TwitchUi.dp(c, 6f), 0, 0, 0);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        if (active) {
            TextView pill = new TextView(c);
            pill.setText("視聴中");
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 2f), TwitchUi.dp(c, 6f), TwitchUi.dp(c, 2f));
            pill.setBackground(TwitchUi.roundedRect(SettingsUi.accentStrongColor(c), TwitchUi.dp(c, 4f), 0, 0));
            nameRow.addView(pill);
        }
        infoCol.addView(nameRow);

        if (!info.title.isEmpty()) {
            TextView titleTv = new TextView(c);
            titleTv.setText(info.title);
            titleTv.setTextColor(SettingsUi.secondaryTextColor(c));
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            titleTv.setSingleLine(true);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            titleTv.setPadding(0, TwitchUi.dp(c, 2f), 0, 0);
            infoCol.addView(titleTv);
        }

        if (!info.game.isEmpty()) {
            TextView gameTv = new TextView(c);
            gameTv.setText(info.game);
            gameTv.setTextColor(SettingsUi.secondaryTextColor(c));
            gameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            gameTv.setSingleLine(true);
            gameTv.setEllipsize(TextUtils.TruncateAt.END);
            infoCol.addView(gameTv);
        }

        row.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private static String formatViewers(int n) {
        if (n >= 10000) return String.format(Locale.JAPAN, "%.1f万人", n / 10000f);
        return String.format(Locale.JAPAN, "%,d人", n);
    }

    private static void loadImage(String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = THUMB_CACHE.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(url);
        WeakReference<ImageView> ref = new WeakReference<>(into);
        KizuRuntime.ioExecutor().execute(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(7000);
                try (InputStream in = con.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp == null) return;
                    THUMB_CACHE.put(url, bmp);
                    MAIN.post(() -> {
                        ImageView target = ref.get();
                        if (target != null && url.equals(target.getTag())) {
                            target.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }
}
