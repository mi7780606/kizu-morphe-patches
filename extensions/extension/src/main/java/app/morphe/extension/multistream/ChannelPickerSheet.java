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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;

/**
 * Twitch-styled BottomSheet for searching live channels/games by keyword (just like Twitch's
 * native search), picking followed live channels, or pasting a direct channel ID/URL.
 */
final class ChannelPickerSheet {
    interface OnChannelPicked {
        void onPicked(String login, String displayName);
    }

    private static final long SEARCH_DEBOUNCE_MS = 350L;
    private static final Pattern VALID_LOGIN_PATTERN = Pattern.compile("^[a-z0-9_]{3,25}$");

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
        Window w = dlg.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

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

        // Search input row (keyword search + URL/ID support)
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout inputWrap = new FrameLayout(context);
        EditText edit = TwitchUi.createPillInput(context, "配信者・ゲーム名で検索 / URL");
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setPadding(TwitchUi.dp(context, 14f), TwitchUi.dp(context, 10f),
                TwitchUi.dp(context, 34f), TwitchUi.dp(context, 10f));
        inputWrap.addView(edit, new FrameLayout.LayoutParams(-1, -2));

        TextView clearBtn = new TextView(context);
        clearBtn.setText("✕");
        clearBtn.setTextColor(SettingsUi.secondaryTextColor(context));
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setVisibility(View.GONE);
        clearBtn.setClickable(true);
        clearBtn.setFocusable(true);
        int clearSize = TwitchUi.dp(context, 30f);
        FrameLayout.LayoutParams clrLp = new FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END | Gravity.CENTER_VERTICAL);
        clrLp.rightMargin = TwitchUi.dp(context, 4f);
        inputWrap.addView(clearBtn, clrLp);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(0, -2, 1f);
        wrapLp.rightMargin = TwitchUi.dp(context, 8f);
        inputRow.addView(inputWrap, wrapLp);

        TextView searchBtn = TwitchUi.createPrimaryButton(context, "検索");
        inputRow.addView(searchBtn, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(-1, -2);
        irLp.bottomMargin = TwitchUi.dp(context, 10f);
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
        showLoading(context, listBox);

        scroll.addView(listBox, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TwitchUi.prepareBottomSheet(dlg, context, root);
        dlg.setContentView(root);
        dlg.show();

        Context appCtx = context.getApplicationContext();
        AtomicInteger searchSeq = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        final List<MultiStreamApi.ChannelInfo>[] followedCache = new List[]{null};
        final Runnable[] pendingDebounce = new Runnable[1];

        Runnable renderFollowed = () -> {
            subTitle.setText("フォロー中のライブ配信");
            List<MultiStreamApi.ChannelInfo> items = followedCache[0];
            if (items == null) {
                showLoading(context, listBox);
                return;
            }
            listBox.removeAllViews();
            if (items.isEmpty()) {
                addEmptyMessage(context, listBox,
                        "フォロー中のライブ配信が見つかりませんでした。\n上の検索欄から配信者名やゲーム名で探せます。");
                return;
            }
            for (MultiStreamApi.ChannelInfo info : items) {
                boolean active = activeLogins != null && activeLogins.contains(info.login);
                listBox.addView(buildRow(context, info, active, () -> {
                    dlg.dismiss();
                    callback.onPicked(info.login, info.displayName);
                }));
            }
        };

        Runnable executeSearch = () -> {
            if (pendingDebounce[0] != null) {
                MAIN.removeCallbacks(pendingDebounce[0]);
                pendingDebounce[0] = null;
            }
            String raw = edit.getText() == null ? "" : edit.getText().toString().trim();
            if (raw.isEmpty()) {
                searchSeq.incrementAndGet();
                renderFollowed.run();
                return;
            }

            // If user pasted a direct Twitch URL (https://www.twitch.tv/xxx), open directly.
            String urlLogin = extractLoginFromUrl(raw);
            if (urlLogin != null) {
                hideKeyboard(context, edit);
                dlg.dismiss();
                callback.onPicked(urlLogin, urlLogin);
                return;
            }

            int seq = searchSeq.incrementAndGet();
            subTitle.setText("「" + raw + "」を検索中...");
            showLoading(context, listBox);

            KizuRuntime.ioExecutor().execute(() -> {
                List<MultiStreamApi.ChannelInfo> results = MultiStreamApi.searchLiveChannels(appCtx, raw);
                MAIN.post(() -> {
                    if (!dlg.isShowing() || searchSeq.get() != seq) return;
                    listBox.removeAllViews();
                    int liveCount = 0;
                    boolean directLoginPresent = false;
                    String directCandidate = extractDirectLoginCandidate(raw);
                    for (MultiStreamApi.ChannelInfo info : results) {
                        if (info.live) liveCount++;
                        if (directCandidate != null && directCandidate.equals(info.login)) {
                            directLoginPresent = true;
                        }
                    }

                    subTitle.setText(liveCount > 0
                            ? "「" + raw + "」の検索結果（ライブ " + liveCount + "件）"
                            : "「" + raw + "」の検索結果");

                    if (results.isEmpty()) {
                        addEmptyMessage(context, listBox,
                                "「" + raw + "」に一致する配信が見つかりませんでした。");
                    } else {
                        for (MultiStreamApi.ChannelInfo info : results) {
                            boolean active = activeLogins != null && activeLogins.contains(info.login);
                            listBox.addView(buildRow(context, info, active, () -> {
                                hideKeyboard(context, edit);
                                dlg.dismiss();
                                callback.onPicked(info.login, info.displayName);
                            }));
                        }
                    }

                    // Always allow opening an exact channel ID directly if not already listed.
                    if (directCandidate != null && !directLoginPresent) {
                        listBox.addView(buildDirectOpenRow(context, directCandidate, () -> {
                            hideKeyboard(context, edit);
                            dlg.dismiss();
                            callback.onPicked(directCandidate, directCandidate);
                        }));
                    }
                });
            });
        };

        clearBtn.setOnClickListener(v -> {
            edit.setText("");
            searchSeq.incrementAndGet();
            renderFollowed.run();
        });

        searchBtn.setOnClickListener(v -> {
            hideKeyboard(context, edit);
            executeSearch.run();
        });

        edit.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(context, edit);
            executeSearch.run();
            return true;
        });

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String text = s == null ? "" : s.toString().trim();
                clearBtn.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                boolean isUrl = extractLoginFromUrl(text) != null;
                searchBtn.setText(isUrl ? "開く" : "検索");

                if (pendingDebounce[0] != null) {
                    MAIN.removeCallbacks(pendingDebounce[0]);
                    pendingDebounce[0] = null;
                }
                if (text.isEmpty()) {
                    searchSeq.incrementAndGet();
                    renderFollowed.run();
                    return;
                }
                if (isUrl) return;
                Runnable run = () -> {
                    if (dlg.isShowing()) executeSearch.run();
                };
                pendingDebounce[0] = run;
                MAIN.postDelayed(run, SEARCH_DEBOUNCE_MS);
            }
        });

        dlg.setOnDismissListener(d -> {
            searchSeq.incrementAndGet();
            if (pendingDebounce[0] != null) {
                MAIN.removeCallbacks(pendingDebounce[0]);
                pendingDebounce[0] = null;
            }
        });

        // Initial load of followed live channels
        KizuRuntime.ioExecutor().execute(() -> {
            List<MultiStreamApi.ChannelInfo> items = MultiStreamApi.fetchFollowedLive(appCtx);
            MAIN.post(() -> {
                followedCache[0] = items != null ? items : Collections.emptyList();
                if (!dlg.isShowing()) return;
                String cur = edit.getText() == null ? "" : edit.getText().toString().trim();
                if (cur.isEmpty()) {
                    renderFollowed.run();
                }
            });
        });
    }

    private static void showLoading(Context context, LinearLayout listBox) {
        listBox.removeAllViews();
        ProgressBar progress = new ProgressBar(context);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                TwitchUi.dp(context, 28f), TwitchUi.dp(context, 28f));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = TwitchUi.dp(context, 18f);
        pLp.bottomMargin = TwitchUi.dp(context, 18f);
        listBox.addView(progress, pLp);
    }

    private static void addEmptyMessage(Context context, LinearLayout listBox, String message) {
        TextView empty = new TextView(context);
        empty.setText(message);
        empty.setTextColor(SettingsUi.secondaryTextColor(context));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, TwitchUi.dp(context, 16f), 0, TwitchUi.dp(context, 16f));
        listBox.addView(empty);
    }

    private static View buildDirectOpenRow(Context c, String login, Runnable onClick) {
        TextView row = new TextView(c);
        row.setText("＋ チャンネルID「" + login + "」を直接追加");
        row.setTextColor(SettingsUi.accentColor(c));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(TwitchUi.dp(c, 10f), TwitchUi.dp(c, 12f), TwitchUi.dp(c, 10f), TwitchUi.dp(c, 12f));
        row.setBackground(TwitchUi.selectableBackground(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static String extractLoginFromUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")
                && !lower.contains("twitch.tv/")) {
            return null;
        }
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        if (slash < 0 || slash >= s.length() - 1) return null;
        String candidate = s.substring(slash + 1).replace("@", "").trim().toLowerCase(Locale.ROOT);
        return VALID_LOGIN_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static String extractDirectLoginCandidate(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim().replace("@", "").toLowerCase(Locale.ROOT);
        return VALID_LOGIN_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static void hideKeyboard(Context context, View anchor) {
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && anchor != null) {
                imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private static View buildRow(Context c, MultiStreamApi.ChannelInfo info, boolean active, Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(TwitchUi.dp(c, 4f), TwitchUi.dp(c, 8f), TwitchUi.dp(c, 4f), TwitchUi.dp(c, 8f));
        row.setBackground(TwitchUi.selectableBackground(c));
        row.setAlpha(info.live ? 1f : 0.65f);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        int bgAlt = SettingsUi.alternateBackgroundColor(c);
        if (info.live) {
            FrameLayout thumbBox = new FrameLayout(c);
            thumbBox.setBackground(TwitchUi.roundedRect(bgAlt, TwitchUi.dp(c, 6f), 0, 0));
            thumbBox.setClipToOutline(true);

            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            loadImage(info.previewUrl, thumb);

            TextView viewers = new TextView(c);
            viewers.setText("● " + formatViewers(info.viewers));
            viewers.setTextColor(Color.WHITE);
            viewers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            viewers.setTypeface(Typeface.DEFAULT_BOLD);
            viewers.setPadding(TwitchUi.dp(c, 4f), TwitchUi.dp(c, 1f), TwitchUi.dp(c, 4f), TwitchUi.dp(c, 1f));
            viewers.setBackground(TwitchUi.roundedRect(0xCC0E0E10, TwitchUi.dp(c, 3f), 0, 0));
            FrameLayout.LayoutParams vLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
            vLp.setMargins(TwitchUi.dp(c, 4f), 0, 0, TwitchUi.dp(c, 4f));
            thumbBox.addView(viewers, vLp);

            row.addView(thumbBox, new LinearLayout.LayoutParams(TwitchUi.dp(c, 104f), TwitchUi.dp(c, 58f)));
        }

        LinearLayout infoCol = new LinearLayout(c);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(TwitchUi.dp(c, info.live ? 10f : 4f), 0, TwitchUi.dp(c, 6f), 0);

        LinearLayout nameRow = new LinearLayout(c);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(bgAlt);
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        int avSize = TwitchUi.dp(c, info.live ? 18f : 28f);
        nameRow.addView(avatar, new LinearLayout.LayoutParams(avSize, avSize));
        loadImage(info.avatarUrl, avatar);

        String displayLabel = info.displayName;
        if (!info.login.equalsIgnoreCase(info.displayName)) {
            displayLabel = info.displayName + " (" + info.login + ")";
        }

        TextView name = new TextView(c);
        name.setText(displayLabel);
        name.setTextColor(SettingsUi.primaryTextColor(c));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(TwitchUi.dp(c, 6f), 0, TwitchUi.dp(c, 4f), 0);
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
        } else if (!info.live) {
            TextView pill = new TextView(c);
            pill.setText("オフライン");
            pill.setTextColor(SettingsUi.secondaryTextColor(c));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            pill.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 2f), TwitchUi.dp(c, 6f), TwitchUi.dp(c, 2f));
            pill.setBackground(TwitchUi.roundedRect(bgAlt, TwitchUi.dp(c, 4f), 0, 0));
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
