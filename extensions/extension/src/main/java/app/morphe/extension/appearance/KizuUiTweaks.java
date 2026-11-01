package app.morphe.extension.appearance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** 三点リーダーの非表示と、チャンネルポイント画面への「予想の自動参加」ボタン追加 */
public final class KizuUiTweaks {
    private static final String TAG = "KizuUiTweaks";
    private static final String BUTTON_TAG = "kizu_prediction_button";
    private static final long DELAY_MS = 250;

    private static final Map<View, Boolean> ROOTS = new WeakHashMap<>();
    private static int chatMenuId = -1, outerId, headerId, moreId;

    private KizuUiTweaks() {}

    public static void onActivityStarted(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || ROOTS.containsKey(decor)) return;
            ROOTS.put(decor, Boolean.TRUE);
            if (chatMenuId == -1) {
                String pkg = activity.getPackageName();
                chatMenuId = activity.getResources().getIdentifier("view_chat_settings", "id", pkg);
                outerId = activity.getResources().getIdentifier("community_points_outer_container", "id", pkg);
                headerId = activity.getResources().getIdentifier("header_container", "id", pkg);
                moreId = activity.getResources().getIdentifier("more_button", "id", pkg);
            }
            Watcher w = new Watcher(decor);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(w);
            w.schedule();
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private static final class Watcher implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        final WeakReference<View> ref;
        boolean posted;

        Watcher(View root) { ref = new WeakReference<>(root); }

        @Override public void onGlobalLayout() {
            View root = ref.get();
            if (root != null) hideChatMenu(root); // ちらつき防止にすぐ隠す
            schedule();
        }

        void schedule() {
            View root = ref.get();
            if (root == null || posted) return;
            posted = true;
            root.postDelayed(this, DELAY_MS);
        }

        @Override public void run() {
            posted = false;
            View root = ref.get();
            if (root == null) return;
            try {
                hideChatMenu(root);
                addPredictionButton(root);
                try { KizuRecommended.update(root); } catch (Throwable t) { Log.e(TAG, "recommended failed", t); }
            } catch (Throwable t) {
                Log.e(TAG, "scan failed", t);
            }
        }
    }

    private static void hideChatMenu(View root) {
        if (chatMenuId == 0) return;
        boolean hide;
        try { hide = KizuSettings.HIDE_CHAT_MENU_BUTTON.get(); } catch (Throwable t) { hide = true; }
        if (!hide) return;
        View v = root.findViewById(chatMenuId);
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }

    private static void addPredictionButton(View root) {
        if (outerId == 0 || headerId == 0) return;
        View outer = root.findViewById(outerId);
        if (outer == null) return;
        View h = outer.findViewById(headerId);
        if (!(h instanceof LinearLayout)) return;
        LinearLayout header = (LinearLayout) h;
        View more = moreId != 0 ? header.findViewById(moreId) : null;
        addPredictionButtonTo(header, more);
    }

    /** チャンネルポイント画面の見出しに「予想」ボタンを置く（予想がなくても常に出す） */
    static void addPredictionButtonTo(LinearLayout header, View before) {
        if (header.findViewWithTag(BUTTON_TAG) != null) return;
        Context c = header.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        TextView b = new TextView(c);
        b.setTag(BUTTON_TAG);
        b.setText("予想");
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(0xFFBF94FF);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (8 * d), 0, (int) (8 * d), 0);
        b.setContentDescription("予想の自動参加の設定");
        b.setOnClickListener(v -> showDialog(v.getContext()));
        int i = before == null ? -1 : header.indexOfChild(before);
        int index = i >= 0 ? i : header.getChildCount();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setMinHeight((int) (40 * d));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.addView(b, index, lp);
    }
    private static void showDialog(Context c) {
        try {
            float d = c.getResources().getDisplayMetrics().density;
            int pad = (int) (20 * d);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, (int) (8 * d), pad, 0);

            Switch auto = new Switch(c);
            auto.setText("自動で参加する");
            auto.setTextColor(Color.WHITE);
            auto.setChecked(KizuSettings.PREDICTION_AUTO.get());
            box.addView(auto);

            box.addView(label(c, d, "賭ける番号（1が一番上の選択肢）"));
            EditText outcome = number(c, String.valueOf(KizuSettings.PREDICTION_OUTCOME.get()));
            box.addView(outcome);

            box.addView(label(c, d, "賭けるポイント"));
            EditText points = number(c, String.valueOf(KizuSettings.PREDICTION_POINTS.get()));
            box.addView(points);

            TextView note = label(c, d, "予想が始まると、10秒以内に自動で賭けます。番号が選択肢の数より大きいときは一番下に賭けます。");
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            box.addView(note);

            new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("予想の自動参加")
                    .setView(box)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("保存", (dialog, which) -> {
                        KizuSettings.PREDICTION_AUTO.save(auto.isChecked());
                        KizuSettings.PREDICTION_OUTCOME.save(clamp(outcome, 1, 10, 1));
                        KizuSettings.PREDICTION_POINTS.save(clamp(points, 1, 250000, 100));
                    })
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "dialog failed", t);
        }
    }

    private static TextView label(Context c, float d, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xFFDDDDDD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, (int) (12 * d), 0, 0);
        return t;
    }

    private static EditText number(Context c, String value) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
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
