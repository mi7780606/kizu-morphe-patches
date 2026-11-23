package app.morphe.extension.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.bakwudo.uyu.extension.Utils;

/**
 * A value stored in uyu's own SharedPreferences file. Values are cached in memory and
 * automatically synchronized via {@link SharedPreferences.OnSharedPreferenceChangeListener}.
 */
public abstract class Setting<T> {
    public static final String PREFERENCES_NAME = "uyu_settings";

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Setting<?>>> INSTANCES =
            new ConcurrentHashMap<>();
    private static volatile SharedPreferences cachedPreferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener CACHE_LISTENER =
            (prefs, changedKey) -> {
                if (changedKey == null) {
                    for (CopyOnWriteArrayList<Setting<?>> list : INSTANCES.values()) {
                        for (Setting<?> setting : list) {
                            setting.invalidate();
                        }
                    }
                    return;
                }
                CopyOnWriteArrayList<Setting<?>> list = INSTANCES.get(changedKey);
                if (list != null) {
                    for (Setting<?> setting : list) {
                        setting.invalidate();
                    }
                }
            };

    public final String key;
    public final T defaultValue;

    protected Setting(String key, T defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        INSTANCES.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(this);
    }

    public abstract T get();

    public abstract void save(T value);

    protected abstract void invalidate();

    /**
     * Calls the listener whenever an uyu setting changes. SharedPreferences keeps only a weak
     * reference to the listener, so the caller must hold on to it.
     */
    public static void addChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public static void removeChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /**
     * @return The preferences, or null if called before the app context is known.
     */
    protected static SharedPreferences preferences() {
        SharedPreferences existing = cachedPreferences;
        if (existing != null) return existing;
        Context context = Utils.getContext();
        if (context == null) return null;
        Context appContext = context.getApplicationContext();
        if (appContext != null) context = appContext;
        synchronized (Setting.class) {
            if (cachedPreferences == null) {
                SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
                if (prefs != null) {
                    prefs.registerOnSharedPreferenceChangeListener(CACHE_LISTENER);
                    cachedPreferences = prefs;
                }
            }
            return cachedPreferences;
        }
    }
}
