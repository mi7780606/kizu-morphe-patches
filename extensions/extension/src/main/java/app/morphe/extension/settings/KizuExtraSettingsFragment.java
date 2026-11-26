package app.morphe.extension.settings;

import android.app.Activity;
import android.app.FragmentManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;
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
            Preference link = TwitchUi.createSectionPreference(screen.getContext());
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
                    "名前をタップするとその人の過去コメント（新しい順・検索可）を表示します。画面に出なかったコメントも記録します。オフでTwitch本来のユーザーカードが開きます。");
            addSwitch(screen, KizuSettings.HIDE_HYPE_TRAIN.key, KizuSettings.HIDE_HYPE_TRAIN.defaultValue,
                    "ハイプトレインを非表示",
                    "ハイプトレインのカードを作らせずに消します。オフにしたときは、次のハイプトレインから表示されます。");
            addSwitch(screen, KizuSettings.HIDE_CLAIM_POPUP.key, KizuSettings.HIDE_CLAIM_POPUP.defaultValue,
                    "ポイント獲得の通知を非表示",
                    "チャンネルポイント自動獲得時のメッセージを出しません。自動獲得は続きます。");
            addSwitch(screen, KizuSettings.DANMAKU_QUICK_SETTINGS.key, KizuSettings.DANMAKU_QUICK_SETTINGS.defaultValue,
                    "弾幕の設定ボタン",
                    "プレイヤー右上に弾幕のクイック設定ボタンを表示します。");
            addSwitch(screen, KizuSettings.MULTI_STREAM_BUTTON.key, KizuSettings.MULTI_STREAM_BUTTON.defaultValue,
                    "マルチ視聴ボタン",
                    "プレイヤー右上に複数配信の同時視聴（サブ小窓 / 最大4枠マルチビュー）ボタンを表示します。");
            addMultiStream(screen);

            addSwitch(screen, KizuSettings.HIDE_CHAT_MENU_BUTTON.key, KizuSettings.HIDE_CHAT_MENU_BUTTON.defaultValue,
                    "チャット欄の三点リーダーを非表示",
                    "チャット入力欄の右にあるチャット設定ボタン（︙）を隠します。");
            addSwitch(screen, KizuSettings.RECOMMENDED_CHANNELS.key, KizuSettings.RECOMMENDED_CHANNELS.defaultValue,
                    "あなたにおすすめのチャンネル",
                    "フォロー中で「ライブ」を選んだとき、一番上におすすめの配信を4件表示します。");
            addNgWords(screen);
            addNumber(screen, "弾幕で長文を流さない", "この文字数より長いコメントは弾幕に流しません（チャット欄には残ります）。スタンプは1個1文字、0で制限なし（0〜200）",
                    KizuSettings.DANMAKU_MAX_LENGTH.key, KizuSettings.DANMAKU_MAX_LENGTH.defaultValue, 0, 200);
            addNumber(screen, "混雑とみなす流速", "毎秒このコメント数以上で、スクロール効果を止めて弾幕を半分程度に間引きます（5〜500）",
                    KizuSettings.FLOW_BUSY_RATE.key, KizuSettings.FLOW_BUSY_RATE.defaultValue, 5, 500);
            addNumber(screen, "激流とみなす流速", "毎秒このコメント数以上で、1件ずつ流すのを止めて弾幕を1秒10件までにします（10〜1000）",
                    KizuSettings.FLOW_FLOOD_RATE.key, KizuSettings.FLOW_FLOOD_RATE.defaultValue, 10, 1000);
            addCrashLog(screen);
            addManualBet(screen);
            addSwitch(screen, KizuSettings.PREDICTION_AUTO.key, KizuSettings.PREDICTION_AUTO.defaultValue,
                    "予想に自動で参加",
                    "予想が始まったら、指定の番号とポイントで自動で賭けます。チャンネルポイント画面の「予想」ボタンからも設定できます。");
            addNumber(screen, "賭ける番号", "1が一番上の選択肢（1〜10）",
                    KizuSettings.PREDICTION_OUTCOME.key, KizuSettings.PREDICTION_OUTCOME.defaultValue, 1, 10);
            addNumber(screen, "賭けるポイント", "1回の予想で賭けるポイント",
                    KizuSettings.PREDICTION_POINTS.key, KizuSettings.PREDICTION_POINTS.defaultValue, 1, 250000);
            Preference note = new Preference(SettingsUi.preferenceContext(screen.getContext()));
            note.setSummary("切り替えはすぐに反映されます。");
            note.setSelectable(false);
            screen.addPreference(note);
        } catch (Throwable t) {
            Log.e(TAG, "build failed", t);
        }
    }

    private void addMultiStream(PreferenceScreen screen) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle("マルチ視聴（同時視聴）を開く");
        p.setSummary("最大4つの配信を同時に視聴できるマルチビュー画面を開きます。");
        p.setOnPreferenceClickListener(clicked -> {
            android.app.Activity a = getActivity();
            if (a != null) app.morphe.extension.multistream.MultiStreamManager.openMultiViewDirect(a);
            return true;
        });
        screen.addPreference(p);
    }

    private void addManualBet(PreferenceScreen screen) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle("予想に手動で賭ける");
        p.setSummary("配信を見ている間に開くと、今の予想に賭けられます。初回はKizu用ログイン（コードを twitch.tv/activate で入力）が出ます。");
        p.setOnPreferenceClickListener(clicked -> {
            android.app.Activity a = getActivity();
            if (a != null) app.morphe.extension.channelpoints.TvBet.open(a);
            return true;
        });
        screen.addPreference(p);
    }

    private void addNgWords(PreferenceScreen screen) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle("NGワード");
        p.setSummary(ngSummary());
        p.setOnPreferenceClickListener(clicked -> {
            Activity a = getActivity();
            if (a == null) return true;
            EditText text = TwitchUi.createPillInput(a, "1行に1つ。本文と完全一致したコメントだけ消えます");
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setSingleLine(false);
            text.setMinLines(4);
            text.setText(KizuSettings.NG_WORDS.get());
            FrameLayout box = new FrameLayout(a);
            box.setPadding(0, SettingsUi.dp(a, 4), 0, 0);
            box.addView(text);
            TwitchUi.showThemedDialog(
                    a,
                    "NGワード",
                    box,
                    a.getString(android.R.string.ok),
                    () -> {
                        KizuSettings.NG_WORDS.save(text.getText().toString());
                        p.setSummary(ngSummary());
                    },
                    null,
                    null,
                    a.getString(android.R.string.cancel));
            return true;
        });
        screen.addPreference(p);
    }

    private void addCrashLog(PreferenceScreen screen) {
        Preference p = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        p.setTitle("最後に落ちたときの記録");
        app.morphe.extension.CrashLog.ensureFile(screen.getContext());
        String log = app.morphe.extension.CrashLog.read();
        p.setSummary(log.isEmpty() ? "記録なし" : "記録あり（タップで表示・コピー）");
        p.setOnPreferenceClickListener(clicked -> {
            Activity a = getActivity();
            if (a == null) return true;
            String text = app.morphe.extension.CrashLog.read();
            TextView tv = new TextView(a);
            tv.setText(text.isEmpty() ? "記録はありません" : text);
            tv.setTextIsSelectable(true);
            tv.setTextColor(text.isEmpty() ? SettingsUi.secondaryTextColor(a) : SettingsUi.primaryTextColor(a));
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            int pad = SettingsUi.dp(a, 12);
            tv.setPadding(pad, pad, pad, pad);
            int maxHeight = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.52f);
            ScrollView sv = new ScrollView(a) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int bounded = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST);
                    super.onMeasure(widthMeasureSpec, bounded);
                }
            };
            sv.setBackground(TwitchUi.roundedRect(
                    SettingsUi.inputBackgroundColor(a),
                    SettingsUi.dp(a, 8),
                    Math.max(1, SettingsUi.dp(a, 0.75f)),
                    SettingsUi.borderColor(a)));
            sv.addView(tv);
            TwitchUi.showThemedDialog(
                    a,
                    "最後に落ちたときの記録",
                    sv,
                    text.isEmpty() ? null : "コピー",
                    text.isEmpty() ? null : () -> {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Kizu crash log", text));
                                Toast.makeText(a, "コピーしました", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) { Log.e(TAG, "copy failed", t); }
                    },
                    text.isEmpty() ? null : "消去",
                    text.isEmpty() ? null : () -> {
                        app.morphe.extension.CrashLog.clear();
                        p.setSummary("記録なし");
                    },
                    "閉じる");
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
            EditText text = TwitchUi.createPillInput(a, hint);
            text.setInputType(InputType.TYPE_CLASS_NUMBER);
            text.setSingleLine(true);
            text.setText(String.valueOf(sp.getInt(key, def)));
            text.setSelection(text.length());
            FrameLayout box = new FrameLayout(a);
            box.setPadding(0, SettingsUi.dp(a, 4), 0, 0);
            box.addView(text);
            TwitchUi.showThemedDialog(
                    a,
                    title,
                    box,
                    a.getString(android.R.string.ok),
                    () -> {
                        int v;
                        try { v = Integer.parseInt(text.getText().toString().trim()); } catch (Throwable t) { v = def; }
                        v = Math.max(min, Math.min(max, v));
                        sp.edit().putInt(key, v).apply();
                        p.setSummary(String.valueOf(v));
                    },
                    null,
                    null,
                    a.getString(android.R.string.cancel));
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