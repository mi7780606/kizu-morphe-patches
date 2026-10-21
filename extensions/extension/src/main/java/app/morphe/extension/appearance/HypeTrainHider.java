package app.morphe.extension.appearance;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ハイプトレインの表示を消す。
 * 画面の部品のうち、リソースID名に KEYWORDS を含むものを GONE にする。
 * Twitch が表示し直しても、画面が更新されるたびに消し直す。
 */
@SuppressWarnings("unused")
public final class HypeTrainHider {
    private static final String TAG = "KizuHypeTrain";
    /** false にするとハイプトレインを表示する */
    public static final boolean ENABLED = true;
    /** ID名にこれを含む部品を消す */
    private static final String[] KEYWORDS = {"hype_train", "hypetrain"};
    /** 画面更新から何ms後に探すか（まとめて1回だけ探す） */
    private static final long SCAN_DELAY_MS = 300;

    private static final WeakHashMap<View, Scanner> SCANNERS = new WeakHashMap<>();
    private static final Map<Integer, Boolean> ID_CACHE = new HashMap<>();

    private HypeTrainHider() {
    }

    /** Utils の Activity 開始時に呼ばれる */
    public static void onActivityStarted(Activity activity) {
        if (!ENABLED || activity == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            Scanner scanner = SCANNERS.get(decor);
            if (scanner == null) {
                scanner = new Scanner(decor);
                SCANNERS.put(decor, scanner);
                decor.getViewTreeObserver().addOnGlobalLayoutListener(scanner);
            }
            scanner.schedule();
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private static boolean isHypeTrainId(Resources res, int id) {
        Boolean cached = ID_CACHE.get(id);
        if (cached != null) return cached;
        boolean match = false;
        try {
            String name = res.getResourceEntryName(id).toLowerCase(Locale.ROOT);
            for (String keyword : KEYWORDS) {
                if (name.contains(keyword)) {
                    match = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 実行時に作られたID（名前なし）
        }
        ID_CACHE.put(id, match);
        return match;
    }

    private static void hideIn(View view, Resources res) {
        int id = view.getId();
        if (id != View.NO_ID && isHypeTrainId(res, id)) {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                Log.i(TAG, "hid " + safeName(res, id));
            }
            return; // 中身は見なくてよい
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) hideIn(group.getChildAt(i), res);
        }
    }

    private static String safeName(Resources res, int id) {
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return String.valueOf(id);
        }
    }

    private static final class Scanner implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        private final View root;
        private boolean pending;

        Scanner(View root) {
            this.root = root;
        }

        void schedule() {
            if (pending) return;
            pending = true;
            root.postDelayed(this, SCAN_DELAY_MS);
        }

        @Override
        public void onGlobalLayout() {
            schedule();
        }

        @Override
        public void run() {
            pending = false;
            try {
                hideIn(root, root.getResources());
            } catch (Throwable t) {
                Log.e(TAG, "scan failed", t);
            }
        }
    }
}
