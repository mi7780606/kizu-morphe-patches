package app.morphe.extension.appearance;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.Utils;
import app.morphe.extension.channelpoints.TvBet;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** 三点リーダーの非表示と、チャンネルポイント画面への「予想の自動参加」ボタン追加 */
public final class KizuUiTweaks {
    private static final String TAG = "KizuUiTweaks";
    private static final String BUTTON_TAG = "kizu_prediction_button";

    private KizuUiTweaks() {}

    public static void bindChatMenuButton(View chatMenuButton) {
        if (chatMenuButton == null) return;
        HiddenView.attach(chatMenuButton, view -> KizuSettings.HIDE_CHAT_MENU_BUTTON.get(), true);
    }

    public static void bindCommunityPoints(View outer) {
        if (outer == null) return;
        Context c = outer.getContext();
        int headerId = Utils.getResourceId(c, "header_container", "id");
        if (headerId == 0) return;
        View h = outer.findViewById(headerId);
        if (!(h instanceof LinearLayout header)) return;
        int moreId = Utils.getResourceId(c, "more_button", "id");
        View more = moreId != 0 ? header.findViewById(moreId) : null;
        addPredictionButtonTo(header, more);
    }

    /** Lightweight one-shot fallback at Activity start in case views already existed before ViewDelegate hooks. */
    public static void onActivityStarted(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) return;
            WeakReference<View> ref = new WeakReference<>(decor);
            decor.post(() -> {
                View root = ref.get();
                if (root == null) return;
                try {
                    Context c = root.getContext();
                    int chatMenuId = Utils.getResourceId(c, "view_chat_settings", "id");
                    if (chatMenuId != 0) {
                        View chatMenu = root.findViewById(chatMenuId);
                        if (chatMenu != null) bindChatMenuButton(chatMenu);
                    }
                    int outerId = Utils.getResourceId(c, "community_points_outer_container", "id");
                    if (outerId != 0) {
                        View outer = root.findViewById(outerId);
                        if (outer != null) bindCommunityPoints(outer);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "fallback attach failed", t);
        }
    }

    /** チャンネルポイント画面の見出しに「予想」ボタンを置く（予想がなくても常に出す） */
    static void addPredictionButtonTo(LinearLayout header, View before) {
        if (header == null || header.findViewWithTag(BUTTON_TAG) != null) return;
        Context c = header.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        TextView b = new TextView(c);
        b.setTag(BUTTON_TAG);
        b.setText("予想");
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(SettingsUi.accentColor(c));
        b.setGravity(Gravity.CENTER);
        b.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
        b.setMinHeight(Math.round(36 * d));
        b.setBackground(TwitchUi.ripple(c, null, 8f * d));
        b.setContentDescription("予想の自動参加の設定");
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> showDialog(v.getContext()));
        int i = before == null ? -1 : header.indexOfChild(before);
        int index = i >= 0 ? i : header.getChildCount();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        header.addView(b, index, lp);
    }

    private static void showDialog(Context c) {
        try {
            Dialog dlg = TwitchUi.createBottomSheet(c);
            int pad = TwitchUi.dp(c, 16f);

            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, 0, pad, pad);
            root.addView(TwitchUi.dragHandle(c));

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, TwitchUi.dp(c, 4f), 0, TwitchUi.dp(c, 12f));

            int iconRes = Utils.getResourceId(c, "ic_prediction_event", "drawable");
            if (iconRes != 0) {
                ImageView icon = new ImageView(c);
                icon.setImageResource(iconRes);
                icon.setColorFilter(SettingsUi.accentColor(c));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        TwitchUi.dp(c, 20f), TwitchUi.dp(c, 20f));
                ilp.rightMargin = TwitchUi.dp(c, 8f);
                header.addView(icon, ilp);
            }

            TextView title = new TextView(c);
            title.setText("予想");
            title.setTextColor(SettingsUi.primaryTextColor(c));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            header.addView(TwitchUi.createCloseButton(c, dlg::dismiss));
            root.addView(header);

            TextView betNow = TwitchUi.createPrimaryButton(c, "今すぐ賭ける（手動）");
            betNow.setOnClickListener(v -> {
                try { dlg.dismiss(); } catch (Throwable ignored) {}
                TvBet.open(c);
            });
            LinearLayout.LayoutParams betLp = new LinearLayout.LayoutParams(-1, -2);
            betLp.bottomMargin = TwitchUi.dp(c, 14f);
            root.addView(betNow, betLp);

            root.addView(TwitchUi.divider(c));

            LinearLayout autoRow = new LinearLayout(c);
            autoRow.setOrientation(LinearLayout.HORIZONTAL);
            autoRow.setGravity(Gravity.CENTER_VERTICAL);
            autoRow.setPadding(0, TwitchUi.dp(c, 12f), 0, TwitchUi.dp(c, 4f));
            TextView autoLabel = new TextView(c);
            autoLabel.setText("自動で参加する");
            autoLabel.setTextColor(SettingsUi.primaryTextColor(c));
            autoLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            autoLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0, -2, 1f));

            Switch auto = new Switch(c);
            auto.setChecked(KizuSettings.PREDICTION_AUTO.get());
            TwitchUi.styleSwitch(c, auto);
            autoRow.addView(auto);
            autoRow.setOnClickListener(v -> auto.toggle());
            root.addView(autoRow);

            root.addView(label(c, "賭ける番号（1が一番上の選択肢）"));
            EditText outcome = number(c, String.valueOf(KizuSettings.PREDICTION_OUTCOME.get()));
            root.addView(outcome);

            root.addView(label(c, "賭けるポイント"));
            EditText points = number(c, String.valueOf(KizuSettings.PREDICTION_POINTS.get()));
            root.addView(points);

            TextView note = new TextView(c);
            note.setText("予想が始まると、10秒以内に自動で賭けます。番号が選択肢の数より大きいときは一番下に賭けます。");
            note.setTextColor(SettingsUi.secondaryTextColor(c));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            note.setPadding(0, TwitchUi.dp(c, 10f), 0, 0);
            root.addView(note);

            LinearLayout actions = new LinearLayout(c);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, TwitchUi.dp(c, 16f), 0, 0);
            TextView cancel = TwitchUi.createSecondaryButton(c, "キャンセル");
            cancel.setOnClickListener(v -> dlg.dismiss());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.rightMargin = TwitchUi.dp(c, 8f);
            actions.addView(cancel, clp);

            TextView save = TwitchUi.createPrimaryButton(c, "保存");
            save.setOnClickListener(v -> {
                KizuSettings.PREDICTION_AUTO.save(auto.isChecked());
                KizuSettings.PREDICTION_OUTCOME.save(clamp(outcome, 1, 10, 1));
                KizuSettings.PREDICTION_POINTS.save(clamp(points, 1, 250000, 100));
                dlg.dismiss();
            });
            actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(actions);

            TwitchUi.prepareBottomSheet(dlg, c, root);
            dlg.setContentView(root);
            dlg.show();
        } catch (Throwable t) {
            Log.e(TAG, "dialog failed", t);
        }
    }

    private static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(SettingsUi.secondaryTextColor(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setPadding(0, TwitchUi.dp(c, 10f), 0, TwitchUi.dp(c, 4f));
        return t;
    }

    private static EditText number(Context c, String value) {
        EditText e = TwitchUi.createPillInput(c, "");
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setSingleLine(true);
        e.setSelection(e.length());
        return e;
    }

    private static int clamp(EditText e, int min, int max, int fallback) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable t) {
            return fallback;
        }
    }
}
