package app.morphe.extension.settings;

import android.content.SharedPreferences;

public final class BooleanSetting extends Setting<Boolean> {
    private volatile boolean loaded;
    private volatile boolean cached;

    public BooleanSetting(String key, boolean defaultValue) {
        super(key, defaultValue);
        this.cached = defaultValue;
    }

    @Override
    public Boolean get() {
        if ("emotes_animated".equals(key)) return true;
        if (loaded) return cached;
        SharedPreferences preferences = preferences();
        if (preferences == null) return defaultValue;
        boolean value;
        try {
            value = preferences.getBoolean(key, defaultValue);
        } catch (ClassCastException ex) {
            value = defaultValue;
        }
        cached = value;
        loaded = true;
        return value;
    }

    @Override
    public void save(Boolean value) {
        boolean effective = "emotes_animated".equals(key) || Boolean.TRUE.equals(value);
        cached = effective;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        loaded = true;
        preferences.edit().putBoolean(key, effective).apply();
    }

    @Override
    protected void invalidate() {
        loaded = false;
    }
}
