package app.morphe.extension.twitch.emotes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.KizuRuntime;

final class EmoteProviderIcons {
    private static final String[] NAMES = {"bttv", "7tv", "ffz"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<ImageView, String> views = new WeakHashMap<>();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> failedUntil = new ConcurrentHashMap<>();
    private static final LruCache<String, Bitmap> cache = new LruCache<>(256 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private EmoteProviderIcons() {}

    static void bind(ImageView view, int provider) {
        String url = "https://cdn.betterttv.net/assets/logos/" + NAMES[provider] + "_logo.png";
        views.put(view, url);
        Bitmap bmp = cache.get(url);
        if (bmp != null) {
            view.setImageDrawable(new BitmapDrawable(view.getResources(), bmp));
            return;
        }
        view.setImageDrawable(new Label(NAMES[provider].toUpperCase(Locale.ROOT)));
        Long retryAfter = failedUntil.get(url);
        if (retryAfter != null && retryAfter > System.currentTimeMillis()) return;
        if (!loading.add(url)) return;

        KizuRuntime.ioExecutor().execute(() -> {
            Bitmap loaded = null;
            try {
                byte[] bytes = EmoteHttp.get(url, 512 * 1024, false);
                loaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Throwable ignored) {}
            final Bitmap finalBmp = loaded;
            loading.remove(url);
            if (finalBmp == null) {
                failedUntil.put(url, System.currentTimeMillis() + 60_000L);
                return;
            }
            cache.put(url, finalBmp);
            failedUntil.remove(url);
            MAIN.post(() -> views.forEach((v, expected) -> {
                if (url.equals(expected) && v.isAttachedToWindow()) {
                    v.setImageDrawable(new BitmapDrawable(v.getResources(), finalBmp));
                }
            }));
        });
    }

    private static final class Label extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;

        Label(String label) {
            this.label = label;
            paint.setColor(Color.GRAY);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override public void draw(Canvas canvas) {
            paint.setTextSize(getBounds().width() / 3f);
            canvas.drawText(label, getBounds().exactCenterX(),
                    getBounds().exactCenterY() - (paint.ascent() + paint.descent()) / 2f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
