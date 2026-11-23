package app.morphe.extension.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.bakwudo.uyu.extension.Utils;

/**
 * Theme helpers for Kizu-owned UI.
 *
 * Twitch owns the application theme. These helpers resolve and cache that existing theme for
 * extension-owned preference/picker/overlay surfaces without repeated reflection or resource lookups.
 */
public final class SettingsUi {
    private static final int FALLBACK_LIGHT = 0xFFF7F7F8;
    private static final int FALLBACK_DARK = 0xFF0E0E10;
    private static final int FALLBACK_SURFACE_LIGHT = Color.WHITE;
    private static final int FALLBACK_SURFACE_DARK = 0xFF18181B;
    private static final int FALLBACK_ALT_LIGHT = 0xFFEFEFF1;
    private static final int FALLBACK_ALT_DARK = 0xFF26262C;
    private static final int FALLBACK_INPUT_LIGHT = 0xFFF2F2F3;
    private static final int FALLBACK_INPUT_DARK = 0xFF1F1F23;
    private static final int FALLBACK_BORDER_LIGHT = 0xFFDEDEE3;
    private static final int FALLBACK_BORDER_DARK = 0xFF2F2F35;
    private static final int FALLBACK_TEXT_LIGHT = 0xFF0E0E10;
    private static final int FALLBACK_TEXT_DARK = 0xFFEFEFF1;
    private static final int FALLBACK_SECONDARY_LIGHT = 0xFF53535F;
    private static final int FALLBACK_SECONDARY_DARK = 0xFFADADB8;
    private static final int ACCENT_LIGHT = 0xFF9147FF;
    private static final int ACCENT_DARK = 0xFFA970FF;
    private static final int ACCENT_STRONG_LIGHT = 0xFF772CE8;
    private static final int ACCENT_STRONG_DARK = 0xFF9147FF;
    private static final int LIST_TAG_KEY = 0x7F7E0010;

    private static volatile boolean themeManagerChecked;
    private static volatile Object themeManagerCompanion;
    private static volatile Method themeManagerIsNight;

    private static volatile Boolean cachedDark;
    private static volatile boolean colorCacheDark;
    private static volatile boolean colorsInitialized;
    private static volatile int cachedBg;
    private static volatile int cachedSurface;
    private static volatile int cachedAlt;
    private static volatile int cachedPrimaryText;
    private static volatile int cachedSecondaryText;

    private static volatile SharedPreferences defaultPrefs;
    private static final SharedPreferences.OnSharedPreferenceChangeListener DEFAULT_PREFS_LISTENER =
            (prefs, key) -> {
                if (key == null || "user_theme".equals(key)) {
                    invalidateThemeCache();
                }
            };

    private SettingsUi() {}

    public static void invalidateThemeCache() {
        cachedDark = null;
        colorsInitialized = false;
    }

    public static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * Resolve Twitch's actual theme state rather than Android's system night-mode flag.
     */
    public static boolean isDark(Context context) {
        Boolean cached = cachedDark;
        if (cached != null) return cached;

        Context themeContext = themeResolutionContext(context);
        registerDefaultThemeListener(themeContext);

        boolean resolved;
        Boolean userTheme = twitchUserTheme(themeContext);
        if (userTheme != null) {
            resolved = userTheme;
        } else {
            Boolean twitch = twitchNightMode(themeContext);
            if (twitch != null) {
                resolved = twitch;
            } else {
                int raw = twitchColor(themeContext, "background_body");
                if (raw != Integer.MIN_VALUE) {
                    resolved = luminance(raw) < 128d;
                } else {
                    resolved = resolveAttrDark(themeContext);
                }
            }
        }
        cachedDark = resolved;
        return resolved;
    }

    private static boolean resolveAttrDark(Context themeContext) {
        TypedValue value = new TypedValue();
        if (themeContext != null && themeContext.getTheme().resolveAttribute(
                android.R.attr.colorBackground, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return luminance(value.data) < 128d;
            }
        }
        return false;
    }

    private static void ensureColorCache(Context context) {
        boolean dark = isDark(context);
        if (colorsInitialized && colorCacheDark == dark) return;

        int rawBg = twitchColor(context, "background_body");
        cachedBg = colorMatchesTheme(rawBg, dark) ? rawBg : (dark ? FALLBACK_DARK : FALLBACK_LIGHT);

        int rawSurface = twitchColor(context, "background_base");
        cachedSurface = colorMatchesTheme(rawSurface, dark)
                ? rawSurface
                : (dark ? FALLBACK_SURFACE_DARK : FALLBACK_SURFACE_LIGHT);

        int rawAlt = twitchColor(context, "background_alt");
        cachedAlt = colorMatchesTheme(rawAlt, dark)
                ? rawAlt
                : (dark ? FALLBACK_ALT_DARK : FALLBACK_ALT_LIGHT);

        int rawPrimary = twitchColor(context, "text_base");
        cachedPrimaryText = textMatchesTheme(rawPrimary, dark)
                ? rawPrimary
                : (dark ? FALLBACK_TEXT_DARK : FALLBACK_TEXT_LIGHT);

        int rawSecondary = twitchColor(context, "text_alt_2");
        if (rawSecondary == Integer.MIN_VALUE) rawSecondary = twitchColor(context, "text_alt");
        cachedSecondaryText = textMatchesTheme(rawSecondary, dark)
                ? rawSecondary
                : (dark ? FALLBACK_SECONDARY_DARK : FALLBACK_SECONDARY_LIGHT);

        colorCacheDark = dark;
        colorsInitialized = true;
    }

    public static int backgroundColor(Context context) {
        ensureColorCache(context);
        return cachedBg;
    }

    public static int surfaceColor(Context context) {
        ensureColorCache(context);
        return cachedSurface;
    }

    public static int alternateBackgroundColor(Context context) {
        ensureColorCache(context);
        return cachedAlt;
    }

    public static int primaryTextColor(Context context) {
        ensureColorCache(context);
        return cachedPrimaryText;
    }

    public static int secondaryTextColor(Context context) {
        ensureColorCache(context);
        return cachedSecondaryText;
    }

    public static int accentColor(Context context) {
        return isDark(context) ? ACCENT_DARK : ACCENT_LIGHT;
    }

    public static int accentStrongColor(Context context) {
        return isDark(context) ? ACCENT_STRONG_DARK : ACCENT_STRONG_LIGHT;
    }

    public static int inputBackgroundColor(Context context) {
        return isDark(context) ? FALLBACK_INPUT_DARK : FALLBACK_INPUT_LIGHT;
    }

    public static int borderColor(Context context) {
        return isDark(context) ? FALLBACK_BORDER_DARK : FALLBACK_BORDER_LIGHT;
    }

    public static int rippleColor(Context context) {
        return isDark(context) ? 0x26FFFFFF : 0x1A000000;
    }

    public static int successColor() {
        return 0xFF00C85A;
    }

    public static int dangerColor() {
        return 0xFFEB0400;
    }

    public static int dividerColor(Context context) {
        int base = primaryTextColor(context);
        return Color.argb(
                isDark(context) ? 0x24 : 0x1A,
                Color.red(base), Color.green(base), Color.blue(base));
    }

    /**
     * Creates preference controls with the resolved Light/Dark platform theme.
     */
    public static Context preferenceContext(Context context) {
        if (context == null) return null;
        return new ContextThemeWrapper(
                context,
                isDark(context)
                        ? android.R.style.Theme_DeviceDefault
                        : android.R.style.Theme_DeviceDefault_Light);
    }

    /**
     * Styles only the Kizu preference fragment root, list divider, switches, and sliders.
     */
    public static void applySettingsView(View root) {
        if (root == null) return;
        Context context = root.getContext();
        int bg = backgroundColor(context);
        int primary = primaryTextColor(context);
        int secondary = secondaryTextColor(context);
        int divider = dividerColor(context);
        root.setBackgroundColor(bg);

        View list = root.findViewById(android.R.id.list);
        if (list != null) {
            list.setBackgroundColor(bg);
            if (list instanceof ListView listView) {
                listView.setCacheColorHint(bg);
                listView.setDivider(new ColorDrawable(divider));
                listView.setDividerHeight(Math.max(1, dp(context, 0.5f)));
                listView.setHeaderDividersEnabled(false);
                listView.setFooterDividersEnabled(false);
                if (!Boolean.TRUE.equals(listView.getTag(LIST_TAG_KEY))) {
                    listView.setTag(LIST_TAG_KEY, Boolean.TRUE);
                    listView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                        @Override
                        public void onChildViewAdded(View parent, View child) {
                            stylePreferenceRow(child, context, primaryTextColor(context), secondaryTextColor(context));
                        }

                        @Override
                        public void onChildViewRemoved(View parent, View child) {
                        }
                    });
                }
            }
        }

        stylePreferenceRow(root, context, primary, secondary);
    }

    private static void stylePreferenceRow(View view, Context context, int primary, int secondary) {
        if (view instanceof TextView text) {
            boolean summary = text.getId() == android.R.id.summary;
            text.setTextColor(summary ? secondary : primary);
        } else if (view instanceof Switch sw) {
            TwitchUi.styleSwitch(context, sw);
        } else if (view instanceof SeekBar bar) {
            TwitchUi.styleSeekBar(context, bar);
        }
        if (!(view instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            stylePreferenceRow(group.getChildAt(i), context, primary, secondary);
        }
    }

    private static Context themeResolutionContext(Context context) {
        if (context == null) return null;
        try {
            Context application = context.getApplicationContext();
            return application != null ? application : context;
        } catch (Throwable ignored) {
            return context;
        }
    }

    @SuppressWarnings("deprecation")
    private static void registerDefaultThemeListener(Context context) {
        if (context == null || defaultPrefs != null) return;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(DEFAULT_PREFS_LISTENER);
                defaultPrefs = prefs;
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static Boolean twitchUserTheme(Context context) {
        if (context == null) return null;
        try {
            SharedPreferences prefs = defaultPrefs != null
                    ? defaultPrefs
                    : PreferenceManager.getDefaultSharedPreferences(context);
            String value = prefs.getString("user_theme", null);
            if ("DARK".equalsIgnoreCase(value)) return true;
            if ("LIGHT".equalsIgnoreCase(value)) return false;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Boolean twitchNightMode(Context context) {
        if (context == null) return null;
        if (!themeManagerChecked) {
            themeManagerChecked = true;
            try {
                Class<?> manager = Class.forName("tv.twitch.android.app.core.ThemeManager");
                Field companionField = manager.getDeclaredField("Companion");
                companionField.setAccessible(true);
                Object companion = companionField.get(null);
                if (companion != null) {
                    themeManagerIsNight = companion.getClass().getMethod("isNightModeEnabled", Context.class);
                    themeManagerCompanion = companion;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object companion = themeManagerCompanion;
            Method method = themeManagerIsNight;
            if (companion == null || method == null) return null;
            Object value = method.invoke(companion, context);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int twitchColor(Context context, String name) {
        Context themeContext = themeResolutionContext(context);
        if (themeContext == null) return Integer.MIN_VALUE;
        try {
            int id = Utils.getResourceId(themeContext, name, "color");
            if (id != 0) return themeContext.getColor(id);
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    private static boolean colorMatchesTheme(int color, boolean dark) {
        if (color == Integer.MIN_VALUE) return false;
        return (luminance(color) < 128d) == dark;
    }

    private static boolean textMatchesTheme(int color, boolean dark) {
        if (color == Integer.MIN_VALUE) return false;
        return (luminance(color) < 160d) != dark;
    }

    private static double luminance(int color) {
        return 0.299d * Color.red(color)
                + 0.587d * Color.green(color)
                + 0.114d * Color.blue(color);
    }

    @SuppressWarnings("deprecation")
    public static AlertDialog.Builder dialog(Context context) {
        return new AlertDialog.Builder(context, isDark(context)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }
}
