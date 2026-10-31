package app.morphe.extension.settings;

import android.app.AlertDialog;
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
        int padding = SettingsUi.dp(context, 20);
        EditText text = new EditText(context);
        text.setSingleLine(true);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        text.setHint("API_KEY");
        FrameLayout layout = new FrameLayout(context);
        layout.setPadding(padding, padding / 2, padding, 0);
        layout.addView(text);
        AlertDialog dialog = SettingsUi.dialog(context)
                .setTitle(getTitle())
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String value = text.getText().toString().trim();
                    if (!value.isEmpty()) setValue(value);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Clear", (d, which) -> setValue(""))
                .create();
        dialog.show();
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
