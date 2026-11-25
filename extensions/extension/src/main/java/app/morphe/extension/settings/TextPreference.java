package app.morphe.extension.settings;

import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * A single line text setting, edited in a dialog. The row shows the value, or a placeholder
 * text when it is empty.
 */
@SuppressWarnings("deprecation")
public final class TextPreference extends Preference {
    private final StringSetting setting;
    private final String hint;
    private final CharSequence emptySummary;

    /**
     * @param hint         Shown in the empty text field.
     * @param emptySummary Shown in the row when the value is empty.
     */
    public TextPreference(Context context, StringSetting setting, String hint, CharSequence emptySummary) {
        super(context);
        this.setting = setting;
        this.hint = hint;
        this.emptySummary = emptySummary;
        setKey(setting.key);
        setPersistent(true);
        setDefaultValue(setting.defaultValue);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        updateSummary(setting.get());
    }

    @Override
    protected void onClick() {
        Context context = getContext();

        EditText text = TwitchUi.createPillInput(context, hint);
        text.setSingleLine(true);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setText(setting.get());
        text.setSelection(text.length());

        FrameLayout layout = new FrameLayout(context);
        layout.setPadding(0, SettingsUi.dp(context, 4), 0, 0);
        layout.addView(text);

        CharSequence rawTitle = getTitle();
        TwitchUi.showThemedDialog(
                context,
                rawTitle == null ? null : rawTitle.toString(),
                layout,
                context.getString(android.R.string.ok),
                () -> setValue(text.getText().toString().trim()),
                "クリア",
                () -> setValue(""),
                context.getString(android.R.string.cancel));
    }

    private void setValue(String value) {
        if (!callChangeListener(value)) return;
        persistString(value);
        updateSummary(value);
        notifyChanged();
    }

    private void updateSummary(String value) {
        setSummary(value.isEmpty() ? emptySummary : value);
    }
}
