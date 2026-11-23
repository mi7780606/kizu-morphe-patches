package app.morphe.extension.settings;

import android.content.SharedPreferences;

public final class IntSetting extends Setting<Integer> {
    public final int min;
    public final int max;

    private volatile boolean loaded;
    private volatile int cached;

    /**
     * @param min Smallest valid value. Stored values outside [min, max] read as the default.
     * @param max Largest valid value.
     */
    public IntSetting(String key, int defaultValue, int min, int max) {
        super(key, defaultValue);
        this.min = min;
        this.max = max;
        this.cached = defaultValue;
    }

    /**
     * A setting that can take any int, such as an ARGB color.
     */
    public IntSetting(String key, int defaultValue) {
        this(key, defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public Integer get() {
        if (loaded) return cached;
        SharedPreferences preferences = preferences();
        if (preferences == null) return defaultValue;
        int resolved;
        try {
            int value = preferences.getInt(key, defaultValue);
            resolved = value < min || value > max ? defaultValue : value;
        } catch (ClassCastException ex) {
            resolved = defaultValue;
        }
        cached = resolved;
        loaded = true;
        return resolved;
    }

    @Override
    public void save(Integer value) {
        if (value == null) return;
        int raw = value;
        cached = raw < min || raw > max ? defaultValue : raw;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        loaded = true;
        preferences.edit().putInt(key, raw).apply();
    }

    @Override
    protected void invalidate() {
        loaded = false;
    }
}
