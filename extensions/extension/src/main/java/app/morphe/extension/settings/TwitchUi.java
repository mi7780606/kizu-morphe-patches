package app.morphe.extension.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.WeakHashMap;

import app.morphe.extension.twitch.emotes.NativeEmotePreview;
import io.github.bakwudo.uyu.extension.Utils;

/**
 * Shared Twitch-native UI components for dialogs, bottom sheets, chips, inputs, buttons,
 * tinted controls, icons, and snackbars across all extension modules.
 */
public final class TwitchUi {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();

    private TwitchUi() {}

    public static int dp(Context context, float value) {
        return SettingsUi.dp(context, value);
    }

    /**
     * Creates a Twitch-native BottomSheetDialog via the patched bridge when available,
     * falling back to a matching rounded bottom-docked Dialog otherwise.
     */
    public static Dialog createBottomSheet(Context context) {
        Dialog nativeSheet = NativeEmotePreview.createBottomSheetDialog(context);
        if (nativeSheet != null) {
            return nativeSheet;
        }
        Dialog fallback = new Dialog(context);
        fallback.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return fallback;
    }

    /**
     * Configures a bottom sheet container with Twitch's surface color, rounded top corners,
     * and clean window placement. Call right before or inside show().
     */
    public static void prepareBottomSheet(Dialog dialog, Context context, View sheetRoot) {
        if (dialog == null || context == null || sheetRoot == null) return;
        sheetRoot.setBackground(sheetBackground(context, 16f));

        dialog.setOnShowListener(shown -> {
            Window w = dialog.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int bsId = Utils.getResourceId(context, "design_bottom_sheet", "id");
            View bottomSheet = bsId != 0 ? dialog.findViewById(bsId) : null;
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT);
            } else {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                boolean landscape = dm.widthPixels > dm.heightPixels;
                int width = landscape ? Math.min(dm.widthPixels, dp(context, 480f))
                        : WindowManager.LayoutParams.MATCH_PARENT;
                w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                w.setWindowAnimations(android.R.style.Animation_InputMethod);
            }
        });
    }

    public static GradientDrawable sheetBackground(Context context, float topRadiusDp) {
        float r = dp(context, topRadiusDp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUi.surfaceColor(context));
        bg.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        bg.setStroke(Math.max(1, dp(context, 0.75f)), SettingsUi.borderColor(context));
        return bg;
    }

    public static GradientDrawable roundedRect(int fillColor, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    public static RippleDrawable ripple(Context context, Drawable content, float maskRadiusPx) {
        int color = SettingsUi.rippleColor(context);
        Drawable mask = null;
        if (content == null && maskRadiusPx >= 0f) {
            mask = roundedRect(Color.WHITE, maskRadiusPx, 0, 0);
        }
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    public static Drawable selectableBackground(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    return context.getDrawable(tv.resourceId);
                } catch (Throwable ignored) {}
            }
        }
        return ripple(context, null, 0f);
    }

    public static Drawable selectableBorderlessBackground(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    return context.getDrawable(tv.resourceId);
                } catch (Throwable ignored) {}
            }
        }
        return new RippleDrawable(ColorStateList.valueOf(SettingsUi.rippleColor(context)), null, null);
    }

    public static View dragHandle(Context context) {
        View handle = new View(context);
        handle.setBackground(roundedRect(SettingsUi.dividerColor(context), dp(context, 2f), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 36f), dp(context, 4f));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(context, 8f);
        lp.bottomMargin = dp(context, 6f);
        handle.setLayoutParams(lp);
        return handle;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(SettingsUi.dividerColor(context));
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.75f))));
        return line;
    }

    public static EditText createPillInput(Context context, String hint) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setTextColor(SettingsUi.primaryTextColor(context));
        edit.setHintTextColor(SettingsUi.secondaryTextColor(context));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        edit.setBackground(roundedRect(
                SettingsUi.inputBackgroundColor(context),
                dp(context, 8f),
                Math.max(1, dp(context, 1f)),
                SettingsUi.borderColor(context)));
        int hPad = dp(context, 12f);
        int vPad = dp(context, 10f);
        edit.setPadding(hPad, vPad, hPad, vPad);
        return edit;
    }

    public static TextView createPrimaryButton(Context context, String label) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int hPad = dp(context, 16f);
        int vPad = dp(context, 11f);
        btn.setPadding(hPad, vPad, hPad, vPad);
        btn.setBackground(ripple(context,
                roundedRect(SettingsUi.accentStrongColor(context), dp(context, 8f), 0, 0),
                dp(context, 8f)));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    public static TextView createSecondaryButton(Context context, String label) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextColor(SettingsUi.primaryTextColor(context));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int hPad = dp(context, 14f);
        int vPad = dp(context, 10f);
        btn.setPadding(hPad, vPad, hPad, vPad);
        btn.setBackground(ripple(context,
                roundedRect(SettingsUi.alternateBackgroundColor(context), dp(context, 8f), 0, 0),
                dp(context, 8f)));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    public static TextView createGhostButton(Context context, String label, int textColor) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int hPad = dp(context, 12f);
        int vPad = dp(context, 8f);
        btn.setPadding(hPad, vPad, hPad, vPad);
        btn.setBackground(ripple(context, null, dp(context, 8f)));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    public static ImageView createCloseButton(Context context, Runnable onClick) {
        ImageView close = new ImageView(context);
        int box = dp(context, 36f);
        int icon = dp(context, 18f);
        close.setLayoutParams(new LinearLayout.LayoutParams(box, box));
        int pad = (box - icon) / 2;
        close.setPadding(pad, pad, pad, pad);
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setImageDrawable(new CloseIconDrawable(SettingsUi.secondaryTextColor(context), icon));
        close.setBackground(selectableBorderlessBackground(context));
        close.setContentDescription("閉じる");
        close.setClickable(true);
        close.setFocusable(true);
        if (onClick != null) close.setOnClickListener(v -> onClick.run());
        return close;
    }

    public static ImageView createChevronView(Context context) {
        ImageView chevron = new ImageView(context);
        int size = dp(context, 18f);
        chevron.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chevron.setImageDrawable(new ChevronDrawable(SettingsUi.secondaryTextColor(context), size));
        return chevron;
    }

    @SuppressWarnings("deprecation")
    public static Preference createSectionPreference(Context context) {
        Context prefContext = SettingsUi.preferenceContext(context);
        return new Preference(prefContext) {
            @Override
            protected View onCreateView(ViewGroup parent) {
                View row = super.onCreateView(parent);
                ViewGroup widgetFrame = row.findViewById(android.R.id.widget_frame);
                if (widgetFrame != null) {
                    widgetFrame.removeAllViews();
                    widgetFrame.addView(createChevronView(getContext()));
                    widgetFrame.setVisibility(View.VISIBLE);
                }
                return row;
            }
        };
    }

    public static void styleSeekBar(Context context, SeekBar bar) {
        if (bar == null) return;
        int accent = SettingsUi.accentColor(context);
        int accentStrong = SettingsUi.accentStrongColor(context);
        int trackBg = SettingsUi.borderColor(context);
        bar.setProgressTintList(ColorStateList.valueOf(accentStrong));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(trackBg));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setSplitTrack(false);
    }

    public static void styleSwitch(Context context, Switch sw) {
        if (sw == null) return;
        int accent = SettingsUi.accentColor(context);
        int accentStrong = SettingsUi.accentStrongColor(context);
        boolean dark = SettingsUi.isDark(context);
        int offThumb = dark ? 0xFFB8B8C2 : 0xFFECECF0;
        int offTrack = dark ? 0xFF3A3A44 : 0xFFC8C8D2;
        int onTrack = Color.argb(0x78, Color.red(accentStrong), Color.green(accentStrong), Color.blue(accentStrong));
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[]{accent, offThumb}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{onTrack, offTrack}));
    }

    /**
     * Shows a Twitch-styled rounded card Dialog (Light/Dark aware, ripple action buttons).
     * Positive/neutral clicks dismiss automatically unless handleDismiss is false.
     */
    public static Dialog showThemedDialog(
            Context context,
            String title,
            View customView,
            String positiveText,
            Runnable onPositive,
            String neutralText,
            Runnable onNeutral,
            String negativeText
    ) {
        Dialog dlg = new Dialog(context);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 18f);
        card.setPadding(pad, dp(context, 16f), pad, dp(context, 12f));
        card.setBackground(roundedRect(
                SettingsUi.surfaceColor(context),
                dp(context, 16f),
                Math.max(1, dp(context, 0.75f)),
                SettingsUi.borderColor(context)));

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(context);
            tv.setText(title);
            tv.setTextColor(SettingsUi.primaryTextColor(context));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.bottomMargin = dp(context, 10f);
            card.addView(tv, tp);
        }

        if (customView != null) {
            card.addView(customView, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(context, 14f);

        if (neutralText != null) {
            TextView nb = createGhostButton(context, neutralText, SettingsUi.secondaryTextColor(context));
            nb.setOnClickListener(v -> {
                dlg.dismiss();
                if (onNeutral != null) onNeutral.run();
            });
            actions.addView(nb);
        }
        View spacer = new View(context);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        if (negativeText != null) {
            TextView neg = createGhostButton(context, negativeText, SettingsUi.secondaryTextColor(context));
            neg.setOnClickListener(v -> dlg.dismiss());
            actions.addView(neg);
        }
        if (positiveText != null) {
            TextView pos = createGhostButton(context, positiveText, SettingsUi.accentColor(context));
            pos.setOnClickListener(v -> {
                dlg.dismiss();
                if (onPositive != null) onPositive.run();
            });
            actions.addView(pos);
        }
        card.addView(actions, ap);

        dlg.setContentView(card);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int maxWidth = Math.min(dm.widthPixels - dp(context, 32f), dp(context, 400f));
            w.setLayout(maxWidth, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
        return dlg;
    }

    public static void showAlert(Context context, String title, String message) {
        TextView body = new TextView(context);
        body.setText(message);
        body.setTextColor(SettingsUi.secondaryTextColor(context));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setLineSpacing(dp(context, 2f), 1f);
        showThemedDialog(context, title, body, "OK", null, null, null, null);
    }

    public static void showSnackbar(
            Activity activity,
            String message,
            WeakHashMap<Activity, View> activeMap,
            long durationMs
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || message == null) return;
        MAIN.post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                View decorView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (!(decorView instanceof FrameLayout decor)) return;

                if (activeMap != null) {
                    View old;
                    synchronized (activeMap) {
                        old = activeMap.get(activity);
                    }
                    if (old != null) {
                        try { decor.removeView(old); } catch (Throwable ignored) {}
                    }
                }

                LinearLayout pill = new LinearLayout(activity);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setBackground(roundedRect(
                        SettingsUi.surfaceColor(activity),
                        dp(activity, 20f),
                        Math.max(1, dp(activity, 0.75f)),
                        SettingsUi.borderColor(activity)));
                pill.setElevation(dp(activity, 6f));
                pill.setPadding(dp(activity, 14f), dp(activity, 9f), dp(activity, 16f), dp(activity, 9f));

                View dot = new View(activity);
                dot.setBackground(roundedRect(SettingsUi.accentColor(activity), dp(activity, 4f), 0, 0));
                LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(dp(activity, 6f), dp(activity, 6f));
                dpLp.rightMargin = dp(activity, 8f);
                pill.addView(dot, dpLp);

                TextView tv = new TextView(activity);
                tv.setText(message);
                tv.setTextColor(SettingsUi.primaryTextColor(activity));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                pill.addView(tv);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(activity, 72f);
                lp.leftMargin = dp(activity, 16f);
                lp.rightMargin = dp(activity, 16f);

                pill.setAlpha(0f);
                pill.setTranslationY(dp(activity, 12f));
                decor.addView(pill, lp);
                if (activeMap != null) {
                    synchronized (activeMap) {
                        activeMap.put(activity, pill);
                    }
                }
                pill.animate().alpha(1f).translationY(0f).setDuration(180L).setInterpolator(DECEL).start();

                MAIN.postDelayed(() -> {
                    try {
                        pill.animate().alpha(0f).translationY(dp(activity, 8f)).setDuration(160L)
                                .withEndAction(() -> {
                                    try {
                                        if (activeMap != null) {
                                            synchronized (activeMap) {
                                                if (activeMap.get(activity) == pill) activeMap.remove(activity);
                                            }
                                        }
                                        decor.removeView(pill);
                                    } catch (Throwable ignored) {}
                                }).start();
                    } catch (Throwable ignored) {}
                }, durationMs);
            } catch (Throwable ignored) {}
        });
    }

    private static final class CloseIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        CloseIconDrawable(int color, int size) {
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) return;
            paint.setStrokeWidth(Math.max(1.5f, s * 0.12f));
            float inset = s * 0.22f;
            float l = b.exactCenterX() - s / 2f + inset;
            float r = b.exactCenterX() + s / 2f - inset;
            float t = b.exactCenterY() - s / 2f + inset;
            float bt = b.exactCenterY() + s / 2f - inset;
            canvas.drawLine(l, t, r, bt, paint);
            canvas.drawLine(r, t, l, bt, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    private static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int size;

        ChevronDrawable(int color, int size) {
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) return;
            paint.setStrokeWidth(Math.max(1.5f, s * 0.11f));
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float dx = s * 0.16f;
            float dy = s * 0.28f;
            path.reset();
            path.moveTo(cx - dx * 0.6f, cy - dy);
            path.lineTo(cx + dx, cy);
            path.lineTo(cx - dx * 0.6f, cy + dy);
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
