package app.morphe.extension.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.RelativeLayout;

/** PurpleTV-style landscape brightness/volume gestures on Twitch's existing player wrapper. */
public final class GesturePlayerWrapper extends RelativeLayout {
    private final PlayerGestureController controller;

    public GesturePlayerWrapper(Context context) { this(context, null); }
    public GesturePlayerWrapper(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public GesturePlayerWrapper(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        controller = new PlayerGestureController(this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        controller.onFinishInflate("player_overlay_container");
    }

    /** Keep Twitch's nested player from disabling interception while collapse prevention is on. */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (controller.shouldSuppressDisallowIntercept()) {
            super.requestDisallowInterceptTouchEvent(false);
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return controller.onInterceptTouchEvent(event);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        return controller.onTouchEvent(event);
    }
}
