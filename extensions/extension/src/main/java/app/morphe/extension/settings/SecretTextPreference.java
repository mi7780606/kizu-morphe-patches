package app.morphe.extension.settings;

import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

/** APIキーなど、行に中身を出さないテキスト設定。 */
@SuppressWarnings("deprecation")
public final class SecretTextPreference extends Preference {
    private final StringSetting setting;

    public SecretTextPreference(Context context, StringSetting setting) {
        super(context);
        this.setting = setting;
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
        EditText text = TwitchUi.createPillInput(context, "API_KEY");
        text.setSingleLine(true);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout layout = new FrameLayout(context);
        layout.setPadding(0, SettingsUi.dp(context, 4), 0, 0);
        layout.addView(text);
        CharSequence rawTitle = getTitle();
        TwitchUi.showThemedDialog(
                context,
                rawTitle == null ? null : rawTitle.toString(),
                layout,
                context.getString(android.R.string.ok),
                () -> {
                    String value = text.getText().toString().trim();
                    if (!value.isEmpty()) setValue(value);
                },
                "Clear",
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
        setSummary(value.isEmpty() ? "未設定" : "設定済み（" + value.length() + "文字）");
    }
}
