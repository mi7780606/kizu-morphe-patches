package app.morphe.extension;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.util.WeakHashMap;

import app.morphe.extension.appearance.BottomCreateButtonHider;
import app.morphe.extension.appearance.HypeTrainHider;
import app.morphe.extension.appearance.KizuUiTweaks;
import app.morphe.extension.channelpoints.ChannelPoints;
import app.morphe.extension.channelpoints.PredictionBetter;
import app.morphe.extension.settings.SettingsUi;
import app.morphe.extension.settings.TwitchUi;
import app.morphe.extension.twitch.emotes.EmoteSupport;
import io.github.bakwudo.uyu.extension.danmaku.LandscapeChatPatch;
import io.github.bakwudo.uyu.extension.settings.KizuSettings;

public final class Utils {
    private static final String TAG = "kizu";
    private static final int STATUS_DURATION_MS = 2_000;
    private static final WeakHashMap<Activity, View> STATUS_MESSAGES = new WeakHashMap<>();

    @SuppressLint("StaticFieldLeak")
    private static volatile Context context;
    private static volatile Activity currentActivity;
    private static volatile Application registeredApplication;
    private static volatile int startedActivities;

    private static final Application.ActivityLifecycleCallbacks ACTIVITY_CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {
                    startedActivities++;
                    currentActivity = activity;
                    try { DefaultFollowing.onActivityStarted(activity); } catch (Throwable ignored) {}
                    try { LandscapeChatPatch.onActivityStarted(activity); } catch (Throwable ignored) {}
                    try { HypeTrainHider.onActivityStarted(activity); } catch (Throwable ignored) {}
                    try { KizuUiTweaks.onActivityStarted(activity); } catch (Throwable ignored) {}
                    try { BottomCreateButtonHider.onActivityStarted(activity); } catch (Throwable ignored) {}
                }
                @Override public void onActivityResumed(Activity activity) {
                    currentActivity = activity;
                    SettingsUi.invalidateThemeCache();
                }
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {
                    if (startedActivities > 0) startedActivities--;
                }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    synchronized (STATUS_MESSAGES) { STATUS_MESSAGES.remove(activity); }
                    if (currentActivity == activity) currentActivity = null;
                }
            };

    private Utils() {}

    public static void setContext(Context appContext) {
        context = appContext;
        io.github.bakwudo.uyu.extension.Utils.setContext(appContext);
        EmoteSupport.init(appContext);
        ChannelPoints.start(appContext);
        try { PredictionBetter.start(appContext); } catch (Throwable ignored) {}
        try {
            Context applicationContext = appContext == null ? null : appContext.getApplicationContext();
            if (applicationContext instanceof Application application) {
                if (registeredApplication != application) {
                    if (registeredApplication != null) {
                        registeredApplication.unregisterActivityLifecycleCallbacks(ACTIVITY_CALLBACKS);
                    }
                    registeredApplication = application;
                    application.registerActivityLifecycleCallbacks(ACTIVITY_CALLBACKS);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isAppInForeground() {
        return startedActivities > 0;
    }

    public static void showClaimStatus(final String message) {
        try {
            if (KizuSettings.HIDE_CLAIM_POPUP.get()) {
                Log.i(TAG, "claim status (hidden): " + message);
                return;
            }
        } catch (Throwable ignored) {}
        final Activity activity = currentActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        TwitchUi.showSnackbar(activity, message, STATUS_MESSAGES, STATUS_DURATION_MS);
    }

    public static Context getContext() { return context; }

    public static Activity getCurrentActivity() {
        Activity activity = currentActivity;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) return activity;
        return findActivity(context);
    }

    @SuppressLint("DiscouragedApi")
    public static int getResourceId(Context context, String name, String type) {
        return io.github.bakwudo.uyu.extension.Utils.getResourceId(context, name, type);
    }

    public static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity activity) return activity;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static void logInfo(String message) { Log.i(TAG, message); }
    public static void logError(String message, Throwable throwable) { Log.e(TAG, message, throwable); }
}
