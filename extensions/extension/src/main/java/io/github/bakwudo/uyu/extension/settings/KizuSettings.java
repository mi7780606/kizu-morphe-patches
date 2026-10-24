package io.github.bakwudo.uyu.extension.settings;

/** 自分で追加した機能のオン・オフ。値はKizuの設定と同じ場所に保存される。 */
public final class KizuSettings {
    public static final BooleanSetting SMOOTH_CHAT =
            new BooleanSetting("kizu_smooth_chat", true);
    public static final BooleanSetting SMOOTH_SCROLL =
            new BooleanSetting("kizu_smooth_scroll", true);
    public static final BooleanSetting CHAT_HISTORY =
            new BooleanSetting("kizu_chat_history", true);
    public static final BooleanSetting HIDE_HYPE_TRAIN =
            new BooleanSetting("kizu_hide_hype_train", true);
    public static final BooleanSetting HIDE_CLAIM_POPUP =
            new BooleanSetting("kizu_hide_claim_popup", true);
    public static final BooleanSetting DANMAKU_QUICK_SETTINGS =
            new BooleanSetting("kizu_danmaku_quick_settings", true);
    /** 確認用メッセージ。常にオフ（設定画面にも出さない） */
    public static final BooleanSetting DEBUG_MESSAGES =
            new BooleanSetting("kizu_debug_messages_v2", false);

    /** チャット入力欄右の三点リーダー（チャット設定ボタン）を隠す */
    public static final BooleanSetting HIDE_CHAT_MENU_BUTTON =
            new BooleanSetting("kizu_hide_chat_menu_button", true);

    /** NGワード（1行に1つ、本文と完全一致したコメントだけ消す） */
    public static final StringSetting NG_WORDS =
            new StringSetting("kizu_ng_words", "");

    /** 予想（賭け）の自動参加 */
    public static final BooleanSetting PREDICTION_AUTO =
            new BooleanSetting("kizu_prediction_auto", false);
    /** 何番目の選択肢に賭けるか（1が一番上） */
    public static final IntSetting PREDICTION_OUTCOME =
            new IntSetting("kizu_prediction_outcome", 1, 1, 10);
    /** 賭けるポイント */
    public static final IntSetting PREDICTION_POINTS =
            new IntSetting("kizu_prediction_points", 100, 1, 250000);

    private KizuSettings() {}
}
