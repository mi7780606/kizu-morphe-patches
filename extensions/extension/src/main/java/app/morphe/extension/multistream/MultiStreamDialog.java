package app.morphe.extension.multistream;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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

/**
 * Mode B: Dedicated fullscreen Multi-View supporting up to 4 simultaneous live streams.
 *
 * - Landscape: full-bleed multi-stream grid + proportional Danmaku on the focused stream (no chat input).
 * - Portrait: multi-stream grid + proportional Danmaku on the focused stream + compact comment input bar only.
 * - Audio: tapping a slot focuses it (unmuted, others auto-muted); each slot's speaker button allows
 *   independent simultaneous unmuting; double-tapping a slot swaps it with Slot 0 (main).
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
    private final FrameLayout videoArea;
    private final LinearLayout commentBar;
    private final EditText commentInput;
    private final TextView sendBtn;
    private final LinearLayout topBar;
    private final TextView topTitle;
    private final TextView layoutBtn;
    private final TextView danmakuToggleBtn;
    private final TextView addBtn;
    private final TextView toPipBtn;
    private final TextView emptyHint;

    private final DanmakuView danmakuView;
    private final MultiStreamIrcClient ircClient;
    private final Runnable hideHudRunnable = this::hideHud;

    private int focusedIndex = -1;
    private boolean emphasisLayout = true;
    private boolean danmakuEnabled = true;
    private boolean hudVisible = true;
    private boolean dismissed;

    MultiStreamDialog(Activity activity, List<String> initialLogins) {
        this.activityRef = new WeakReference<>(activity);
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        Window w = dialog.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(0xFF0E0E10);

        LinearLayout mainColumn = new LinearLayout(activity);
        mainColumn.setOrientation(LinearLayout.VERTICAL);

        videoArea = new FrameLayout(activity);
        videoArea.setBackgroundColor(0xFF0E0E10);
        videoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!dismissed) {
                updateOrientationUi();
                relayoutSlots();
            }
        });

        emptyHint = new TextView(activity);
        emptyHint.setText("右上の「＋追加」から視聴する配信を選んでください（最大4枠）");
        emptyHint.setTextColor(0xFFADADB8);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(TwitchUi.dp(activity, 24f), 0, TwitchUi.dp(activity, 24f), 0);
        videoArea.addView(emptyHint, new FrameLayout.LayoutParams(-1, -1));

        mainColumn.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Portrait-only compact comment input bar (no scrolling chat list; hidden completely in Landscape).
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

        // Floating auto-hiding top control bar
        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xCC0E0E10);
        topBar.setPadding(TwitchUi.dp(activity, 10f), TwitchUi.dp(activity, 8f),
                TwitchUi.dp(activity, 10f), TwitchUi.dp(activity, 8f));

        TextView backBtn = topActionChip(activity, "←", v -> dismiss());
        topBar.addView(backBtn);

        topTitle = new TextView(activity);
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setPadding(TwitchUi.dp(activity, 8f), 0, TwitchUi.dp(activity, 6f), 0);
        topBar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        toPipBtn = topActionChip(activity, "小窓", v -> minimizeToPip());
        topBar.addView(toPipBtn);

        layoutBtn = topActionChip(activity, "⊞", v -> {
            emphasisLayout = !emphasisLayout;
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

        addBtn = topActionChip(activity, "＋追加", v -> openAddChannelPicker());
        addBtn.setBackground(TwitchUi.roundedRect(0xFF9147FF, TwitchUi.dp(activity, 6f), 0, 0));
        topBar.addView(addBtn);

        rootFrame.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Shared proportional DanmakuView attached dynamically onto the currently focused slot's 16:9 video box.
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
            setFocus(0);
        }
        updateOrientationUi();
        updateTopBarState();
        applyDanmakuState();
    }

    void show(boolean promptAddIfUnderTwo) {
        if (dismissed) return;
        dialog.show();
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
        if (danmakuView.getParent() == slot.videoBox) {
            slot.videoBox.removeView(danmakuView);
        }
        slot.player.release();
        videoArea.removeView(slot.container);
        slots.remove(idx);

        if (slots.isEmpty()) {
            focusedIndex = -1;
            danmakuView.clear();
            emptyHint.setVisibility(View.VISIBLE);
            commentInput.setHint("コメントを送信...");
        } else {
            int nextFocus = Math.min(focusedIndex, slots.size() - 1);
            if (nextFocus < 0) nextFocus = 0;
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
        focused.explicitlyMuted = false;

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
        for (int i = 0; i < slots.size(); i++) {
            StreamSlot slot = slots.get(i);
            boolean shouldUnmute = (i == focusedIndex)
                    ? !slot.explicitlyMuted
                    : slot.pinnedUnmuted;
            slot.player.setMuted(!shouldUnmute);
            slot.updateSpeakerButton(shouldUnmute, i != focusedIndex && slot.pinnedUnmuted);
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
        if (slotIndex == 0) return slots.size() >= 3 ? 720 : 0; // 0 = Auto ABR
        return slots.size() >= 3 ? 360 : 480;
    }

    private void minimizeToPip() {
        Activity act = activityRef.get();
        if (act == null || slots.isEmpty()) {
            dismiss();
            return;
        }
        // Pick a sub-slot if >= 2 streams are active; otherwise use the focused slot.
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

    private void updateOrientationUi() {
        boolean landscape = rootFrame.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int want = landscape ? View.GONE : View.VISIBLE;
        if (commentBar.getVisibility() != want) {
            commentBar.setVisibility(want);
            if (landscape) hideKeyboard();
        }
    }

    private void updateTopBarState() {
        topTitle.setText("マルチ視聴 (" + slots.size() + "/" + MAX_SLOTS + ")");
        boolean canAdd = slots.size() < MAX_SLOTS;
        addBtn.setEnabled(canAdd);
        addBtn.setAlpha(canAdd ? 1f : 0.45f);
        toPipBtn.setVisibility(slots.isEmpty() ? View.GONE : View.VISIBLE);
        layoutBtn.setVisibility(slots.size() >= 2 ? View.VISIBLE : View.GONE);
        layoutBtn.setText(emphasisLayout ? "⊞ 均等" : "⊞ 強調");
        danmakuToggleBtn.setTextColor(danmakuEnabled ? 0xFFBF94FF : 0xAAFFFFFF);
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
        topBar.setVisibility(View.GONE);
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

        int gap = TwitchUi.dp(rootFrame.getContext(), 2f);
        boolean landscape = w > h;

        if (count == 1) {
            placeSlot(0, 0, 0, w, h);
        } else if (count == 2) {
            if (landscape) {
                int leftW = emphasisLayout ? Math.round((w - gap) * 0.66f) : (w - gap) / 2;
                int rightW = w - gap - leftW;
                placeSlot(0, 0, 0, leftW, h);
                placeSlot(1, leftW + gap, 0, rightW, h);
            } else {
                int topH = emphasisLayout ? Math.round((h - gap) * 0.56f) : (h - gap) / 2;
                int botH = h - gap - topH;
                placeSlot(0, 0, 0, w, topH);
                placeSlot(1, 0, topH + gap, w, botH);
            }
        } else if (count == 3) {
            if (landscape) {
                if (emphasisLayout) {
                    int leftW = Math.round((w - gap) * 0.64f);
                    int rightW = w - gap - leftW;
                    int halfH = (h - gap) / 2;
                    placeSlot(0, 0, 0, leftW, h);
                    placeSlot(1, leftW + gap, 0, rightW, halfH);
                    placeSlot(2, leftW + gap, halfH + gap, rightW, h - gap - halfH);
                } else {
                    int colW = (w - gap * 2) / 3;
                    placeSlot(0, 0, 0, colW, h);
                    placeSlot(1, colW + gap, 0, colW, h);
                    placeSlot(2, (colW + gap) * 2, 0, w - (colW + gap) * 2, h);
                }
            } else {
                int topH = Math.round((h - gap) * 0.54f);
                int botH = h - gap - topH;
                int halfW = (w - gap) / 2;
                placeSlot(0, 0, 0, w, topH);
                placeSlot(1, 0, topH + gap, halfW, botH);
                placeSlot(2, halfW + gap, topH + gap, w - gap - halfW, botH);
            }
        } else {
            // 4 slots
            if (landscape) {
                if (emphasisLayout) {
                    int leftW = Math.round((w - gap) * 0.64f);
                    int rightW = w - gap - leftW;
                    int subH = (h - gap * 2) / 3;
                    placeSlot(0, 0, 0, leftW, h);
                    placeSlot(1, leftW + gap, 0, rightW, subH);
                    placeSlot(2, leftW + gap, subH + gap, rightW, subH);
                    placeSlot(3, leftW + gap, (subH + gap) * 2, rightW, h - (subH + gap) * 2);
                } else {
                    int halfW = (w - gap) / 2;
                    int halfH = (h - gap) / 2;
                    placeSlot(0, 0, 0, halfW, halfH);
                    placeSlot(1, halfW + gap, 0, w - gap - halfW, halfH);
                    placeSlot(2, 0, halfH + gap, halfW, h - gap - halfH);
                    placeSlot(3, halfW + gap, halfH + gap, w - gap - halfW, h - gap - halfH);
                }
            } else {
                if (emphasisLayout) {
                    int topH = Math.round((h - gap) * 0.55f);
                    int botH = h - gap - topH;
                    int colW = (w - gap * 2) / 3;
                    placeSlot(0, 0, 0, w, topH);
                    placeSlot(1, 0, topH + gap, colW, botH);
                    placeSlot(2, colW + gap, topH + gap, colW, botH);
                    placeSlot(3, (colW + gap) * 2, topH + gap, w - (colW + gap) * 2, botH);
                } else {
                    int halfW = (w - gap) / 2;
                    int halfH = (h - gap) / 2;
                    placeSlot(0, 0, 0, halfW, halfH);
                    placeSlot(1, halfW + gap, 0, w - gap - halfW, halfH);
                    placeSlot(2, 0, halfH + gap, halfW, h - gap - halfH);
                    placeSlot(3, halfW + gap, halfH + gap, w - gap - halfW, h - gap - halfH);
                }
            }
        }
    }

    private void placeSlot(int index, int left, int top, int width, int height) {
        StreamSlot slot = slots.get(index);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.container.getLayoutParams();
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != width || lp.height != height) {
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.width = Math.max(1, width);
            lp.height = Math.max(1, height);
            slot.container.setLayoutParams(lp);
        }
        slot.fitVideoAspect16x9(width, height);
    }

    private static TextView topActionChip(Context c, String label, View.OnClickListener onClick) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(TwitchUi.dp(c, 9f), TwitchUi.dp(c, 5f), TwitchUi.dp(c, 9f), TwitchUi.dp(c, 5f));
        tv.setBackground(TwitchUi.roundedRect(0xFF26262C, TwitchUi.dp(c, 6f), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = TwitchUi.dp(c, 6f);
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

            // Per-slot HUD overlay
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
                int idx = slots.indexOf(this);
                if (idx == focusedIndex) {
                    explicitlyMuted = !explicitlyMuted;
                } else {
                    pinnedUnmuted = !pinnedUnmuted;
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
            int fitW = cellW;
            int fitH = Math.round(cellW * 9f / 16f);
            if (fitH > cellH) {
                fitH = cellH;
                fitW = Math.round(cellH * 16f / 9f);
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
            int stroke = focused ? Math.max(2, TwitchUi.dp(c, 2f)) : Math.max(1, TwitchUi.dp(c, 0.75f));
            int borderColor = focused ? 0xFF9147FF : 0xFF26262C;
            container.setPadding(stroke, stroke, stroke, stroke);
            container.setBackground(TwitchUi.roundedRect(Color.BLACK, 0f, stroke, borderColor));
        }

        void updateBadge() {
            titleBadge.setText("● " + displayName + " · " + player.getCurrentQualityLabel());
        }

        void updateSpeakerButton(boolean unmuted, boolean pinnedSimultaneous) {
            speakerBtn.setText(unmuted ? "🔊" : "🔇");
            speakerBtn.setBackground(TwitchUi.roundedRect(
                    pinnedSimultaneous ? 0xCC9147FF : 0x9926262C,
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
