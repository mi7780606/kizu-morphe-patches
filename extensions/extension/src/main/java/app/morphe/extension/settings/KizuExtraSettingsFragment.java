package app.morphe.extension.settings;

import android.app.Activity;
import android.app.FragmentManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.View;
import android.widget.TextView;

import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/** Kizu設定の一覧に「追加機能」を出し、自分で追加した機能のオン・オフ画面を開く */
@SuppressWarnings("deprecation")
public class KizuExtraSettingsFragment extends PreferenceFragment {
    private static final String TAG = "KizuExtraSettings";
    private static final String FRAGMENT_TAG = "kizu_extra_settings";
    private static final String TITLE = "追加機能";

    private CharSequence previousTitle;

    public KizuExtraSettingsFragment() {}

    static void addLink(PreferenceFragment owner, PreferenceScreen screen) {
        try {
            Preference link = new Preference(SettingsUi.preferenceContext(screen.getContext()));
            link.setTitle(TITLE);
            link.setSummary("自分で追加した機能のオン・オフ");
            link.setOnPreferenceClickListener(clicked -> {
                Activity activity = owner.getActivity();
                if (activity != null) show(activity);
                return true;
            });
            screen.addPreference(link);
        } catch (Throwable t) {
            Log.e(TAG, "add link failed", t);
        }
    }

    private static void show(Activity activity) {
        try {
            FragmentManager fm = activity.getFragmentManager();
            if (fm.findFragmentByTag(FRAGMENT_TAG) != null) return;
            int container = activity.getResources().getIdentifier(
                    "fragment_container", "id", activity.getPackageName());
            if (container == 0 || activity.findViewById(container) == null) {
                Log.i(TAG, "fragment_container not found");
                return;
            }
            fm.beginTransaction()
                    .add(container, new KizuExtraSettingsFragment(), FRAGMENT_TAG)
                    .addToBackStack(FRAGMENT_TAG)
                    .commit();
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getPreferenceManager().setSharedPreferencesName(Setting.PREFERENCES_NAME);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);

            addSwitch(screen, KizuSettings.SMOOTH_CHAT.key, KizuSettings.SMOOTH_CHAT.defaultValue,
                    "コメントを1件ずつ流す",
                    "まとめて届いたコメントを1件ずつ表示します。オフでTwitch本来の表示に戻ります。");
            addSwitch(screen, KizuSettings.SMOOTH_SCROLL.key, KizuSettings.SMOOTH_SCROLL.defaultValue,
                    "スクロールを滑らかにする",
                    "新しいコメントが来たときのチャット欄の跳ねを滑らかにします。");
            addSwitch(screen, KizuSettings.CHAT_HISTORY.key, KizuSettings.CHAT_HISTORY.defaultValue,
                    "名前タップで過去コメント",
                    "名前をタップするとその人の過去コメントを表示します。オフでTwitch本来のユーザーカードが開きます。");
            addSwitch(screen, KizuSettings.HIDE_HYPE_TRAIN.key, KizuSettings.HIDE_HYPE_TRAIN.defaultValue,
                    "ハイプトレインを非表示",
                    "ハイプトレインのカードを消します。オフにしたときは、配信を開き直すと表示されます。");
            addSwitch(screen, KizuSettings.HIDE_CLAIM_POPUP.key, KizuSettings.HIDE_CLAIM_POPUP.defaultValue,
                    "ポイント獲得の通知を非表示",
                    "チャンネルポイント自動獲得時のメッセージを出しません。自動獲得は続きます。");
            addSwitch(screen, KizuSettings.DANMAKU_QUICK_SETTINGS.key, KizuSettings.DANMAKU_QUICK_SETTINGS.defaultValue,
                    "弾幕の設定ボタン",
                    "プレイヤー右上に弾幕のクイック設定ボタンを表示します。");

            addSwitch(screen, KizuSettings.HIDE_CHAT_MENU_BUTTON.key, KizuSettings.HIDE_CHAT_MENU_BUTTON.defaultValue,
                    "チャット欄の三点リーダーを非表示",
                    "チャット入力欄の右にあるチャット設定ボタン（︙）を隠します。");
            addNgWords(screen);
            addSwitch(screen, KizuSettings.PREDICTION_AUTO.key, KizuSettings.PREDICTION_AUTO.defaultValue,
                    "予想に自動で参加",
                    "予想が始まったら、指定の番号とポイントで自動で賭けます。チャンネルポイント画面の「予想」ボタンからも設定できます。");
            addNumber(screen, "賭ける番号", "1が一番上の選択肢（1〜10）",
                    KizuSettings.PREDICTION_OUTCOME.key, KizuSettings.PREDICTION_OUTCOME.defaultValue, 1, 10);
            addNumber(screen, "賭けるポイント", "1回の予想で賭けるポイント",
                    KizuSettings.PREDICTION_POINTS.key, KizuSettings.PREDICTION_POINTS.defaultValue, 1, 250000);
            Preference note = new Preference(screen.getContext());
            note.setSummary("切り替えはすぐに反映されます。");
            note.setSelectable(false);
            screen.addPreference(note);
        } catch (Throwable t) {
            Log.e(TAG, "build failed", t);
        }
    }

    private void addNgWords(PreferenceScreen screen) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle("NGワード");
        p.setSummary(ngSummary());
        p.setOnPreferenceClickListener(clicked -> {
            Activity a = getActivity();
            if (a == null) return true;
            float d = a.getResources().getDisplayMetrics().density;
            EditText text = new EditText(a);
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setSingleLine(false);
            text.setMinLines(4);
            text.setHint("1行に1つ。本文と完全一致したコメントだけ消えます");
            text.setText(KizuSettings.NG_WORDS.get());
            FrameLayout box = new FrameLayout(a);
            int pad = (int) (20 * d);
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(text);
            new AlertDialog.Builder(a)
                    .setTitle("NGワード")
                    .setView(box)
                    .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                        KizuSettings.NG_WORDS.save(text.getText().toString());
                        p.setSummary(ngSummary());
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(p);
    }

    private static String ngSummary() {
        String raw = KizuSettings.NG_WORDS.get();
        int count = 0;
        if (raw != null) for (String s : raw.split("\n")) if (!s.trim().isEmpty()) count++;
        return count == 0 ? "未設定（本文と完全一致したコメントだけ非表示）" : count + "件登録中（完全一致のみ非表示）";
    }

    private void addNumber(PreferenceScreen screen, String title, String hint, String key,
            int def, int min, int max) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle(title);
        android.content.SharedPreferences sp = getPreferenceManager().getSharedPreferences();
        p.setSummary(String.valueOf(sp.getInt(key, def)));
        p.setOnPreferenceClickListener(clicked -> {
            Activity a = getActivity();
            if (a == null) return true;
            float d = a.getResources().getDisplayMetrics().density;
            EditText text = new EditText(a);
            text.setInputType(InputType.TYPE_CLASS_NUMBER);
            text.setSingleLine(true);
            text.setHint(hint);
            text.setText(String.valueOf(sp.getInt(key, def)));
            FrameLayout box = new FrameLayout(a);
            int pad = (int) (20 * d);
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(text);
            new AlertDialog.Builder(a)
                    .setTitle(title)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                        int v;
                        try { v = Integer.parseInt(text.getText().toString().trim()); } catch (Throwable t) { v = def; }
                        v = Math.max(min, Math.min(max, v));
                        sp.edit().putInt(key, v).apply();
                        p.setSummary(String.valueOf(v));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(p);
    }

    private void addSwitch(PreferenceScreen screen, String key, boolean defaultValue,
            String title, String summary) {
        SwitchPreference p = new SwitchPreference(SettingsUi.preferenceContext(screen.getContext()));
        p.setKey(key);
        p.setDefaultValue(defaultValue);
        p.setTitle(title);
        p.setSummary(summary);
        screen.addPreference(p);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try { SettingsUi.applySettingsView(view); } catch (Throwable t) { Log.e(TAG, "appearance failed", t); }
        view.setClickable(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            View v = getView();
            if (v != null) SettingsUi.applySettingsView(v);
            Activity activity = getActivity();
            TextView title = activity == null ? null : SettingsPatch.findToolbarTitle(activity);
            if (title == null) return;
            if (previousTitle == null) previousTitle = title.getText();
            title.setText(TITLE);
        } catch (Throwable t) { Log.e(TAG, "resume failed", t); }
    }

    @Override
    public void onDestroyView() {
        try {
            Activity activity = getActivity();
            TextView title = activity == null ? null : SettingsPatch.findToolbarTitle(activity);
            if (title != null && previousTitle != null) title.setText(previousTitle);
        } catch (Throwable t) { Log.e(TAG, "restore title failed", t); } finally { super.onDestroyView(); }
    }
}