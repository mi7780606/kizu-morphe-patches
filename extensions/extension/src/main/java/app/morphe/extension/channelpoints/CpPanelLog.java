package app.morphe.extension.channelpoints;

import java.lang.reflect.Field;

/**
 * Twitch 本体のチャンネルポイント画面（31.3.1: gb9）で起きた画面切り替えとエラーを CpLog に残す。
 * パッチが eb9.invoke / cb9.invoke の先頭で onEvent(this, arg) を呼ぶ。表示や動作は変えない。
 */
public final class CpPanelLog {
    private static Field kindField;
    private static volatile String last;
    private static volatile long lastAt;

    private CpPanelLog() {}

    /** lambda: eb9 か cb9。arg: 画面の状態 / イベント / 例外 */
    public static void onEvent(Object lambda, Object arg) {
        try {
            if (arg == null) return;
            String text;
            if (arg instanceof Throwable) {
                Throwable t = (Throwable) arg;
                text = "CP画面: エラーで閉じました " + t.getClass().getName() + ": " + t.getMessage()
                        + (t.getCause() != null ? " / " + t.getCause() : "");
            } else {
                int kind = kind(lambda);
                String s = String.valueOf(arg);
                String name = s;
                int paren = s.indexOf('(');
                if (paren > 0) name = s.substring(0, paren);
                if (name.length() > 60) name = arg.getClass().getName();
                // 一覧の再描画などは数が多いので、画面の状態（eb9 の case 0）だけ記録
                if (!(lambda.getClass().getName().equals("eb9") && kind == 0)) return;
                String detail = "";
                int err = s.indexOf("error=");
                if (err >= 0) detail = " " + s.substring(err, Math.min(s.length(), err + 120));
                text = "CP画面: " + label(name) + " [" + name + "]" + detail;
            }
            long now = System.currentTimeMillis();
            if (text.equals(last) && now - lastAt < 1500) return;
            last = text;
            lastAt = now;
            CpLog.add(text);
        } catch (Throwable ignored) {}
    }

    private static int kind(Object lambda) {
        try {
            Field f = kindField;
            if (f == null || f.getDeclaringClass() != lambda.getClass()) {
                f = lambda.getClass().getDeclaredField("a");
                f.setAccessible(true);
                kindField = f;
            }
            return f.getInt(lambda);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String label(String name) {
        switch (name) {
            case "RewardSelectionError": return "報酬を選んだらエラー";
            case "RewardSelectionInterstitial": return "報酬の確認画面";
            case "RewardsDisabledError": return "報酬が無効";
            case "PredictionDetails": return "予想の画面";
            case "PredictionCelebration": return "予想の結果";
            case "RewardUnlockEmoteError": return "スタンプ解放エラー";
            case "CustomRewardNoInputRedemptionError": return "交換エラー";
            case "RewardRedeemed": return "交換完了";
            case "RewardCancelled": return "閉じた";
            default: return "状態";
        }
    }
}
