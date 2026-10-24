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

    private KizuSettings() {}
}