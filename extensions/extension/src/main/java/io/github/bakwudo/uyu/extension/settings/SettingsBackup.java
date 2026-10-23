package io.github.bakwudo.uyu.extension.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.bakwudo.uyu.extension.Utils;

/**
 * Kizu: 設定のエクスポート / インポート。
 * uyu_settings の中身を型つきの JSON ファイルに保存し、読み込んで戻す。
 */
public final class SettingsBackup {
    public static final int REQUEST_EXPORT = 0x7580;
    public static final int REQUEST_IMPORT = 0x7581;

    private static final String FORMAT = "kizu-settings";
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private SettingsBackup() {
    }

    public static void startExport(Fragment fragment) {
        try {
            String name = "kizu-settings-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date()) + ".json";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, name);
            fragment.startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception ex) {
            Utils.logError("Settings export: picker failed", ex);
            toast(fragment.getActivity(), "ファイル保存画面を開けませんでした");
        }
    }

    public static void startImport(Fragment fragment) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "text/plain", "application/octet-stream"});
            fragment.startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception ex) {
            Utils.logError("Settings import: picker failed", ex);
            toast(fragment.getActivity(), "ファイル選択画面を開けませんでした");
        }
    }

    /** @return true if the request code belonged to this class. */
    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return false;
        if (activity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            export(activity, uri);
        } else {
            confirmImport(activity, uri);
        }
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void export(Activity activity, Uri uri) {
        try {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> e : prefs(activity).getAll().entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                JSONObject item = new JSONObject();
                if (v instanceof Boolean) {
                    item.put("t", "b");
                    item.put("v", v);
                } else if (v instanceof Integer) {
                    item.put("t", "i");
                    item.put("v", v);
                } else if (v instanceof Long) {
                    item.put("t", "l");
                    item.put("v", v);
                } else if (v instanceof Float) {
                    item.put("t", "f");
                    item.put("v", ((Float) v).doubleValue());
                } else if (v instanceof String) {
                    item.put("t", "s");
                    item.put("v", v);
                } else if (v instanceof Set) {
                    JSONArray arr = new JSONArray();
                    for (Object o : (Set<?>) v) arr.put(String.valueOf(o));
                    item.put("t", "ss");
                    item.put("v", arr);
                } else {
                    continue;
                }
                values.put(e.getKey(), item);
            }
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("values", values);

            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IllegalStateException("no output stream");
                out.write(bytes);
            }
            toast(activity, "設定をエクスポートしました（" + values.length() + "件）");
        } catch (Exception ex) {
            Utils.logError("Settings export failed", ex);
            toast(activity, "エクスポートに失敗しました");
        }
    }

    private static void confirmImport(Activity activity, Uri uri) {
        JSONObject values;
        try {
            values = readValues(activity, uri);
        } catch (Exception ex) {
            Utils.logError("Settings import: read failed", ex);
            toast(activity, "設定ファイルを読み込めませんでした");
            return;
        }
        final JSONObject finalValues = values;
        new AlertDialog.Builder(activity)
                .setTitle("設定をインポート")
                .setMessage(values.length() + "件の設定を読み込みます。今の設定は上書きされます。")
                .setPositiveButton("インポート", (d, w) -> apply(activity, finalValues))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static JSONObject readValues(Context context, Uri uri) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("no input stream");
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > MAX_BYTES) throw new IllegalStateException("file too large");
            }
        }
        JSONObject root = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        if (!FORMAT.equals(root.optString("format"))) throw new IllegalStateException("not a kizu settings file");
        JSONObject values = root.optJSONObject("values");
        if (values == null) throw new IllegalStateException("no values");
        return values;
    }

    private static void apply(Activity activity, JSONObject values) {
        try {
            SharedPreferences.Editor editor = prefs(activity).edit();
            int count = 0;
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = values.optJSONObject(key);
                if (item == null) continue;
                String t = item.optString("t");
                switch (t) {
                    case "b":
                        editor.putBoolean(key, item.getBoolean("v"));
                        break;
                    case "i":
                        editor.putInt(key, item.getInt("v"));
                        break;
                    case "l":
                        editor.putLong(key, item.getLong("v"));
                        break;
                    case "f":
                        editor.putFloat(key, (float) item.getDouble("v"));
                        break;
                    case "s":
                        editor.putString(key, item.getString("v"));
                        break;
                    case "ss": {
                        JSONArray arr = item.getJSONArray("v");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                        editor.putStringSet(key, set);
                        break;
                    }
                    default:
                        continue;
                }
                count++;
            }
            editor.apply();
            toast(activity, "設定をインポートしました（" + count + "件）。反映されない項目はアプリを再起動してください");
        } catch (Exception ex) {
            Utils.logError("Settings import: apply failed", ex);
            toast(activity, "インポートに失敗しました");
        }
    }

    private static void toast(Context context, String message) {
        if (context == null) return;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
