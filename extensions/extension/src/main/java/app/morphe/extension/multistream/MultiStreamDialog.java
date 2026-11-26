package app.morphe.extension.multistream;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.KizuRuntime;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import io.github.bakwudo.uyu.extension.danmaku.DanmakuView;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

/**
 * Mode B: Dedicated Multi-View supporting up to 4 simultaneous live streams.
 *
 * - Non-fullscreen window respecting system status/navigation bars & display cutouts.
 * - Portrait: non-overlapping top header, streams tightly top-packed with 1dp hairline dividers
 *   and zero blank gaps between videos, proportional Danmaku on focused stream, compact comment input only.
 * - Landscape: full video grid with auto-hiding top header, proportional Danmaku, no chat input.
 * - Audio: configurable right inside Mode B between "Tap-to-Switch" and "Lock Audio Channel",
 *   plus per-slot simultaneous unmute buttons.
 */
final class MultiStreamDialog {
    static final int MAX_SLOTS = 4;
    private static final long HUD_AUTO_HIDE_MS = 3500L;
    private static final long DOUBLE_TAP_WINDOW_MS = 300L;

    private final WeakReference<Activity> activityRef;
    private final Dialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StreamSlot> slots = new ArrayList<>(MAX_SLOTS);

    private final FrameLayout rootFrame;
    private final LinearLayout mainColumn;
    private final LinearLayout topBar;
    private final FrameLayout videoArea;
    private final LinearLayout commentBar;
    private final EditText commentInput;
    private final TextView sendBtn;
    private final TextView topTitle;
    private final TextView audioModeBtn;
    private final TextView layoutBtn;
    private final TextView danmakuToggleBtn;
    private final TextView addBtn;
    private final TextView toPipBtn;
    private final TextView emptyHint;

    private final DanmakuView danmakuView;
    private final MultiStreamIrcClient ircClient;
    private final Runnable hideHudRunnable = this::hideHud;

    private int focusedIndex = -1;
    private String lockedAudioLogin;
    private boolean allMutedInLockMode;
    private int layoutMode;
    private int lastLayoutSlotCount = -1;
    private boolean lastLayoutLandscape;
    private boolean danmakuEnabled = true;
    private boolean hudVisible = true;
    private boolean dismissed;

    MultiStreamDialog(Activity activity, List<String> initialLogins) {
        this.activityRef = new WeakReference<>(activity);
        // Non-fullscreen theme so status bar, cutout, and navigation/tab bars are never covered.
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar);

        Window w = dialog.getWindow();
        if (w != null) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0xFF0E0E10);
            w.setNavigationBarColor(0xFF0E0E10);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(0xFF0E0E10);
        applySafeSystemInsets(rootFrame);

        mainColumn = new LinearLayout(activity);
        mainColumn.setOrientation(LinearLayout.VERTICAL);

        // Top control bar: docked cleanly above videoArea in Portrait (never overlapping Slot 0),
        // auto-hides on tap in Landscape.
        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF141418);
        topBar.setPadding(TwitchUi.dp(activity, 8f), TwitchUi.dp(activity, 6f),
                TwitchUi.dp(activity, 8f), TwitchUi.dp(activity, 6f));

        TextView backBtn = topActionChip(activity, "←", v -> dismiss());
        topBar.addView(backBtn);

        topTitle = new TextView(activity);
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setPadding(TwitchUi.dp(activity, 6f), 0, TwitchUi.dp(activity, 4f), 0);
        topBar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        audioModeBtn = topActionChip(activity, "音声", v -> openAudioSettingsSheet());
        topBar.addView(audioModeBtn);

        toPipBtn = topActionChip(activity, "小窓", v -> minimizeToPip());
        topBar.addView(toPipBtn);

        layoutBtn = topActionChip(activity, "⊞", v -> {
            layoutMode = (layoutMode + 1) % layoutVariantCount();
            relayoutSlots();
            updateTopBarState();
            scheduleHudHide();
        });
        topBar.addView(layoutBtn);

        danmakuToggleBtn = topActionChip(activity, "弾幕", v -> {
            danmakuEnabled = !danmakuEnabled;
            applyDanmakuState();
            updateTopBarState();
            scheduleHudHide();
        });
        topBar.addView(danmakuToggleBtn);

        addBtn = topActionChip(activity, "＋", v -> openAddChannelPicker());
        addBtn.setBackground(TwitchUi.roundedRect(0xFF9147FF, TwitchUi.dp(activity, 6f), 0, 0));
        topBar.addView(addBtn);

        mainColumn.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        videoArea = new FrameLayout(activity);
        videoArea.setBackgroundColor(0xFF0E0E10);
        videoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!dismissed) {
                updateOrientationUi();
                relayoutSlots();
            }
        });

        emptyHint = new TextView(activity);
        emptyHint.setText("右上の「＋」から視聴する配信を選んでください（最大4枠）");
        emptyHint.setTextColor(0xFFADADB8);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(TwitchUi.dp(activity, 24f), 0, TwitchUi.dp(activity, 24f), 0);
        videoArea.addView(emptyHint, new FrameLayout.LayoutParams(-1, -1));

        mainColumn.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Portrait-only compact comment input bar (no scrolling chat list; GONE in Landscape).
        commentBar = new LinearLayout(activity);
        commentBar.setOrientation(LinearLayout.HORIZONTAL);
        commentBar.setGravity(Gravity.CENTER_VERTICAL);
        commentBar.setBackgroundColor(0xFF18181B);
        int barPadH = TwitchUi.dp(activity, 10f);
        int barPadV = TwitchUi.dp(activity, 7f);
        commentBar.setPadding(barPadH, barPadV, barPadH, barPadV);

        commentInput = TwitchUi.createPillInput(activity, "コメントを送信...");
        commentInput.setSingleLine(true);
        commentInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        commentInput.setOnEditorActionListener((v, actionId, event) -> {
            sendComment();
            return true;
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        inputLp.rightMargin = TwitchUi.dp(activity, 8f);
        commentBar.addView(commentInput, inputLp);

        sendBtn = TwitchUi.createPrimaryButton(activity, "送信");
        sendBtn.setPadding(TwitchUi.dp(activity, 14f), TwitchUi.dp(activity, 9f),
                TwitchUi.dp(activity, 14f), TwitchUi.dp(activity, 9f));
        sendBtn.setOnClickListener(v -> sendComment());
        commentBar.addView(sendBtn, new LinearLayout.LayoutParams(-2, -2));

        mainColumn.addView(commentBar, new LinearLayout.LayoutParams(-1, -2));
        rootFrame.addView(mainColumn, new FrameLayout.LayoutParams(-1, -1));

        danmakuView = new DanmakuView(activity);
        danmakuView.setClickable(false);
        danmakuView.setFocusable(false);

        ircClient = new MultiStreamIrcClient(activity, comment -> {
            if (!dismissed && danmakuEnabled) {
                danmakuView.addComment(comment);
            }
        });

        dialog.setContentView(rootFrame);
        dialog.setOnDismissListener(d -> cleanup());

        if (initialLogins != null) {
            for (String login : initialLogins) {
                if (slots.size() >= MAX_SLOTS) break;
                addSlotInternal(login, login, false);
            }
        }
        if (!slots.isEmpty()) {
            lockedAudioLogin = slots.get(0).login;
            setFocus(0);
        }
        updateOrientationUi();
        updateTopBarState();
        applyDanmakuState();
    }

    @SuppressWarnings("deprecation")
    private static void applySafeSystemInsets(View target) {
        target.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                l = sys.left;
                t = sys.top;
                r = sys.right;
                b = Math.max(sys.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            if (v.getPaddingLeft() != l || v.getPaddingTop() != t
                    || v.getPaddingRight() != r || v.getPaddingBottom() != b) {
                v.setPadding(l, t, r, b);
            }
            return insets;
        });
    }

    void show(boolean promptAddIfUnderTwo) {
        if (dismissed) return;
        dialog.show();
        rootFrame.requestApplyInsets();
        showHud();
        if (promptAddIfUnderTwo && slots.size() < 2) {
            main.postDelayed(this::openAddChannelPicker, 180L);
        }
    }

    boolean isShowing() {
        return !dismissed && dialog.isShowing();
    }

    boolean belongsTo(Activity activity) {
        return activity != null && activityRef.get() == activity;
    }

    void pause() {
        if (dismissed) return;
        for (StreamSlot slot : slots) slot.player.pause();
        danmakuView.setPaused(true);
    }

    void resume() {
        if (dismissed) return;
        for (StreamSlot slot : slots) slot.player.resume();
        danmakuView.setPaused(!danmakuEnabled);
    }

    void dismiss() {
        if (dismissed) return;
        try { dialog.dismiss(); } catch (Throwable ignored) {}
        cleanup();
    }

    private void cleanup() {
        if (dismissed) return;
        dismissed = true;
        main.removeCallbacks(hideHudRunnable);
        for (StreamSlot slot : slots) {
            slot.player.release();
        }
        slots.clear();
        ircClient.disconnect();
        danmakuView.clear();
        MultiStreamManager.onMultiViewClosed(this);
    }

    private void openAudioSettingsSheet() {
        Activity act = activityRef.get();
        if (act == null || dismissed) return;

        Dialog sheet = TwitchUi.createBottomSheet(act);
        int pad = TwitchUi.dp(act, 16f);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, pad);
        root.addView(TwitchUi.dragHandle(act));

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, TwitchUi.dp(act, 4f), 0, TwitchUi.dp(act, 10f));
        TextView title = new TextView(act);
        title.setText("マルチ視聴の音声設定");
        title.setTextColor(SettingsUi.primaryTextColor(act));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(TwitchUi.createCloseButton(act, sheet::dismiss));
        root.addView(header);

        boolean lockMode = KizuSettings.MULTI_STREAM_AUDIO_LOCK.get();

        TextView modeLabel = new TextView(act);
        modeLabel.setText("音声の切り替え方式");
        modeLabel.setTextColor(SettingsUi.secondaryTextColor(act));
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        modeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        modeLabel.setPadding(0, 0, 0, TwitchUi.dp(act, 6f));
        root.addView(modeLabel);

        root.addView(buildModeRow(act,
                "タップした配信に切り替え",
                "枠をタップすると音声もその配信へ自動で切り替わります。",
                !lockMode,
                () -> {
                    KizuSettings.MULTI_STREAM_AUDIO_LOCK.save(false);
                    allMutedInLockMode = false;
                    applyAudioFocus();
                    updateTopBarState();
                    sheet.dismiss();
                }));

        root.addView(buildModeRow(act,
                "音声チャンネルを固定",
                "別枠をタップして弾幕やコメント送信先を変えても、固定した配信の音声を維持します。",
                lockMode,
                () -> {
                    KizuSettings.MULTI_STREAM_AUDIO_LOCK.save(true);
                    if ((lockedAudioLogin == null || findSlotByLogin(lockedAudioLogin) < 0)
                            && focusedIndex >= 0 && focusedIndex < slots.size()) {
                        lockedAudioLogin = slots.get(focusedIndex).login;
                    }
                    allMutedInLockMode = false;
                    applyAudioFocus();
                    updateTopBarState();
                    sheet.dismiss();
                }));

        if (!slots.isEmpty()) {
            View div = TwitchUi.divider(act);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, Math.max(1, TwitchUi.dp(act, 0.75f)));
            dLp.topMargin = TwitchUi.dp(act, 10f);
            dLp.bottomMargin = TwitchUi.dp(act, 10f);
            root.addView(div, dLp);

            TextView chanLabel = new TextView(act);
            chanLabel.setText("音声を固定するチャンネルを選択（タップで固定ON）");
            chanLabel.setTextColor(SettingsUi.secondaryTextColor(act));
            chanLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chanLabel.setTypeface(Typeface.DEFAULT_BOLD);
            chanLabel.setPadding(0, 0, 0, TwitchUi.dp(act, 6f));
            root.addView(chanLabel);

            for (StreamSlot slot : slots) {
                boolean selected = lockMode && !allMutedInLockMode && slot.login.equals(lockedAudioLogin);
                root.addView(buildChannelAudioRow(act, "🔊 " + slot.displayName, selected, () -> {
                    KizuSettings.MULTI_STREAM_AUDIO_LOCK.save(true);
                    lockedAudioLogin = slot.login;
                    allMutedInLockMode = false;
                    for (StreamSlot other : slots) other.pinnedUnmuted = false;
                    applyAudioFocus();
                    updateTopBarState();
                    sheet.dismiss();
                }));
            }

            boolean mutedAll = lockMode && allMutedInLockMode;
            root.addView(buildChannelAudioRow(act, "🔇 すべてミュートで固定", mutedAll, () -> {
                KizuSettings.MULTI_STREAM_AUDIO_LOCK.save(true);
                allMutedInLockMode = true;
                for (StreamSlot other : slots) other.pinnedUnmuted = false;
                applyAudioFocus();
                updateTopBarState();
                sheet.dismiss();
            }));
        }

        TwitchUi.prepareBottomSheet(sheet, act, root);
        sheet.setContentView(root);
        sheet.show();
    }

    private static View buildModeRow(Context c, String titleText, String descText,
                                     boolean selected, Runnable onClick) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(TwitchUi.dp(c, 12f), TwitchUi.dp(c, 10f), TwitchUi.dp(c, 12f), TwitchUi.dp(c, 10f));
        int stroke = selected ? Math.max(2, TwitchUi.dp(c, 1.5f)) : Math.max(1, TwitchUi.dp(c, 0.75f));
        int strokeColor = selected ? SettingsUi.accentColor(c) : SettingsUi.borderColor(c);
        box.setBackground(TwitchUi.ripple(c,
                TwitchUi.roundedRect(SettingsUi.alternateBackgroundColor(c), TwitchUi.dp(c, 10f), stroke, strokeColor),
                TwitchUi.dp(c, 10f)));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> onClick.run());

        TextView title = new TextView(c);
        title.setText((selected ? "● " : "○ ") + titleText);
        title.setTextColor(selected ? SettingsUi.accentColor(c) : SettingsUi.primaryTextColor(c));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView desc = new TextView(c);
        desc.setText(descText);
        desc.setTextColor(SettingsUi.secondaryTextColor(c));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        desc.setPadding(0, TwitchUi.dp(c, 3f), 0, 0);
        box.addView(desc);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = TwitchUi.dp(c, 8f);
        box.setLayoutParams(lp);
        return box;
    }

    private static View buildChannelAudioRow(Context c, String label, boolean selected, Runnable onClick) {
        TextView row = new TextView(c);
        row.setText((selected ? "✓ " : "   ") + label);
        row.setTextColor(selected ? SettingsUi.accentColor(c) : SettingsUi.primaryTextColor(c));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        row.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        row.setPadding(TwitchUi.dp(c, 12f), TwitchUi.dp(c, 10f), TwitchUi.dp(c, 12f), TwitchUi.dp(c, 10f));
        row.setBackground(TwitchUi.selectableBackground(c));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int findSlotByLogin(String login) {
        if (login == null) return -1;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).login.equals(login)) return i;
        }
        return -1;
    }

    private void openAddChannelPicker() {
        if (dismissed || slots.size() >= MAX_SLOTS) return;
        Activity act = activityRef.get();
        if (act == null) return;
        Set<String> active = new HashSet<>();
        for (StreamSlot s : slots) active.add(s.login);
        ChannelPickerSheet.show(act, "マルチ視聴に配信を追加 (" + slots.size() + "/" + MAX_SLOTS + ")",
                active, (login, displayName) -> {
                    addSlotInternal(login, displayName, true);
                    showHud();
                });
    }

    private void addSlotInternal(String rawLogin, String rawDisplay, boolean autoFocusIfFirst) {
        if (dismissed || rawLogin == null) return;
        String login = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (login.isEmpty() || slots.size() >= MAX_SLOTS) return;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).login.equals(login)) {
                setFocus(i);
                return;
            }
        }
        Activity act = activityRef.get();
        if (act == null) return;
        String display = rawDisplay != null && !rawDisplay.isEmpty() ? rawDisplay : login;
        StreamSlot slot = new StreamSlot(act, login, display);
        slots.add(slot);
        videoArea.addView(slot.container, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        emptyHint.setVisibility(View.GONE);

        if (lockedAudioLogin == null) lockedAudioLogin = login;
        updateSlotTargetQualities();
        slot.loadStream(targetAutoHeightForSlot(slots.size() - 1));
        if (focusedIndex < 0 || (autoFocusIfFirst && slots.size() == 1)) {
            setFocus(slots.size() - 1);
        } else {
            applyAudioFocus();
        }
        relayoutSlots();
        updateTopBarState();
    }

    private void removeSlot(StreamSlot slot) {
        int idx = slots.indexOf(slot);
        if (idx < 0) return;
        boolean wasLocked = slot.login.equals(lockedAudioLogin);
        if (danmakuView.getParent() == slot.videoBox) {
            slot.videoBox.removeView(danmakuView);
        }
        slot.player.release();
        videoArea.removeView(slot.container);
        slots.remove(idx);

        if (slots.isEmpty()) {
            focusedIndex = -1;
            lockedAudioLogin = null;
            danmakuView.clear();
            emptyHint.setVisibility(View.VISIBLE);
            commentInput.setHint("コメントを送信...");
        } else {
            int nextFocus = Math.min(focusedIndex, slots.size() - 1);
            if (nextFocus < 0) nextFocus = 0;
            if (wasLocked) {
                lockedAudioLogin = slots.get(nextFocus).login;
            }
            focusedIndex = -1;
            setFocus(nextFocus);
        }
        updateSlotTargetQualities();
        relayoutSlots();
        updateTopBarState();
    }

    private void swapToMain(int idx) {
        if (idx <= 0 || idx >= slots.size()) return;
        StreamSlot tapped = slots.get(idx);
        StreamSlot mainSlot = slots.get(0);
        slots.set(0, tapped);
        slots.set(idx, mainSlot);
        updateSlotTargetQualities();
        setFocus(0);
        relayoutSlots();
        Activity act = activityRef.get();
        if (act != null) {
            Toast.makeText(act, tapped.displayName + " をメイン枠へ移動しました", Toast.LENGTH_SHORT).show();
        }
    }

    private void setFocus(int index) {
        if (index < 0 || index >= slots.size()) return;
        boolean channelChanged = focusedIndex != index;
        focusedIndex = index;
        StreamSlot focused = slots.get(index);
        if (!KizuSettings.MULTI_STREAM_AUDIO_LOCK.get()) {
            focused.explicitlyMuted = false;
        }

        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).setFocusedVisual(i == focusedIndex);
        }
        applyAudioFocus();
        attachDanmakuToSlot(focused);
        commentInput.setHint("#" + focused.displayName + " へコメント...");

        if (channelChanged) {
            danmakuView.clear();
            ircClient.joinChannel(focused.login);
        }
    }

    private void attachDanmakuToSlot(StreamSlot slot) {
        ViewParent p = danmakuView.getParent();
        if (p == slot.videoBox) return;
        if (p instanceof ViewGroup oldParent) {
            oldParent.removeView(danmakuView);
        }
        slot.videoBox.addView(danmakuView, new FrameLayout.LayoutParams(-1, -1));
        applyDanmakuState();
    }

    private void applyAudioFocus() {
        boolean lockMode = KizuSettings.MULTI_STREAM_AUDIO_LOCK.get();
        if (lockMode && !allMutedInLockMode
                && (lockedAudioLogin == null || findSlotByLogin(lockedAudioLogin) < 0)
                && focusedIndex >= 0 && focusedIndex < slots.size()) {
            lockedAudioLogin = slots.get(focusedIndex).login;
        }
        for (int i = 0; i < slots.size(); i++) {
            StreamSlot slot = slots.get(i);
            boolean isLockedOwner = lockMode && !allMutedInLockMode && slot.login.equals(lockedAudioLogin);
            boolean shouldUnmute;
            boolean highlightBadge;
            if (lockMode) {
                shouldUnmute = isLockedOwner || slot.pinnedUnmuted;
                highlightBadge = isLockedOwner || slot.pinnedUnmuted;
            } else {
                shouldUnmute = (i == focusedIndex) ? !slot.explicitlyMuted : slot.pinnedUnmuted;
                highlightBadge = i != focusedIndex && slot.pinnedUnmuted;
            }
            slot.player.setMuted(!shouldUnmute);
            slot.updateSpeakerButton(shouldUnmute, isLockedOwner, highlightBadge);
        }
    }

    private void applyDanmakuState() {
        danmakuView.setVisibility(danmakuEnabled ? View.VISIBLE : View.INVISIBLE);
        danmakuView.setPaused(!danmakuEnabled);
        if (!danmakuEnabled) danmakuView.clear();
    }

    private void updateSlotTargetQualities() {
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).player.setTargetMaxHeight(targetAutoHeightForSlot(i));
        }
    }

    private int targetAutoHeightForSlot(int slotIndex) {
        if (slotIndex == 0) return slots.size() >= 3 ? 720 : 0;
        return slots.size() >= 3 ? 360 : 480;
    }

    private void minimizeToPip() {
        Activity act = activityRef.get();
        if (act == null || slots.isEmpty()) {
            dismiss();
            return;
        }
        int pipIdx = slots.size() >= 2
                ? (focusedIndex > 0 ? focusedIndex : 1)
                : 0;
        StreamSlot chosen = slots.get(pipIdx);
        String login = chosen.login;
        String name = chosen.displayName;
        dismiss();
        MultiStreamManager.collapseMultiViewToPip(act, login, name);
    }

    private void sendComment() {
        String text = commentInput.getText() == null ? "" : commentInput.getText().toString().trim();
        if (text.isEmpty()) return;
        sendBtn.setEnabled(false);
        ircClient.sendMessage(text, (ok, error) -> {
            sendBtn.setEnabled(true);
            Activity act = activityRef.get();
            if (ok) {
                commentInput.setText("");
                hideKeyboard();
            } else if (error != null && act != null) {
                Toast.makeText(act, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void hideKeyboard() {
        try {
            Activity act = activityRef.get();
            if (act == null) return;
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(commentInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private boolean isLandscape() {
        return rootFrame.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void updateOrientationUi() {
        boolean landscape = isLandscape();
        int commentVis = landscape ? View.GONE : View.VISIBLE;
        if (commentBar.getVisibility() != commentVis) {
            commentBar.setVisibility(commentVis);
            if (landscape) hideKeyboard();
        }
        // Keep topBar always docked above videoArea in Portrait so it never obscures the top stream;
        // auto-hide it in Landscape when HUD hides so landscape video uses the entire safe area.
        if (!landscape && topBar.getVisibility() != View.VISIBLE) {
            topBar.setVisibility(View.VISIBLE);
        } else if (landscape) {
            topBar.setVisibility(hudVisible ? View.VISIBLE : View.GONE);
        }
        int count = slots.size();
        if (lastLayoutSlotCount != count || lastLayoutLandscape != landscape) {
            lastLayoutSlotCount = count;
            lastLayoutLandscape = landscape;
            layoutMode = 0;
            updateTopBarState();
        }
    }

    private void updateTopBarState() {
        topTitle.setText("マルチ (" + slots.size() + "/" + MAX_SLOTS + ")");
        boolean lockMode = KizuSettings.MULTI_STREAM_AUDIO_LOCK.get();
        audioModeBtn.setText(lockMode ? "🔒音声" : "🔊音声");
        audioModeBtn.setTextColor(lockMode ? 0xFFBF94FF : Color.WHITE);
        boolean canAdd = slots.size() < MAX_SLOTS;
        addBtn.setEnabled(canAdd);
        addBtn.setAlpha(canAdd ? 1f : 0.45f);
        toPipBtn.setVisibility(slots.isEmpty() ? View.GONE : View.VISIBLE);
        int variants = layoutVariantCount();
        layoutBtn.setVisibility(variants > 1 ? View.VISIBLE : View.GONE);
        layoutBtn.setText(currentLayoutLabel());
        danmakuToggleBtn.setTextColor(danmakuEnabled ? 0xFFBF94FF : 0xAAFFFFFF);
    }

    private int layoutVariantCount() {
        int count = slots.size();
        if (count <= 1) return 1;
        boolean landscape = isLandscape();
        if (!landscape) {
            if (count == 2) return 1;
            if (count == 3) return 2; // 3 full-width rows (default) <-> 1 large + 2 side-by-side
            return 3; // 4 slots: 1+1+2 (default) <-> 4 stacked rows <-> 2x2 grid
        }
        return 2; // Landscape 2/3/4 each have 2 optimal layouts
    }

    private String currentLayoutLabel() {
        int count = slots.size();
        int mode = layoutMode % Math.max(1, layoutVariantCount());
        if (!isLandscape()) {
            if (count == 3) return mode == 0 ? "⊞ 3段" : "⊞ 1+2";
            if (count == 4) {
                if (mode == 0) return "⊞ 1+1+2";
                if (mode == 1) return "⊞ 4段";
                return "⊞ 2×2";
            }
            return "⊞";
        }
        if (count == 2) return mode == 0 ? "⊞ 強調" : "⊞ 均等";
        if (count == 3) return mode == 0 ? "⊞ 1+2" : "⊞ 2+1";
        if (count == 4) return mode == 0 ? "⊞ 2×2" : "⊞ 1+3";
        return "⊞";
    }

    private void showHud() {
        hudVisible = true;
        topBar.setVisibility(View.VISIBLE);
        for (StreamSlot s : slots) s.setHudVisible(true);
        scheduleHudHide();
    }

    private void hideHud() {
        if (dismissed) return;
        hudVisible = false;
        if (isLandscape()) {
            topBar.setVisibility(View.GONE);
        }
        for (StreamSlot s : slots) s.setHudVisible(false);
    }

    private void toggleHud() {
        if (hudVisible) hideHud();
        else showHud();
    }

    private void scheduleHudHide() {
        main.removeCallbacks(hideHudRunnable);
        main.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS);
    }

    private void relayoutSlots() {
        int count = slots.size();
        int w = videoArea.getWidth();
        int h = videoArea.getHeight();
        if (count == 0 || w <= 0 || h <= 0) return;

        // Subtle unobtrusive 1dp hairline divider between slots.
        int div = Math.max(1, TwitchUi.dp(rootFrame.getContext(), 1f));
        boolean landscape = w > h;

        if (!landscape) {
            layoutPortraitTopPacked(count, w, h, div);
        } else {
            layoutLandscape(count, w, h, div);
        }
    }

    /**
     * Portrait mode: packs all stream slots tightly from top = 0 with maximum 16:9 area and a
     * subtle 1dp hairline divider between them — zero vertical blank gaps between players.
     *
     * - 1 slot: full width 16:9
     * - 2 slots: 2 full-width 16:9 stacked rows
     * - 3 slots: mode 0 = 3 large full-width stacked rows (default), mode 1 = 1 large + 2 side-by-side
     * - 4 slots: mode 0 = 2 large full-width + 2 side-by-side (1+1+2), mode 1 = 4 equal stacked rows,
     *            mode 2 = 2x2 grid
     */
    private void layoutPortraitTopPacked(int count, int w, int h, int div) {
        int mode = layoutMode % Math.max(1, layoutVariantCount());
        if (count == 1) {
            int h0 = Math.min(h, Math.round(w * 9f / 16f));
            placeSlot(0, 0, 0, w, h0);
            return;
        }
        if (count == 2) {
            int natural = Math.round(w * 9f / 16f);
            int eachH = (natural * 2 + div <= h) ? natural : Math.max(1, (h - div) / 2);
            placeSlot(0, 0, 0, w, eachH);
            placeSlot(1, 0, eachH + div, w, eachH);
            return;
        }
        if (count == 3) {
            if (mode == 0) {
                // Default: 3 large full-width stacked streams tightly packed from top.
                int natural = Math.round(w * 9f / 16f);
                int eachH = (natural * 3 + div * 2 <= h) ? natural : Math.max(1, (h - div * 2) / 3);
                placeSlot(0, 0, 0, w, eachH);
                placeSlot(1, 0, eachH + div, w, eachH);
                placeSlot(2, 0, (eachH + div) * 2, w, eachH);
            } else {
                int halfW = Math.max(1, (w - div) / 2);
                int h0 = Math.round(w * 9f / 16f);
                int h1 = Math.round(halfW * 9f / 16f);
                if (h0 + div + h1 > h) {
                    float scale = (h - div) / (float) Math.max(1, h0 + h1);
                    h0 = Math.max(1, Math.round(h0 * scale));
                    h1 = Math.max(1, h - div - h0);
                }
                placeSlot(0, 0, 0, w, h0);
                placeSlot(1, 0, h0 + div, halfW, h1);
                placeSlot(2, halfW + div, h0 + div, w - div - halfW, h1);
            }
            return;
        }
        // 4 slots
        if (mode == 0) {
            // Default: 2 large full-width rows + 2 side-by-side below (maximizes area with zero letterboxing).
            int halfW = Math.max(1, (w - div) / 2);
            int bigH = Math.round(w * 9f / 16f);
            int smallH = Math.round(halfW * 9f / 16f);
            int totalH = bigH * 2 + smallH + div * 2;
            if (totalH > h) {
                float scale = (h - div * 2) / (float) Math.max(1, bigH * 2 + smallH);
                bigH = Math.max(1, Math.round(bigH * scale));
                smallH = Math.max(1, h - div * 2 - bigH * 2);
            }
            placeSlot(0, 0, 0, w, bigH);
            placeSlot(1, 0, bigH + div, w, bigH);
            int row2Top = (bigH + div) * 2;
            placeSlot(2, 0, row2Top, halfW, smallH);
            placeSlot(3, halfW + div, row2Top, w - div - halfW, smallH);
        } else if (mode == 1) {
            // 4 stacked rows filling full vertical space.
            int natural = Math.round(w * 9f / 16f);
            int eachH = (natural * 4 + div * 3 <= h) ? natural : Math.max(1, (h - div * 3) / 4);
            for (int i = 0; i < 4; i++) {
                placeSlot(i, 0, i * (eachH + div), w, eachH);
            }
        } else {
            // 2x2 compact grid packed tightly from top.
            int halfW = Math.max(1, (w - div) / 2);
            int natural = Math.round(halfW * 9f / 16f);
            int rowH = (natural * 2 + div <= h) ? natural : Math.max(1, (h - div) / 2);
            placeSlot(0, 0, 0, halfW, rowH);
            placeSlot(1, halfW + div, 0, w - div - halfW, rowH);
            placeSlot(2, 0, rowH + div, halfW, rowH);
            placeSlot(3, halfW + div, rowH + div, w - div - halfW, rowH);
        }
    }

    /**
     * Landscape mode: solves exact 16:9 proportions for each stream count so videos pack tightly
     * with minimal wasted area and zero internal black gaps between adjacent streams.
     */
    private void layoutLandscape(int count, int w, int h, int div) {
        int mode = layoutMode % Math.max(1, layoutVariantCount());
        if (count == 1) {
            int fitW = Math.min(w, Math.round(h * 16f / 9f));
            int fitH = Math.min(h, Math.round(fitW * 9f / 16f));
            placeSlot(0, (w - fitW) / 2, (h - fitH) / 2, fitW, fitH);
            return;
        }
        if (count == 2) {
            int leftW;
            int rightW;
            if (mode == 0) {
                // Main emphasis: cap main at exact 16:9 width for height h, give rest to sub.
                int maxMainW = Math.round(h * 16f / 9f);
                leftW = Math.min(maxMainW, Math.round((w - div) * 0.67f));
                rightW = Math.min(maxMainW, w - div - leftW);
            } else {
                int halfW = Math.min(Math.round(h * 16f / 9f), (w - div) / 2);
                leftW = halfW;
                rightW = halfW;
            }
            int leftH = Math.min(h, Math.round(leftW * 9f / 16f));
            int rightH = Math.min(h, Math.round(rightW * 9f / 16f));
            int totalW = leftW + div + rightW;
            int startX = Math.max(0, (w - totalW) / 2);
            placeSlot(0, startX, (h - leftH) / 2, leftW, leftH);
            placeSlot(1, startX + leftW + div, (h - rightH) / 2, rightW, rightH);
            return;
        }
        if (count == 3) {
            if (mode == 0) {
                // Optimal 1+2: right 2 stacked take exact 16:9 width for H/2 each; left main fills the rest.
                int subHMax = Math.max(1, (h - div) / 2);
                int exactRightW = Math.round(subHMax * 16f / 9f);
                int rightW = Math.max(Math.round((w - div) * 0.30f),
                        Math.min(Math.round((w - div) * 0.44f), exactRightW));
                int leftW = Math.min(Math.round(h * 16f / 9f), Math.max(1, w - div - rightW));
                int leftH = Math.min(h, Math.round(leftW * 9f / 16f));
                int subH = Math.min(subHMax, Math.round(rightW * 9f / 16f));
                int rightBlockH = subH * 2 + div;

                int totalW = leftW + div + rightW;
                int startX = Math.max(0, (w - totalW) / 2);
                int rightTop = Math.max(0, (h - rightBlockH) / 2);
                placeSlot(0, startX, Math.max(0, (h - leftH) / 2), leftW, leftH);
                placeSlot(1, startX + leftW + div, rightTop, rightW, subH);
                placeSlot(2, startX + leftW + div, rightTop + subH + div, rightW, subH);
            } else {
                // Alternate 2+1: 2 equal streams on top row, 1 centered below (far larger than 3 narrow columns).
                int rowHMax = Math.max(1, (h - div) / 2);
                int slotW = Math.min((w - div) / 2, Math.round(rowHMax * 16f / 9f));
                int slotH = Math.min(rowHMax, Math.round(slotW * 9f / 16f));
                int topRowW = slotW * 2 + div;
                int blockH = slotH * 2 + div;
                int startX = Math.max(0, (w - topRowW) / 2);
                int startY = Math.max(0, (h - blockH) / 2);
                placeSlot(0, startX, startY, slotW, slotH);
                placeSlot(1, startX + slotW + div, startY, slotW, slotH);
                placeSlot(2, Math.max(0, (w - slotW) / 2), startY + slotH + div, slotW, slotH);
            }
            return;
        }
        // 4 slots Landscape
        if (mode == 0) {
            // Default: tightly packed 2x2 16:9 grid (maximum balanced area ~85-100%).
            int rowHMax = Math.max(1, (h - div) / 2);
            int colW = Math.min((w - div) / 2, Math.round(rowHMax * 16f / 9f));
            int rowH = Math.min(rowHMax, Math.round(colW * 9f / 16f));
            int blockW = colW * 2 + div;
            int blockH = rowH * 2 + div;
            int startX = Math.max(0, (w - blockW) / 2);
            int startY = Math.max(0, (h - blockH) / 2);
            placeSlot(0, startX, startY, colW, rowH);
            placeSlot(1, startX + colW + div, startY, colW, rowH);
            placeSlot(2, startX, startY + rowH + div, colW, rowH);
            placeSlot(3, startX + colW + div, startY + rowH + div, colW, rowH);
        } else {
            // Alternate 1+3: right 3 stacked take exact 16:9 width for H/3 each; left main fills the rest.
            int subHMax = Math.max(1, (h - div * 2) / 3);
            int exactRightW = Math.round(subHMax * 16f / 9f);
            int rightW = Math.max(Math.round((w - div) * 0.25f),
                    Math.min(Math.round((w - div) * 0.40f), exactRightW));
            int leftW = Math.min(Math.round(h * 16f / 9f), Math.max(1, w - div - rightW));
            int leftH = Math.min(h, Math.round(leftW * 9f / 16f));
            int subH = Math.min(subHMax, Math.round(rightW * 9f / 16f));
            int rightBlockH = subH * 3 + div * 2;

            int totalW = leftW + div + rightW;
            int startX = Math.max(0, (w - totalW) / 2);
            int rightTop = Math.max(0, (h - rightBlockH) / 2);
            placeSlot(0, startX, Math.max(0, (h - leftH) / 2), leftW, leftH);
            for (int i = 0; i < 3; i++) {
                placeSlot(i + 1, startX + leftW + div, rightTop + i * (subH + div), rightW, subH);
            }
        }
    }

    private void placeSlot(int index, int left, int top, int width, int height) {
        StreamSlot slot = slots.get(index);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.container.getLayoutParams();
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != safeW || lp.height != safeH) {
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.width = safeW;
            lp.height = safeH;
            slot.container.setLayoutParams(lp);
        }
        slot.fitVideoAspect16x9(safeW, safeH);
    }

    private static TextView topActionChip(Context c, String label, View.OnClickListener onClick) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(TwitchUi.dp(c, 8f), TwitchUi.dp(c, 5f), TwitchUi.dp(c, 8f), TwitchUi.dp(c, 5f));
        tv.setBackground(TwitchUi.roundedRect(0xFF26262C, TwitchUi.dp(c, 6f), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = TwitchUi.dp(c, 5f);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private final class StreamSlot {
        final String login;
        final String displayName;
        final FrameLayout container;
        final FrameLayout videoBox;
        final TextureView textureView;
        final ProgressBar loadingBar;
        final TextView errorLabel;
        final LinearLayout slotHud;
        final TextView titleBadge;
        final TextView speakerBtn;
        final MultiStreamPlayer player;

        boolean pinnedUnmuted;
        boolean explicitlyMuted;
        long lastTapAtMs;

        StreamSlot(Context c, String login, String displayName) {
            this.login = login;
            this.displayName = displayName;

            container = new FrameLayout(c);
            container.setBackgroundColor(Color.BLACK);
            container.setClickable(true);

            videoBox = new FrameLayout(c);
            videoBox.setBackgroundColor(Color.BLACK);
            textureView = new TextureView(c);
            videoBox.addView(textureView, new FrameLayout.LayoutParams(-1, -1));
            container.addView(videoBox, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

            loadingBar = new ProgressBar(c);
            int sp = TwitchUi.dp(c, 28f);
            container.addView(loadingBar, new FrameLayout.LayoutParams(sp, sp, Gravity.CENTER));

            errorLabel = new TextView(c);
            errorLabel.setTextColor(Color.WHITE);
            errorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            errorLabel.setGravity(Gravity.CENTER);
            errorLabel.setPadding(TwitchUi.dp(c, 10f), TwitchUi.dp(c, 6f),
                    TwitchUi.dp(c, 10f), TwitchUi.dp(c, 6f));
            errorLabel.setBackground(TwitchUi.roundedRect(0xCC0E0E10, TwitchUi.dp(c, 6f), 0, 0));
            errorLabel.setVisibility(View.GONE);
            errorLabel.setOnClickListener(v -> loadStream(targetAutoHeightForSlot(slots.indexOf(this))));
            container.addView(errorLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            slotHud = new LinearLayout(c);
            slotHud.setOrientation(LinearLayout.HORIZONTAL);
            slotHud.setGravity(Gravity.CENTER_VERTICAL);
            slotHud.setBackgroundColor(0x990E0E10);
            slotHud.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 4f),
                    TwitchUi.dp(c, 4f), TwitchUi.dp(c, 4f));

            titleBadge = new TextView(c);
            titleBadge.setTextColor(Color.WHITE);
            titleBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleBadge.setTypeface(Typeface.DEFAULT_BOLD);
            titleBadge.setSingleLine(true);
            titleBadge.setEllipsize(TextUtils.TruncateAt.END);
            slotHud.addView(titleBadge, new LinearLayout.LayoutParams(0, -2, 1f));

            speakerBtn = slotMiniBtn(c, "🔇", v -> {
                boolean lockMode = KizuSettings.MULTI_STREAM_AUDIO_LOCK.get();
                if (lockMode) {
                    if (login.equals(lockedAudioLogin)) {
                        allMutedInLockMode = !allMutedInLockMode;
                    } else {
                        pinnedUnmuted = !pinnedUnmuted;
                    }
                } else {
                    int idx = slots.indexOf(this);
                    if (idx == focusedIndex) {
                        explicitlyMuted = !explicitlyMuted;
                    } else {
                        pinnedUnmuted = !pinnedUnmuted;
                    }
                }
                applyAudioFocus();
                scheduleHudHide();
            });
            slotHud.addView(speakerBtn);

            TextView qualityBtn = slotMiniBtn(c, "⚙", v -> {
                showQualityDialog();
                scheduleHudHide();
            });
            slotHud.addView(qualityBtn);

            TextView reloadBtn = slotMiniBtn(c, "↻", v -> {
                loadStream(targetAutoHeightForSlot(slots.indexOf(this)));
                scheduleHudHide();
            });
            slotHud.addView(reloadBtn);

            TextView removeBtn = slotMiniBtn(c, "✕", v -> removeSlot(this));
            slotHud.addView(removeBtn);

            FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            container.addView(slotHud, hudLp);

            container.setOnClickListener(v -> {
                int idx = slots.indexOf(this);
                if (idx < 0) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAtMs <= DOUBLE_TAP_WINDOW_MS && idx > 0) {
                    lastTapAtMs = 0L;
                    swapToMain(idx);
                    showHud();
                    return;
                }
                lastTapAtMs = now;
                if (idx != focusedIndex) {
                    setFocus(idx);
                    showHud();
                } else {
                    toggleHud();
                }
            });

            player = new MultiStreamPlayer(c, new MultiStreamPlayer.Listener() {
                @Override
                public void onStateChanged(boolean loading, String errorMessage) {
                    if (dismissed) return;
                    loadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
                    if (errorMessage != null) {
                        errorLabel.setText(errorMessage + "\nタップで再読込");
                        errorLabel.setVisibility(View.VISIBLE);
                    } else {
                        errorLabel.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onQualityChanged(String qualityLabel) {
                    if (!dismissed) updateBadge();
                }
            });
            player.attachTextureView(textureView);
            updateBadge();
            setFocusedVisual(false);
            setHudVisible(hudVisible);
        }

        void loadStream(int targetAutoHeight) {
            Activity act = activityRef.get();
            if (act == null || dismissed) return;
            loadingBar.setVisibility(View.VISIBLE);
            errorLabel.setVisibility(View.GONE);
            Context appCtx = act.getApplicationContext();
            KizuRuntime.ioExecutor().execute(() -> {
                MultiStreamApi.StreamLoadResult res = MultiStreamApi.fetchStreamUri(appCtx, login);
                main.post(() -> {
                    if (dismissed || !slots.contains(this)) return;
                    if (res.playUri == null) {
                        loadingBar.setVisibility(View.GONE);
                        errorLabel.setText((res.error != null ? res.error : "読み込み失敗") + "\nタップで再読込");
                        errorLabel.setVisibility(View.VISIBLE);
                        return;
                    }
                    player.load(res.playUri, res.directUsherUri, targetAutoHeight);
                });
            });
        }

        void fitVideoAspect16x9(int cellW, int cellH) {
            if (cellW <= 0 || cellH <= 0) return;
            int innerW = Math.max(1, cellW - container.getPaddingLeft() - container.getPaddingRight());
            int innerH = Math.max(1, cellH - container.getPaddingTop() - container.getPaddingBottom());
            int fitW = innerW;
            int fitH = Math.round(innerW * 9f / 16f);
            if (fitH > innerH) {
                fitH = innerH;
                fitW = Math.round(innerH * 16f / 9f);
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoBox.getLayoutParams();
            if (lp.width != fitW || lp.height != fitH) {
                lp.width = Math.max(1, fitW);
                lp.height = Math.max(1, fitH);
                lp.gravity = Gravity.CENTER;
                videoBox.setLayoutParams(lp);
            }
        }

        void setFocusedVisual(boolean focused) {
            Context c = container.getContext();
            // Subtle unobtrusive hairline divider when unfocused; thin Twitch purple highlight when focused.
            int stroke = focused ? Math.max(2, TwitchUi.dp(c, 1.5f)) : Math.max(1, TwitchUi.dp(c, 0.75f));
            int borderColor = focused ? 0xFF9147FF : 0xFF2E2E35;
            container.setPadding(stroke, stroke, stroke, stroke);
            container.setBackground(TwitchUi.roundedRect(Color.BLACK, 0f, stroke, borderColor));
        }

        void updateBadge() {
            titleBadge.setText("● " + displayName + " · " + player.getCurrentQualityLabel());
        }

        void updateSpeakerButton(boolean unmuted, boolean lockedOwner, boolean highlight) {
            if (lockedOwner && unmuted) {
                speakerBtn.setText("🔒🔊");
            } else {
                speakerBtn.setText(unmuted ? "🔊" : "🔇");
            }
            speakerBtn.setBackground(TwitchUi.roundedRect(
                    highlight ? 0xCC9147FF : 0x9926262C,
                    TwitchUi.dp(speakerBtn.getContext(), 4f), 0, 0));
        }

        void setHudVisible(boolean visible) {
            slotHud.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        private void showQualityDialog() {
            Activity act = activityRef.get();
            if (act == null || dismissed) return;
            List<String> labels = player.getQualityLabels();
            if (labels.isEmpty()) return;
            SettingsUi.dialog(act)
                    .setTitle(displayName + " の画質")
                    .setItems(labels.toArray(new String[0]), (d, which) -> player.selectQuality(which))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private TextView slotMiniBtn(Context c, String label, View.OnClickListener onClick) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(TwitchUi.dp(c, 6f), TwitchUi.dp(c, 3f), TwitchUi.dp(c, 6f), TwitchUi.dp(c, 3f));
            tv.setBackground(TwitchUi.roundedRect(0x9926262C, TwitchUi.dp(c, 4f), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = TwitchUi.dp(c, 4f);
            tv.setLayoutParams(lp);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setOnClickListener(onClick);
            return tv;
        }
    }
}
