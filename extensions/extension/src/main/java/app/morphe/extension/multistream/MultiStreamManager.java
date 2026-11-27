package app.morphe.extension.multistream;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.Utils;
import app.morphe.extension.ads.BlockAdsPatch;
import app.morphe.extension.channelpoints.ChannelPoints;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;

/**
 * Central coordinator for Kizu's hybrid Multi-Stream feature:
 * - Mode A: Draggable floating Sub-PiP overlay ({@link MultiStreamPipOverlay}) with proportional Danmaku.
 * - Mode B: Dedicated 1–4 stream fullscreen Multi-View ({@link MultiStreamDialog}).
 */
public final class MultiStreamManager {
    private static final String TAG = "KizuMultiStream";

    private static MultiStreamPipOverlay activePip;
    private static MultiStreamDialog activeDialog;

    private MultiStreamManager() {}

    /** Returns true while Mode A Sub-PiP or Mode B Multi-View is currently active. */
    public static boolean isMultiModeActive() {
        return (activePip != null && activePip.isShowing())
                || (activeDialog != null && activeDialog.isShowing());
    }

    /** Opens the hybrid mode launcher sheet from the player top-right multi-view button. */
    public static void openLauncherSheet(Context context) {
        Activity activity = resolveActivity(context);
        if (activity == null) return;
        try {
            Dialog dlg = TwitchUi.createBottomSheet(activity);
            int pad = TwitchUi.dp(activity, 16f);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, 0, pad, pad);
            root.addView(TwitchUi.dragHandle(activity));

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, TwitchUi.dp(activity, 4f), 0, TwitchUi.dp(activity, 12f));

            TextView title = new TextView(activity);
            title.setText("マルチ視聴（同時視聴）");
            title.setTextColor(SettingsUi.primaryTextColor(activity));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            header.addView(TwitchUi.createCloseButton(activity, dlg::dismiss));
            root.addView(header);

            boolean pipShowing = activePip != null && activePip.isShowing();
            String currentMain = ChannelPoints.currentChannelLogin();

            TextView pipBtn = TwitchUi.createPrimaryButton(
                    activity,
                    pipShowing ? "サブ小窓の配信を変更（PiP）" : "サブ小窓を追加（PiP・弾幕付き）");
            pipBtn.setOnClickListener(v -> {
                dlg.dismiss();
                Set<String> active = new HashSet<>();
                if (currentMain != null && !currentMain.isEmpty()) active.add(currentMain);
                if (activePip != null && activePip.isShowing()) active.add(activePip.getChannelLogin());
                ChannelPickerSheet.show(activity, "サブ小窓で開く配信を選択", active,
                        (login, displayName) -> startPip(activity, login, displayName));
            });
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(-1, -2);
            b1.bottomMargin = TwitchUi.dp(activity, 10f);
            root.addView(pipBtn, b1);

            TextView multiBtn = TwitchUi.createSecondaryButton(
                    activity,
                    pipShowing ? "小窓を含めてマルチビューへ展開（最大4枠）" : "マルチビューで開く（最大4枠）");
            multiBtn.setOnClickListener(v -> {
                dlg.dismiss();
                openMultiViewFromCurrent(activity);
            });
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(-1, -2);
            b2.bottomMargin = pipShowing ? TwitchUi.dp(activity, 10f) : TwitchUi.dp(activity, 6f);
            root.addView(multiBtn, b2);

            if (pipShowing) {
                TextView closePipBtn = TwitchUi.createGhostButton(activity, "サブ小窓を閉じる", SettingsUi.dangerColor());
                closePipBtn.setOnClickListener(v -> {
                    dlg.dismiss();
                    closePip();
                });
                root.addView(closePipBtn, new LinearLayout.LayoutParams(-1, -2));
            }

            TextView note = new TextView(activity);
            note.setText("・サブ小窓：今のチャットや弾幕を残したまま別配信を重ねて表示します（小窓にも比率合わせの弾幕が出ます）。\n"
                    + "・マルチビュー：最大4枠を同時視聴。タップで音声フォーカス、🔊ボタンで同時聴き、ダブルタップでメイン入替。");
            note.setTextColor(SettingsUi.secondaryTextColor(activity));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            note.setLineSpacing(TwitchUi.dp(activity, 2f), 1f);
            note.setPadding(0, TwitchUi.dp(activity, 8f), 0, 0);
            root.addView(note);

            TwitchUi.prepareBottomSheet(dlg, activity, root);
            dlg.setContentView(root);
            dlg.show();
        } catch (Throwable t) {
            Log.e(TAG, "openLauncherSheet failed", t);
        }
    }

    /** Opens Mode B directly (used from Kizu Extra Settings). */
    public static void openMultiViewDirect(Context context) {
        Activity activity = resolveActivity(context);
        if (activity == null) return;
        openMultiViewFromCurrent(activity);
    }

    private static void openMultiViewFromCurrent(Activity activity) {
        List<String> initial = new ArrayList<>(2);
        String mainLogin = ChannelPoints.currentChannelLogin();
        if (mainLogin != null && !mainLogin.isEmpty()) {
            initial.add(mainLogin);
        }
        if (activePip != null && activePip.isShowing()) {
            String pipLogin = activePip.getChannelLogin();
            if (pipLogin != null && !pipLogin.isEmpty() && !initial.contains(pipLogin)) {
                initial.add(pipLogin);
            }
            closePip();
        }
        openMultiView(activity, initial);
    }

    static void startPip(Activity activity, String login, String displayName) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            AdBypassOverlayPlayer.stopAll();
            if (activeDialog != null && activeDialog.isShowing()) {
                activeDialog.dismiss();
                activeDialog = null;
            }
            if (activePip != null && activePip.isShowing()) {
                activePip.switchChannel(login, displayName);
            } else {
                closePip();
                activePip = new MultiStreamPipOverlay(activity, login, displayName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startPip failed", t);
        }
    }

    static void closePip() {
        MultiStreamPipOverlay pip = activePip;
        activePip = null;
        if (pip != null) {
            try { pip.dismiss(); } catch (Throwable ignored) {}
        }
    }

    static void expandPipToMultiView(Activity activity, String pipLogin, String pipDisplayName) {
        if (activity == null) return;
        List<String> initial = new ArrayList<>(2);
        String mainLogin = ChannelPoints.currentChannelLogin();
        if (mainLogin != null && !mainLogin.isEmpty()) initial.add(mainLogin);
        if (pipLogin != null && !pipLogin.isEmpty() && !initial.contains(pipLogin)) initial.add(pipLogin);
        closePip();
        openMultiView(activity, initial);
    }

    static void collapseMultiViewToPip(Activity activity, String login, String displayName) {
        if (activity == null || login == null || login.isEmpty()) return;
        startPip(activity, login, displayName);
    }

    static void onMultiViewClosed(MultiStreamDialog dialog) {
        if (activeDialog == dialog) {
            activeDialog = null;
            BlockAdsPatch.setNativePlaybackSuppressed(false);
        }
    }

    private static void openMultiView(Activity activity, List<String> initialLogins) {
        try {
            AdBypassOverlayPlayer.stopAll();
            if (activeDialog != null) {
                activeDialog.dismiss();
                activeDialog = null;
            }
            stopAndCloseNativePlayer(activity);
            MultiStreamDialog dlg = new MultiStreamDialog(activity, initialLogins);
            activeDialog = dlg;
            dlg.show(true);
        } catch (Throwable t) {
            Log.e(TAG, "openMultiView failed", t);
        }
    }

    /**
     * Immediately mutes/pauses Twitch's underlying native player and closes its theatre/mini-player
     * UI so background audio and video decoding do not overlap Mode B Multi-View.
     */
    private static void stopAndCloseNativePlayer(Activity activity) {
        BlockAdsPatch.setNativePlaybackSuppressed(true);
        if (activity == null || activity.getWindow() == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) return;
            if (tryClickCloseButton(decor)) return;

            int playerDelegateId = Utils.getResourceId(activity, "player_view_delegate", "id");
            View playerView = playerDelegateId != 0 ? decor.findViewById(playerDelegateId) : null;
            if (playerView == null || !playerView.isShown()) return;

            int backId = Utils.getResourceId(activity, "back_button", "id");
            View backBtn = backId != 0 ? decor.findViewById(backId) : null;
            if (backBtn != null && backBtn.isClickable()) {
                backBtn.performClick();
                final WeakReference<Activity> ref = new WeakReference<>(activity);
                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(() -> {
                    Activity cur = ref.get();
                    if (cur == null || cur.isFinishing() || cur.isDestroyed()) return;
                    View d = cur.getWindow() == null ? null : cur.getWindow().getDecorView();
                    if (d != null) tryClickCloseButton(d);
                    BlockAdsPatch.setNativePlaybackSuppressed(true);
                }, 220L);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean tryClickCloseButton(View root) {
        String[] closeIds = {
                "mini_player_close_button",
                "close_mini_player_button",
                "player_close_button",
                "close_button",
        };
        for (String name : closeIds) {
            int id = Utils.getResourceId(root.getContext(), name, "id");
            if (id == 0) continue;
            View v = root.findViewById(id);
            if (v != null && v.isShown() && v.isClickable()) {
                v.performClick();
                return true;
            }
        }
        return false;
    }

    public static void onActivityPaused(Activity activity) {
        if (activePip != null && activePip.belongsTo(activity)) activePip.pause();
        if (activeDialog != null && activeDialog.belongsTo(activity)) activeDialog.pause();
        AdBypassOverlayPlayer.onActivityPaused(activity);
    }

    public static void onActivityResumed(Activity activity) {
        if (activePip != null && activePip.belongsTo(activity)) activePip.resume();
        if (activeDialog != null && activeDialog.belongsTo(activity)) activeDialog.resume();
        AdBypassOverlayPlayer.onActivityResumed(activity);
    }

    public static void onActivityDestroyed(Activity activity) {
        AdBypassOverlayPlayer.onActivityDestroyed(activity);
        if (activeDialog != null && activeDialog.belongsTo(activity)) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        if (activePip != null && activePip.belongsTo(activity)) {
            closePip();
        }
    }

    private static Activity resolveActivity(Context context) {
        Activity a = Utils.findActivity(context);
        if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
        return Utils.getCurrentActivity();
    }
}
