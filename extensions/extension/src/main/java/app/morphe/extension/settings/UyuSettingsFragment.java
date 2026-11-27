package app.morphe.extension.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;


import io.github.bakwudo.uyu.extension.Utils;
import io.github.bakwudo.uyu.extension.danmaku.DanmakuPreview;

@SuppressWarnings("deprecation")
public class UyuSettingsFragment extends PreferenceFragment {
    static final String SECTION_GENERAL = "general";
    static final String SECTION_APPEARANCE = "appearance";
    static final String SECTION_DANMAKU = "danmaku";
    static final String SECTION_ADS = "ads";
    static final String SECTION_EMOTES = "emotes";
    static final String SECTION_CHAT = "chat";
    static final String SECTION_PRIVACY = "privacy";
    static final String SECTION_HOME = "home";
    static final String SECTION_PLAYER = "player";
    static final String SECTION_WATCHLOG = "watchlog";
    private static final String ARG_SECTION = "section";
    private static final String[] WEIGHT_NAMES = {
            "極細 (Thin)", "特細 (Extra light)", "細字 (Light)", "標準 (Regular)",
            "中太 (Medium)", "やや太字 (Semi bold)", "太字 (Bold)", "特太 (Extra bold)", "極太 (Black)",
    };
    private String section;
    private CharSequence previousTitle;
    private FontPreference fontPreference;

    private static final String[] PROXY_NAMES = {
            "オフ（プロキシを使わない）",
            "Luminous EU（ロシア）",
            "Luminous EU2（ウクライナ）",
            "Luminous EU3（ブルガリア）",
            "Luminous Asia（カザフスタン）",
            "PerfProd EU",
            "PerfProd EU2",
            "PerfProd EU3（ロシア）",
            "PerfProd EU4",
            "PerfProd EU5",
            "PerfProd NA（フェニックス）",
            "PerfProd Asia",
            "PerfProd 南米（ニューヨーク）",
    };

    private static final String[] PROXY_URLS = {
            "",
            "https://eu.luminous.dev/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://eu2.luminous.dev/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://eu3.luminous.dev/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://as.luminous.dev/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-eu.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-eu2.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-eu3.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-eu4.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-eu5.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-na.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-as.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
            "https://lb-sa.cdn-perfprod.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
    };

    static UyuSettingsFragment create(String section) {
        UyuSettingsFragment fragment = new UyuSettingsFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_SECTION, section);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getPreferenceManager().setSharedPreferencesName(Setting.PREFERENCES_NAME);
            Bundle arguments = getArguments();
            section = arguments == null ? null : arguments.getString(ARG_SECTION);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);
            if (section == null) addSectionLinks(screen);
            else if (section.equals(SECTION_GENERAL)) addGeneralSettings(screen);
            else if (section.equals(SECTION_APPEARANCE)) addAppearanceSettings(screen);
            else if (section.equals(SECTION_DANMAKU)) addDanmakuSettings(screen);
            else if (section.equals(SECTION_ADS)) addAdsSettings(screen);
            else if (section.equals(SECTION_EMOTES)) addEmoteSettings(screen);
            else if (section.equals(SECTION_CHAT)) addChatSettings(screen);
            else if (section.equals(SECTION_PRIVACY)) addPrivacySettings(screen);
            else if (section.equals(SECTION_HOME)) addHomeSettings(screen);
            else if (section.equals(SECTION_PLAYER)) addPlayerSettings(screen);
            else if (section.equals(SECTION_WATCHLOG)) addWatchLogSettings(screen);
        } catch (Throwable error) {
            Utils.logError("Failed to build Kizu settings screen (section=" + section + ")", error);
            try {
                Activity activity = getActivity();
                if (activity != null) {
                    PreferenceScreen fallback = getPreferenceManager().createPreferenceScreen(activity);
                    Preference diagnostic = new Preference(activity);
                    diagnostic.setTitle("Kizu設定を読み込めませんでした");
                    diagnostic.setSummary("エラー内容を記録しました。設定画面を開き直してください。");
                    diagnostic.setSelectable(false);
                    fallback.addPreference(diagnostic);
                    setPreferenceScreen(fallback);
                }
            } catch (Throwable fallbackError) {
                Utils.logError("Failed to show Kizu settings fallback", fallbackError);
            }
        }
    }

    private void addSectionLinks(PreferenceScreen screen) {
        KizuExtraSettingsFragment.addLink(this, screen);
        if (PatchStatus.autoClaimChannelPoints()) addSectionLink(screen, SECTION_GENERAL,
                "ホームタブの初期表示、ストーリーズ非表示、チャンネルポイント自動獲得");
        if (PatchStatus.hidePromotions()) addSectionLink(screen, SECTION_APPEARANCE,
                "サブスク・ビッツボタン、ギフトランキング、各種プロモーションの非表示");
        if (PatchStatus.danmakuComments()) addSectionLink(screen, SECTION_DANMAKU,
                "配信映像の上にコメントを流す（弾幕表示）設定");
        if (PatchStatus.blockAds()) addSectionLink(screen, SECTION_ADS,
                "動画・表示広告のブロック、ストリームプロキシ設定");
        addSectionLink(screen, SECTION_EMOTES, "7TV / BTTV / FFZ などの外部スタンプとアニメーション表示");
        addSectionLink(screen, SECTION_CHAT, "削除済みメッセージ、時刻表示、メンション強調、横画面チャットの調整");
        addSectionLink(screen, SECTION_HOME, "ホーム画面の不要な項目や＋ボタン、ナビゲーションの表示設定");
        addSectionLink(screen, SECTION_PLAYER, "回線別の自動画質切替、リロード・統計ボタン、スワイプ操作、オーバーレイ設定");
        addSectionLink(screen, SECTION_PRIVACY, "解析ツールやクラッシュレポート送信の無効化");
        addSectionLink(screen, SECTION_WATCHLOG, "見たチャンネルと視聴時間をサーバーに記録");
        addBackupLinks(screen);
    }

    // Kizu: 設定のエクスポート / インポート
    private void addBackupLinks(PreferenceScreen screen) {
        Preference export = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        export.setTitle("設定をエクスポート");
        export.setSummary("今の設定をファイルに保存します");
        export.setOnPreferenceClickListener(clicked -> {
            SettingsBackup.startExport(this);
            return true;
        });
        screen.addPreference(export);

        Preference imp = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        imp.setTitle("設定をインポート");
        imp.setSummary("保存した設定ファイルを読み込みます");
        imp.setOnPreferenceClickListener(clicked -> {
            SettingsBackup.startImport(this);
            return true;
        });
        screen.addPreference(imp);
    }

    private void addSectionLink(PreferenceScreen screen, String linkedSection, String summary) {
        Preference preference = TwitchUi.createSectionPreference(screen.getContext());
        preference.setTitle(title(linkedSection));
        preference.setSummary(summary);
        preference.setOnPreferenceClickListener(clicked -> {
            Activity activity = getActivity();
            if (activity != null) SettingsPatch.showScreen(activity, linkedSection);
            return true;
        });
        screen.addPreference(preference);
    }

    private void addGeneralSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.HIDE_STORIES, "ストーリーズを非表示",
                "ホーム／フォロー中画面の「ストーリーズ」欄を隠します。");
        Preference homeTab = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        homeTab.setTitle("起動時のホームタブ");
        homeTab.setSummary(homeTabName(Settings.DEFAULT_HOME_TAB.get()));
        homeTab.setOnPreferenceClickListener(clicked -> {
            Activity activity = getActivity();
            if (activity == null) return true;
            String[] names = {"フォロー中", "ライブ", "クリップ"};
            String[] values = {"following", "live", "clips"};
            String current = Settings.DEFAULT_HOME_TAB.get();
            int selected = homeTabIndex(current);
            SettingsUi.dialog(activity)
                    .setTitle("起動時のホームタブ")
                    .setSingleChoiceItems(names, selected, (dialog, which) -> {
                        Settings.DEFAULT_HOME_TAB.save(values[which]);
                        homeTab.setSummary(names[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(homeTab);

        if (PatchStatus.autoClaimChannelPoints()) addSwitch(screen, Settings.AUTO_CLAIM_CHANNEL_POINTS,
                "チャンネルポイントを自動獲得", "視聴中のチャンネルに出るボーナス宝箱を自動で受け取ります。");
    }

    private static int homeTabIndex(String value) {
        if ("live".equalsIgnoreCase(value)) return 1;
        if ("clips".equalsIgnoreCase(value)) return 2;
        return 0;
    }

    private static String homeTabName(String value) {
        if ("live".equalsIgnoreCase(value)) return "ライブ";
        if ("clips".equalsIgnoreCase(value)) return "クリップ";
        return "フォロー中";
    }

    private void addAppearanceSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.HIDE_STREAM_EXTENSIONS, "配信拡張機能を非表示",
                "チャンネルの拡張機能アイコン、パネル、映像上のオーバーレイを隠します。");
        addSwitch(screen, Settings.HIDE_SUBSCRIBE_BUTTONS, "サブスク・ビッツボタンを非表示",
                "チャット上部に表示されるサブスク・ギフト・ビッツのボタン列を隠します。");
        addSwitch(screen, Settings.HIDE_CHAT_BITS_BUTTON, "チャット欄のビッツボタンを非表示",
                "チャット入力欄のスタンプボタン横にあるビッツアイコンを隠します。");
        addSwitch(screen, Settings.HIDE_GIFT_LEADERBOARD, "ギフトランキングを非表示",
                "チャット上部に表示されるトップギフター／チアーランキングを隠します。");
        addSwitch(screen, Settings.HIDE_SUBSCRIPTION_PROMOTIONS, "サブスク関連のプロモーションを非表示",
                "プレイヤー上・チャット上のサブスク割引や SUBtember バナー、Turbo 案内バナーを隠します。");
        Preference note = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        note.setSummary("再度表示するようにした項目は、次に配信を開いたときから反映されます。");
        note.setSelectable(false);
        screen.addPreference(note);
    }

    private void addDanmakuSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.DANMAKU_ENABLED, "弾幕コメントを有効にする",
                "ライブ配信の映像上にチャットメッセージを流します。プレイヤー右上のボタンからも切り替えられます。");
        addSwitch(screen, Settings.DANMAKU_PORTRAIT, "縦画面でも表示",
                "スマホを縦向きで視聴しているときも映像上に弾幕を流します。");
        addSwitch(screen, Settings.DANMAKU_MINI_PLAYER, "ミニプレイヤーでも表示",
                "画面端の小さなプレイヤーで視聴中も弾幕を流します。");
        addSwitch(screen, Settings.DANMAKU_PICTURE_IN_PICTURE, "ピクチャー・イン・ピクチャーで表示",
                "他アプリ上に重ねる小窓再生中でも弾幕を流します。");
        addSwitch(screen, Settings.DANMAKU_HIDE_LANDSCAPE_CHAT, "横画面のチャット欄を無効化",
                "横画面時のチャット切替ボタンを隠し、チャットオーバーレイが出ないようにして映像を広く表示します。");
        addSlider(screen, Settings.DANMAKU_ROWS, 1, "行数（文字サイズ）", value -> value + " 行（行数が少ないほど文字が大きくなります）");
        addSlider(screen, Settings.DANMAKU_AREA, 5, "表示範囲", value -> "画面の上から " + value + "%");
        addSlider(screen, Settings.DANMAKU_DURATION, 500, "流れる時間", value -> String.format(Locale.ROOT, "%.1f 秒", value / 1000f));
        addSlider(screen, Settings.DANMAKU_MAX_COMMENTS, 10, "同時表示数の上限", value -> "最大 " + value + " 件（空きが出るまで以降のコメントはスキップ）");
        fontPreference = new FontPreference(SettingsUi.preferenceContext(getActivity()), this, Settings.DANMAKU_FONT);
        fontPreference.setTitle("フォント");
        screen.addPreference(fontPreference);
        addSlider(screen, Settings.DANMAKU_FONT_WEIGHT, 100, "フォントの太さ", value -> value + "（" + WEIGHT_NAMES[value / 100 - 1] + "）");
        addColor(screen, Settings.DANMAKU_TEXT_COLOR, "文字色");
        addColor(screen, Settings.DANMAKU_OUTLINE_COLOR, "縁取りの色");
        addSlider(screen, Settings.DANMAKU_OUTLINE_WIDTH, 1, "縁取りの太さ", value -> value == 0 ? "縁取りなし" : "文字サイズの " + value + "%");
        addSlider(screen, Settings.DANMAKU_OPACITY, 5, "不透明度", value -> value + "%");
    }

    private void addAdsSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.BLOCK_ADS, "広告ブロック",
                "ライブ・VOD・画面内の各種広告をブロックします。配信映像自体に埋め込まれる広告中は黒画面・ミュートで待機します。");
        addSwitch(screen, Settings.ADS_BYPASS_OVERLAY_PLAYER, "広告中に別プレイヤーを重ねて視聴",
                "通常再生（非マルチモード）で映像内広告が始まった際、広告回避プレイヤーを自動で重ねて本編を再生し、広告終了後に自動で閉じます。回避できない広告は黒画面・ミュートで待機します。");
        addSwitch(screen, Settings.ADS_EMBED_PLAYER_TYPE, "埋め込み（embed）プレイヤーとして取得",
                "ミッドロール広告を減らす目的で配信を embed 扱いで取得します。サブスクや Twitch Turbo の広告免除が外れることがあるため、通常はオフを推奨します。");

        Preference proxySelector = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        proxySelector.setTitle("プロキシ選択");
        proxySelector.setSummary(proxyName(Settings.ADS_PROXY_URL.get()));

        Preference proxy = new TextPreference(SettingsUi.preferenceContext(screen.getContext()), Settings.ADS_PROXY_URL,
                "https://example.com/live/{channel}?allow_source=true&allow_audio_only=true&fast_bread=true",
                "プロキシは無効です。上から選択するかカスタム URL を入力してください。");
        proxy.setTitle("プロキシ URL");
        proxy.setOnPreferenceChangeListener((pref, newValue) -> {
            String value = newValue instanceof String ? ((String) newValue).trim() : "";
            proxySelector.setSummary(proxyName(value));
            return true;
        });

        proxySelector.setOnPreferenceClickListener(clicked -> {
            Activity activity = getActivity();
            if (activity == null) return true;
            String current = Settings.ADS_PROXY_URL.get().trim();
            int selected = proxyIndex(current);
            SettingsUi.dialog(activity)
                    .setTitle("広告ブロック用プロキシ")
                    .setSingleChoiceItems(PROXY_NAMES, selected, (dialog, which) -> {
                        String url = PROXY_URLS[which];
                        Settings.ADS_PROXY_URL.save(url);
                        proxySelector.setSummary(PROXY_NAMES[which]);
                        proxy.setSummary(url.isEmpty()
                                ? "プロキシは無効です。上から選択するかカスタム URL を入力してください。"
                                : url);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(proxySelector);
        screen.addPreference(proxy);

        Preference note = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        note.setSummary("任意設定。ライブ配信をプロキシ経由で読み込むと映像内広告を回避できる場合があります。なお、サブスク加入中や Twitch Turbo など元々広告なしの配信は自動的にプロキシを通さず再生します。\n\nURL 内の {channel} はチャンネル名に置き換わります（省略時は末尾に付与）。公開プロキシが不調または接続失敗した場合は通常の Twitch 接続へ自動で切り替わります。");
        note.setSelectable(false);
        screen.addPreference(note);
    }

    private static int proxyIndex(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || "off".equalsIgnoreCase(trimmed)) return 0;
        for (int i = 0; i < PROXY_URLS.length; i++) {
            if (PROXY_URLS[i].equals(trimmed)) return i;
        }
        return -1;
    }

    private static String proxyName(String value) {
        int index = proxyIndex(value);
        return index >= 0 ? PROXY_NAMES[index] : "カスタムプロキシ URL";
    }

    private void addEmoteSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.EMOTES_THIRD_PARTY, "サードパーティスタンプを表示",
                "ライブチャットで 7TV / BTTV / FFZ のスタンプを表示します。");
        addSwitch(screen, Settings.EMOTES_ANIMATED, "アニメーションスタンプ",
                "動くサードパーティスタンプを再生します。オフにすると静止画で表示します。");
        addSwitch(screen, Settings.EMOTES_PICKER, "スタンプ選択画面に追加",
                "Twitch 標準のスタンプ一覧に 7TV / BTTV / FFZ スタンプを追加します。");
        addSwitch(screen, Settings.EMOTES_AUTOCOMPLETE, "入力時のスタンプ候補表示",
                "チャット入力中に 7TV / BTTV / FFZ スタンプの予測候補を表示します。");
        addSwitch(screen, Settings.EMOTES_TAP_PREVIEW, "スタンプタップでプレビュー表示",
                "チャット内の 7TV / BTTV / FFZ スタンプをタップしたとき、詳細カードを開きます。");
        addSwitch(screen, Settings.EMOTES_ZERO_WIDTH, "重ねがけ（Zero-width）スタンプを許可",
                "前のスタンプに重ねて表示するタイプのサードパーティスタンプを有効にします。");
    }

    private void addChatSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.CHAT_DELETED_MESSAGES, "削除されたメッセージを残す",
                "モデレーター等に削除されたチャットを画面上から消さずに残します。");
        Preference deletedMessageStyle = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        deletedMessageStyle.setTitle("削除済みメッセージの表示方法");
        deletedMessageStyle.setSummary(deletedMessageStyleName(Settings.CHAT_DELETED_MESSAGES_STYLE.get()));
        deletedMessageStyle.setOnPreferenceClickListener(clicked -> {
            Activity activity = getActivity();
            if (activity == null) return true;
            String[] names = {"モデレーター表示（標準）", "取り消し線", "グレー表示"};
            String[] values = {"mod", "strikethrough", "grey"};
            int selected = deletedMessageStyleIndex(Settings.CHAT_DELETED_MESSAGES_STYLE.get());
            SettingsUi.dialog(activity)
                    .setTitle("削除済みメッセージの表示方法")
                    .setSingleChoiceItems(names, selected, (dialog, which) -> {
                        Settings.CHAT_DELETED_MESSAGES_STYLE.save(values[which]);
                        deletedMessageStyle.setSummary(names[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(deletedMessageStyle);

        addSwitch(screen, Settings.CHAT_TIMESTAMPS, "タイムスタンプを表示", "チャットメッセージの先頭に送信時刻を表示します。");
        addSwitch(screen, Settings.CHAT_MENTION_HIGHLIGHT, "自分へのメンションを強調",
                "自分のアカウント名宛てのチャットメッセージを色付きで目立たせます。");
        addColor(screen, Settings.CHAT_MENTION_HIGHLIGHT_COLOR, "メンション強調の色");
        addSwitch(screen, Settings.CHAT_MENTION_SOUND, "メンション時に通知音を鳴らす",
                "自分宛ての新しいメンションが届いたときに短い通知音を再生します。");
        addSlider(screen, Settings.CHAT_MENTION_SOUND_COOLDOWN_MS, 1000, "通知音のクールダウン",
                value -> value == 0 ? "連続再生（待ち時間なし）" : (value / 1000) + " 秒間隔");
        addSwitch(screen, Settings.LANDSCAPE_CHAT_SIZE_ENABLED, "横画面チャットの幅を変更",
                "横画面オーバーレイチャットの横幅をカスタム設定に合わせます。");
        addSlider(screen, Settings.LANDSCAPE_CHAT_SIZE, 5, "横画面チャットの幅", value -> "画面幅の " + value + "%");
        addSwitch(screen, Settings.LANDSCAPE_CHAT_OPACITY_ENABLED, "横画面チャットの背景透明度を変更",
                "横画面オーバーレイチャットの透明度をカスタム設定に合わせます。");
        addSlider(screen, Settings.LANDSCAPE_CHAT_OPACITY, 5, "横画面チャットの透明度", value -> value + "%");
        addSwitch(screen, Settings.HIDE_LANDSCAPE_CHAT_INPUT, "全画面チャットで入力欄を非表示",
                "横画面（全画面）でコメント欄を表示している間、コメント入力欄を隠してコメント一覧を広く表示します。");
    }

    private static int deletedMessageStyleIndex(String value) {
        if ("strikethrough".equalsIgnoreCase(value)) return 1;
        if ("grey".equalsIgnoreCase(value)) return 2;
        return 0;
    }

    private static String deletedMessageStyleName(String value) {
        if ("strikethrough".equalsIgnoreCase(value)) return "取り消し線";
        if ("grey".equalsIgnoreCase(value) || "gray".equalsIgnoreCase(value)) return "グレー表示";
        return "モデレーター表示（標準）";
    }

    private void addPlayerSettings(PreferenceScreen screen) {
        addNetworkQualitySettings(screen);
        addSwitch(screen, Settings.SHOW_VIDEO_STATS_BUTTON, "再生統計ボタンを表示",
                "リロードボタンのすぐ左に Twitch 標準のライブ再生統計パネルを開くボタンを追加します。");
        addSwitch(screen, Settings.SHOW_REFRESH_BUTTON, "配信リロードボタンを表示",
                "ミュートボタンのすぐ左に 1 タップで映像を読み込み直すボタンを追加します。");
        addSwitch(screen, Settings.BRIGHTNESS_GESTURE, "スワイプで画面の明るさ調整",
                "横画面で映像左半分を上下にスワイプして画面の明るさを変更します。");
        addSwitch(screen, Settings.VOLUME_GESTURE, "スワイプで音量調整",
                "横画面で映像右半分を上下にスワイプしてメディア音量を変更します。");
        addSwitch(screen, Settings.GESTURE_OSD, "スワイプ調整の数値オーバーレイを表示",
                "明るさや音量をスワイプ調整している間、ゲージと数値を画面に表示します。");
        addSwitch(screen, Settings.DISABLE_LANDSCAPE_SWIPE_TO_PORTRAIT, "横画面からの縦戻りスワイプを防止",
                "横画面再生中に上下スワイプで縦画面へ戻る動作をブロックします。オフで Twitch 標準の動作に戻ります。");
        addSwitch(screen, Settings.HIDE_PLAYER_CREATE_CLIP_BUTTON, "クリップ作成ボタンを非表示",
                "プレイヤー上のクリップ作成ボタンを隠します。");
        addSwitch(screen, Settings.HIDE_PLAYER_LIVE_SHARE_BUTTON, "共有ボタンを非表示",
                "プレイヤー上の配信共有ボタンを隠します。");
        addSwitch(screen, Settings.HIDE_CAST_BUTTON, "キャストボタンを非表示",
                "プレイヤー上の Chromecast 接続ボタンを隠します。");
    }

    private void addNetworkQualitySettings(PreferenceScreen screen) {
        SwitchPreference toggle = new SwitchPreference(SettingsUi.preferenceContext(screen.getContext()));
        toggle.setKey(Settings.AUTO_NETWORK_QUALITY.key);
        toggle.setDefaultValue(Settings.AUTO_NETWORK_QUALITY.defaultValue);
        toggle.setTitle("回線別の自動画質切替");
        toggle.setSummary("Wi-Fi 接続時とモバイル回線時で、通常再生・マルチ視聴の画質を自動検知して切り替えます。");
        toggle.setOnPreferenceChangeListener((changed, newValue) -> {
            Settings.AUTO_NETWORK_QUALITY.save(Boolean.TRUE.equals(newValue));
            app.morphe.extension.player.NetworkQualityManager.onSettingsChanged();
            return true;
        });
        screen.addPreference(toggle);

        addQualityPresetPreference(screen, Settings.WIFI_QUALITY, "Wi-Fi 接続時の画質");
        addQualityPresetPreference(screen, Settings.MOBILE_QUALITY, "モバイル回線時の画質");
    }

    private void addQualityPresetPreference(PreferenceScreen screen, StringSetting setting, String title) {
        Preference pref = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        pref.setTitle(title);
        pref.setSummary(app.morphe.extension.player.NetworkQualityManager.labelForPreset(setting.get()));
        pref.setOnPreferenceClickListener(clicked -> {
            Activity activity = getActivity();
            if (activity == null) return true;
            String[] values = app.morphe.extension.player.NetworkQualityManager.PRESET_VALUES;
            String[] labels = app.morphe.extension.player.NetworkQualityManager.PRESET_LABELS;
            int selected = app.morphe.extension.player.NetworkQualityManager.indexForPreset(setting.get());
            SettingsUi.dialog(activity)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                        if (which >= 0 && which < values.length) {
                            setting.save(values[which]);
                            pref.setSummary(labels[which]);
                            app.morphe.extension.player.NetworkQualityManager.onSettingsChanged();
                        }
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(pref);
    }

    private void addHomeSettings(PreferenceScreen screen) {
        addHomeSwitch(screen, Settings.HIDE_CREATE_BUTTON, "配信開始（＋）ボタンを非表示",
                "ホーム画面下の真ん中にある配信開始（＋）ボタンを隠します。");
        addHomeSwitch(screen, Settings.HIDE_TURBO_UPSELL, "広告なし（Turbo）案内を非表示",
                "フォロー中画面に出る「広告なし／Turbo」の案内ボタンを隠します。");
        addHomeSwitch(screen, Settings.HIDE_RESUME_WATCHING, "視聴を再開（Continue Watching）を非表示",
                "フォロー中画面から「視聴を再開」セクションを取り除きます。");
        addHomeSwitch(screen, Settings.HIDE_OFFLINE_CHANNELS, "オフラインチャンネル欄を非表示",
                "フォロー中画面からオフライン中のフォローチャンネル一覧を取り除きます。");
    }

    private void addHomeSwitch(PreferenceGroup group, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference =
                new SwitchPreference(SettingsUi.preferenceContext(group.getContext()));
        preference.setKey(setting.key);
        preference.setDefaultValue(setting.defaultValue);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setOnPreferenceChangeListener((changed, newValue) -> {
            setting.save(Boolean.TRUE.equals(newValue));
            if (setting == Settings.HIDE_RESUME_WATCHING
                    || setting == Settings.HIDE_OFFLINE_CHANNELS) {
                app.morphe.extension.appearance.HidePromotionsPatch
                        .onHomeSectionSettingChanged();
            }
            return true;
        });
        group.addPreference(preference);
    }

    private void addPrivacySettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.DISABLE_COMSCORE, "Comscore 計測を無効化",
                "Twitch の Comscore 視聴計測コンポーネントの起動を防ぎます。");
        addSwitch(screen, Settings.DISABLE_BUGSNAG, "クラッシュレポート送信を無効化",
                "Twitch の自動クラッシュレポート収集（Bugsnag）を無効にします。");
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View list = super.onCreateView(inflater, container, savedInstanceState);
        if (!SECTION_DANMAKU.equals(section)) return list;
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        int margin = SettingsUi.dp(getActivity(), 12f);
        previewParams.setMargins(margin, margin, margin, SettingsUi.dp(getActivity(), 6f));
        layout.addView(new DanmakuPreview(getActivity()), previewParams);
        layout.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return layout;
    }

    @Override public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            SettingsUi.applySettingsView(view);
        } catch (Throwable error) {
            Utils.logError("Failed to apply Kizu settings appearance", error);
        }
        view.setClickable(true);
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FontPreference.REQUEST_IMPORT_FONT && fontPreference != null) {
            fontPreference.onActivityResult(resultCode, data);
            return;
        }
        if (SettingsBackup.onActivityResult(getActivity(), requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onResume() {
        super.onResume();
        try {
            View settingsView = getView();
            if (settingsView != null) SettingsUi.applySettingsView(settingsView);
            Activity activity = getActivity();
            TextView title = activity == null ? null : SettingsPatch.findToolbarTitle(activity);
            if (title == null) return;
            if (previousTitle == null) previousTitle = title.getText();
            title.setText(title(section));
        } catch (Throwable error) {
            Utils.logError("Failed to resume Kizu settings screen", error);
        }
    }

    @Override public void onDestroyView() {
        try {
            Activity activity = getActivity();
            TextView title = activity == null ? null : SettingsPatch.findToolbarTitle(activity);
            if (title != null && previousTitle != null) title.setText(previousTitle);
        } catch (Throwable error) {
            Utils.logError("Failed to restore Twitch settings title", error);
        } finally {
            super.onDestroyView();
        }
    }

    private static String title(String section) {
        if (section == null) return SettingsPatch.TITLE;
        switch (section) {
            case SECTION_GENERAL: return "全般";
            case SECTION_APPEARANCE: return "外観・表示";
            case SECTION_DANMAKU: return "弾幕コメント";
            case SECTION_ADS: return "広告ブロック";
            case SECTION_EMOTES: return "スタンプ";
            case SECTION_CHAT: return "チャット";
            case SECTION_PRIVACY: return "プライバシー";
            case SECTION_HOME: return "ホーム・ナビゲーション";
            case SECTION_PLAYER: return "プレイヤー操作";
            case SECTION_WATCHLOG: return "視聴ログ";
            default: return SettingsPatch.TITLE;
        }
    }

    private void addWatchLogSettings(PreferenceScreen screen) {
        addSwitch(screen, Settings.WATCHLOG_ENABLED, "視聴時間を記録",
                "再生中のチャンネルと時間を1分ごとに視聴ログへ送ります。");
        Preference url = new TextPreference(SettingsUi.preferenceContext(screen.getContext()), Settings.WATCHLOG_SERVER_URL,
                "https://watchlog.example.workers.dev", "未設定");
        url.setTitle("サーバーURL");
        screen.addPreference(url);
        Preference key = new SecretTextPreference(SettingsUi.preferenceContext(screen.getContext()), Settings.WATCHLOG_API_KEY);
        key.setTitle("APIキー");
        screen.addPreference(key);
        Preference note = new Preference(SettingsUi.preferenceContext(screen.getContext()));
        note.setSummary("送るのはチャンネル名・秒数・端末名だけです。通信できないときは最大6時間分まで溜めて再送します。設定のエクスポートにはAPIキーも含まれるので、ファイルの扱いに注意してください。");
        note.setSelectable(false);
        screen.addPreference(note);
    }

    private void addSwitch(PreferenceGroup group, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new SwitchPreference(SettingsUi.preferenceContext(group.getContext()));
        preference.setKey(setting.key);
        preference.setDefaultValue(setting.defaultValue);
        preference.setTitle(title);
        preference.setSummary(summary);
        group.addPreference(preference);
    }

    private static void addSlider(PreferenceGroup group, IntSetting setting, int step, String title, SliderPreference.Formatter formatter) {
        Preference preference = new SliderPreference(SettingsUi.preferenceContext(group.getContext()), setting, step, formatter);
        preference.setTitle(title);
        group.addPreference(preference);
    }

    private static void addColor(PreferenceGroup group, IntSetting setting, String title) {
        Preference preference = new ColorPreference(SettingsUi.preferenceContext(group.getContext()), setting);
        preference.setTitle(title);
        group.addPreference(preference);
    }
}

