package app.morphe.extension.settings;

import android.content.SharedPreferences;

public final class StringSetting extends Setting<String> {
    private volatile boolean loaded;
    private volatile String cached;

    public StringSetting(String key, String defaultValue) {
        super(key, defaultValue);
        this.cached = defaultValue;
    }

    @Override
    public String get() {
        if (loaded) return cached;
        SharedPreferences preferences = preferences();
        if (preferences == null) return defaultValue;
        String value;
        try {
            value = preferences.getString(key, defaultValue);
        } catch (ClassCastException ex) {
            value = defaultValue;
        }
        cached = value;
        loaded = true;
        return value;
    }

    @Override
    public void save(String value) {
        cached = value;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        loaded = true;
        preferences.edit().putString(key, value).apply();
    }

    @Override
    protected void invalidate() {
        loaded = false;
    }
}
