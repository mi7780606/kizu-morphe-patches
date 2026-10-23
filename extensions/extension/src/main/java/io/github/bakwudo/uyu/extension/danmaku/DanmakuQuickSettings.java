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
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.settings.IntSetting;
import io.github.bakwudo.uyu.extension.settings.Settings;

final class DanmakuQuickSettings {
    private interface IntFn { int apply(int x); }
    private interface LabelFn { String apply(int x); }

    private static final IntSetting[] RESETTABLE = {
            Settings.DANMAKU_ROWS, Settings.DANMAKU_DURATION, Settings.DANMAKU_OPACITY,
            Settings.DANMAKU_AREA, Settings.DANMAKU_MAX_COMMENTS, Settings.DANMAKU_OUTLINE_WIDTH,
    };

    private final View anchor;
    private FrameLayout container;
    private View panel;
    private final View.OnLayoutChangeListener relayout =
            (v, l, t, r, b, ol, ot, or, ob) -> place();

    DanmakuQuickSettings(View anchor) { this.anchor = anchor; }

    boolean isShowing() { return panel != null && panel.getParent() != null; }

    void toggle() { if (isShowing()) dismiss(); else show(); }

    void dismiss() {
        View p = panel; panel = null;
        anchor.removeOnLayoutChangeListener(relayout);
        if (container != null) container.removeOnLayoutChangeListener(relayout);
        container = null;
        if (p != null && p.getParent() instanceof ViewGroup parent) parent.removeView(p);
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
        Context c = anchor.getContext();
        float d = c.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d,14), dp(d,6), dp(d,14), dp(d,10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141418);
        bg.setCornerRadius(12 * d);
        root.setBackground(bg);
        root.setClickable(true);
        root.setFocusable(false);
        root.setTranslationZ(64 * d);
        root.setOutlineProvider(null);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(c, "弾幕の設定", 15, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView reset = action(c, d, "リセット");
        reset.setOnClickListener(v -> { for (IntSetting s : RESETTABLE) s.save(s.defaultValue); dismiss(); show(); });
        TextView close = action(c, d, "✕");
        close.setOnClickListener(v -> dismiss());
        header.addView(reset); header.addView(close);
        root.addView(header);

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
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

        ScrollView scroll = new ScrollView(c);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        host.addView(root, new FrameLayout.LayoutParams(dp(d,280), dp(d,240), Gravity.TOP | Gravity.LEFT));
        container = host; panel = root;
        place();
        anchor.addOnLayoutChangeListener(relayout);
        host.addOnLayoutChangeListener(relayout);
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
        int width = Math.min(dp(d,320), Math.max(dp(d,200), w - m * 2));
        int height = Math.max(dp(d,160), h - m * 2);
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
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
        TextView value = text(c, "", 13, true);
        row.addView(text(c, title, 13, false), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(value);
        SeekBar bar = new SeekBar(c); bar.setMax(max);
        int current = setting.get();
        bar.setProgress(Math.max(0, Math.min(max, toProgress.apply(current))));
        value.setText(label.apply(current));
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
        rowLp.topMargin = dp(d, 8);
        list.addView(row, rowLp);
        list.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private static TextView text(Context c, String s, int sp, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextColor(0xFFEFEFF1);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private static TextView action(Context c, float d, String s) {
        TextView t = text(c, s, 14, true); t.setTextColor(0xFFBF94FF);
        t.setPadding(dp(d,10), dp(d,8), dp(d,10), dp(d,8)); return t;
    }
    private static int dp(float density, int v) { return Math.round(v * density); }

    static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        Icon(int size) { this.size = size; paint.setColor(Color.WHITE); paint.setStrokeCap(Paint.Cap.ROUND); }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float left = b.left + (b.width() - s) / 2f;
            float top = b.top + (b.height() - s) / 2f;
            float[] ys = {0.25f, 0.5f, 0.75f}; float[] knobs = {0.35f, 0.68f, 0.48f};
            for (int i = 0; i < 3; i++) {
                float y = top + s * ys[i];
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(s * 0.08f);
                canvas.drawLine(left + s * 0.14f, y, left + s * 0.86f, y, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(left + s * knobs[i], y, s * 0.1f, paint);
            }
        }
        @Override public void setTintList(ColorStateList tint) { if (tint != null) paint.setColor(tint.getDefaultColor()); invalidateSelf(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}