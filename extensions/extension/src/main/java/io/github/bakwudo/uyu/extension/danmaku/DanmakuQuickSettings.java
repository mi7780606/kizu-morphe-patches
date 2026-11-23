package io.github.bakwudo.uyu.extension.danmaku;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.IntSetting;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;
import io.github.bakwudo.uyu.extension.settings.Settings;

final class DanmakuQuickSettings {
    private interface IntFn { int apply(int x); }
    private interface LabelFn { String apply(int x); }

    private static final IntSetting[] RESETTABLE = {
            Settings.DANMAKU_ROWS, Settings.DANMAKU_DURATION, Settings.DANMAKU_OPACITY,
            Settings.DANMAKU_AREA, Settings.DANMAKU_MAX_COMMENTS, Settings.DANMAKU_OUTLINE_WIDTH,
    };

    private final View anchor;
    private final List<Runnable> sliderSyncs = new ArrayList<>();
    private FrameLayout container;
    private FrameLayout scrim;
    private View panel;
    private final View.OnLayoutChangeListener relayout =
            (v, l, t, r, b, ol, ot, or, ob) -> place();

    DanmakuQuickSettings(View anchor) { this.anchor = anchor; }

    boolean isShowing() { return scrim != null && scrim.getParent() != null; }

    void toggle() { if (isShowing()) dismiss(); else show(); }

    void dismiss() {
        FrameLayout s = scrim;
        scrim = null;
        panel = null;
        sliderSyncs.clear();
        anchor.removeOnLayoutChangeListener(relayout);
        if (container != null) container.removeOnLayoutChangeListener(relayout);
        container = null;
        if (s == null || !(s.getParent() instanceof ViewGroup parent)) return;
        if (s.isAttachedToWindow()) {
            s.animate().cancel();
            s.setOnClickListener(null);
            s.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (s.getParent() instanceof ViewGroup p) p.removeView(s);
            }).start();
        } else {
            parent.removeView(s);
        }
    }

    private void show() {
        try { build(); } catch (Exception ex) { Utils.logError("Danmaku: failed to show the quick settings", ex); dismiss(); }
    }

    private FrameLayout findContainer() {
        View root = anchor.getRootView();
        View content = root != null ? root.findViewById(android.R.id.content) : null;
        if (content instanceof FrameLayout f) return f;
        if (root instanceof FrameLayout f) return f;
        return anchor instanceof FrameLayout f ? f : null;
    }

    private void build() {
        FrameLayout host = findContainer();
        if (host == null) { Utils.logInfo("Danmaku: no container for the quick settings"); return; }
        dismiss();
        Context c = anchor.getContext();
        float d = c.getResources().getDisplayMetrics().density;

        FrameLayout backdrop = new FrameLayout(c);
        backdrop.setBackgroundColor(0x66000000);
        backdrop.setClickable(true);
        backdrop.setFocusable(false);
        backdrop.setTranslationZ(64 * d);
        backdrop.setOnClickListener(v -> dismiss());

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 12));
        int bgColor = SettingsUi.surfaceColor(c);
        root.setBackground(TwitchUi.roundedRect(bgColor, 16 * d, Math.max(1, Math.round(d)), SettingsUi.borderColor(c)));
        root.setElevation(12 * d);
        root.setClickable(true);
        root.setFocusable(false);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(d, 6));
        header.addView(text(c, "弾幕の設定", 15, true, SettingsUi.primaryTextColor(c)),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView reset = TwitchUi.createGhostButton(c, "リセット", SettingsUi.accentColor(c));
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        reset.setPadding(dp(d, 10), dp(d, 6), dp(d, 10), dp(d, 6));
        reset.setOnClickListener(v -> {
            for (IntSetting s : RESETTABLE) s.save(s.defaultValue);
            for (Runnable sync : sliderSyncs) sync.run();
        });
        View close = TwitchUi.createCloseButton(c, this::dismiss);
        header.addView(reset);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(d, 32), dp(d, 32));
        closeLp.leftMargin = dp(d, 2);
        header.addView(close, closeLp);
        root.addView(header);
        root.addView(TwitchUi.divider(c));

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(d, 2), 0, dp(d, 4));
        addSlider(list, d, "文字サイズ（右ほど大きい）", Settings.DANMAKU_ROWS, 25,
                p -> 30 - p, v -> 30 - v, v -> v + "行");
        addSlider(list, d, "速さ（右ほど速い）", Settings.DANMAKU_DURATION, 32,
                p -> 10000 - p * 250, v -> (10000 - v) / 250,
                v -> String.format(Locale.US, "%.1f秒", v / 1000f));
        addSlider(list, d, "不透明度", Settings.DANMAKU_OPACITY, 90,
                p -> 10 + p, v -> v - 10, v -> v + "%");
        addSlider(list, d, "表示範囲（上から）", Settings.DANMAKU_AREA, 90,
                p -> 10 + p, v -> v - 10, v -> v + "%");
        addSlider(list, d, "同時に表示する数", Settings.DANMAKU_MAX_COMMENTS, 38,
                p -> 10 + p * 5, v -> (v - 10) / 5, v -> v + "件");
        addSlider(list, d, "縁取りの太さ", Settings.DANMAKU_OUTLINE_WIDTH, 30,
                p -> p, v -> v, String::valueOf);
        addSlider(list, d, "長文を流さない（左端でオフ）", KizuSettings.DANMAKU_MAX_LENGTH, 40,
                p -> p * 5, v -> v / 5, v -> v == 0 ? "制限なし" : v + "文字まで");

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(d, 2);
        root.addView(scroll, scrollLp);

        backdrop.addView(root, new FrameLayout.LayoutParams(dp(d, 280), dp(d, 240), Gravity.TOP | Gravity.LEFT));
        host.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        container = host;
        scrim = backdrop;
        panel = root;
        place();
        anchor.addOnLayoutChangeListener(relayout);
        host.addOnLayoutChangeListener(relayout);

        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(150).start();
        root.setAlpha(0f);
        root.setTranslationY(12 * d);
        root.animate().alpha(1f).translationY(0f).setDuration(170).start();
    }

    private void place() {
        View p = panel; FrameLayout c = container;
        if (p == null || c == null) return;
        float d = c.getResources().getDisplayMetrics().density;
        int m = dp(d, 8);
        int left, top, w, h;
        if (anchor.isAttachedToWindow() && anchor.getWidth() > 0 && anchor.getHeight() > 0) {
            int[] a = new int[2]; int[] o = new int[2];
            anchor.getLocationInWindow(a); c.getLocationInWindow(o);
            left = a[0] - o[0]; top = a[1] - o[1]; w = anchor.getWidth(); h = anchor.getHeight();
        } else { left = 0; top = 0; w = c.getWidth(); h = c.getHeight(); }
        int width = Math.min(dp(d, 320), Math.max(dp(d, 200), w - m * 2));
        int height = Math.max(dp(d, 160), h - m * 2);
        int leftMargin = Math.max(0, left + w - width - m);
        int topMargin = Math.max(0, top + m);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p.getLayoutParams();
        if (lp.width == width && lp.height == height && lp.leftMargin == leftMargin && lp.topMargin == topMargin) return;
        lp.width = width; lp.height = height; lp.leftMargin = leftMargin; lp.topMargin = topMargin;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        p.post(() -> { if (panel == p) p.setLayoutParams(lp); });
    }

    private void addSlider(LinearLayout list, float d, String title, IntSetting setting, int max,
                           IntFn toSetting, IntFn toProgress, LabelFn label) {
        Context c = list.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(c, title, 13, false, SettingsUi.primaryTextColor(c)),
                new LinearLayout.LayoutParams(0, -2, 1));

        TextView value = text(c, "", 12, true, SettingsUi.accentColor(c));
        value.setFontFeatureSettings("tnum");
        value.setPadding(dp(d, 8), dp(d, 2), dp(d, 8), dp(d, 2));
        value.setBackground(TwitchUi.roundedRect(SettingsUi.inputBackgroundColor(c), 6 * d, 0, 0));
        row.addView(value);

        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        TwitchUi.styleSeekBar(c, bar);
        Runnable sync = () -> {
            int current = setting.get();
            bar.setProgress(Math.max(0, Math.min(max, toProgress.apply(current))));
            value.setText(label.apply(current));
        };
        sync.run();
        sliderSyncs.add(sync);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = Math.max(setting.min, Math.min(setting.max, toSetting.apply(progress)));
                value.setText(label.apply(v));
                if (v != setting.get()) setting.save(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(d, 10);
        list.addView(row, rowLp);
        list.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private static TextView text(Context c, String s, int sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static int dp(float density, int v) { return Math.round(v * density); }

    static final class Icon extends Drawable {
        private static final float[] YS = {0.25f, 0.5f, 0.75f};
        private static final float[] KNOBS = {0.35f, 0.68f, 0.48f};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private ColorStateList tint = ColorStateList.valueOf(Color.WHITE);
        private int alpha = 255;

        Icon(int size) {
            this.size = size;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            float left = b.left + (b.width() - s) / 2f;
            float top = b.top + (b.height() - s) / 2f;
            int color = tint.getColorForState(getState(), tint.getDefaultColor());
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);
            for (int i = 0; i < 3; i++) {
                float y = top + s * YS[i];
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.08f);
                canvas.drawLine(left + s * 0.14f, y, left + s * 0.86f, y, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(left + s * KNOBS[i], y, s * 0.1f, paint);
            }
        }

        @Override public void setTintList(ColorStateList tint) {
            this.tint = tint == null ? ColorStateList.valueOf(Color.WHITE) : tint;
            invalidateSelf();
        }
        @Override public boolean isStateful() { return tint.isStateful(); }
        @Override protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return tint.isStateful();
        }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}