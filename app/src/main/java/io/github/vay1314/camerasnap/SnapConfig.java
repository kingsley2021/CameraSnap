package io.github.vay1314.camerasnap;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

final class SnapConfig {
    static final String PACKAGE = "io.github.vay1314.camerasnap";
    static final Uri URI = Uri.parse("content://" + PACKAGE + ".config/state");
    static final int OFF = 0, PHOTO = 1, VIDEO = 2;
    static final String ACTION_START = PACKAGE + ".START";
    static final String ACTION_STOP = PACKAGE + ".STOP";

    static final long MAX_SESSION_MS = 3_600_000;

    final int mode;
    final int photoVibration;
    final String cameraId;
    final String savePath;
    final String saveTree;
    final String saveTreeName;
    final String photoFormat;
    final String photoSize;

    SnapConfig(int mode, String cameraId, String savePath, String saveTree, String saveTreeName) {
        this(mode, cameraId, savePath, saveTree, saveTreeName, PhotoOptions.JPEG, "");
    }

    SnapConfig(int mode, String cameraId, String savePath, String saveTree, String saveTreeName,
            String photoFormat, String photoSize) {
        this(mode, cameraId, savePath, saveTree, saveTreeName, photoFormat, photoSize, PhotoFeedback.EVERY);
    }

    SnapConfig(int mode, String cameraId, String savePath, String saveTree, String saveTreeName,
            String photoFormat, String photoSize, int photoVibration) {
        this.photoVibration = PhotoFeedback.normalize(photoVibration);
        this.mode = mode == PHOTO || mode == VIDEO ? mode : OFF;
        this.cameraId = cameraId == null ? "" : cameraId;
        this.savePath = MediaSavePath.orDefault(savePath);
        this.saveTree = saveTree == null ? "" : saveTree;
        this.saveTreeName = saveTreeName == null ? "" : saveTreeName;
        this.photoFormat = PhotoOptions.format(photoFormat);
        this.photoSize = photoSize == null ? "" : photoSize;
    }

    AppText destinationText() {
        if (saveTree.isEmpty()) return AppText.of(R.string.plain_text, savePath);
        try {
            Uri tree = Uri.parse(saveTree);
            if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
                String id = DocumentsContract.getTreeDocumentId(tree);
                int separator = id.indexOf(':');
                if (separator > 0) {
                    String volume = id.substring(0, separator);
                    String path = id.substring(separator + 1);
                    if ("primary".equals(volume)) return path.isEmpty() ? AppText.of(R.string.internal_storage) : AppText.of(R.string.plain_text, path);
                    if ("home".equals(volume)) return AppText.of(R.string.plain_text, path.isEmpty() ? "Documents/" : "Documents/" + path);
                    return AppText.of(R.string.storage_card, volume, path);
                }
            }
        } catch (RuntimeException ignored) { }
        return saveTreeName.isEmpty() ? AppText.of(R.string.selected_folder) : AppText.of(R.string.selected_folder_name, saveTreeName);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("snap_config_v2", Context.MODE_PRIVATE);
    }

    static SnapConfig read(Context context) {
        SharedPreferences pref = prefs(context);
        return new SnapConfig(pref.getInt("mode", OFF), pref.getString("camera_id", ""),
                pref.getString("save_path", MediaSavePath.DEFAULT),
                pref.getString("save_tree", ""), pref.getString("save_tree_name", ""),
                pref.getString("photo_format", PhotoOptions.JPEG), pref.getString("photo_size", ""), pref.getInt("photo_vibration", PhotoFeedback.EVERY));
    }

    static SnapConfig fromBundle(Bundle bundle) {
        return bundle == null ? new SnapConfig(OFF, "", MediaSavePath.DEFAULT, "", "") :
                new SnapConfig(bundle.getInt("mode", OFF), bundle.getString("camera_id", ""),
                        bundle.getString("save_path", MediaSavePath.DEFAULT),
                        bundle.getString("save_tree", ""), bundle.getString("save_tree_name", ""),
                        bundle.getString("photo_format", PhotoOptions.JPEG), bundle.getString("photo_size", ""), bundle.getInt("photo_vibration", PhotoFeedback.EVERY));
    }

    Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt("mode", mode);
        bundle.putString("camera_id", cameraId);
        bundle.putString("save_path", savePath);
        bundle.putString("save_tree", saveTree);
        bundle.putString("save_tree_name", saveTreeName);
        bundle.putString("photo_format", photoFormat);
        bundle.putString("photo_size", photoSize);
        bundle.putInt("photo_vibration", photoVibration);
        return bundle;
    }

    static void status(Context context, int resource, Object... arguments) {
        status(context, AppText.of(resource, arguments));
    }

    static void status(Context context, AppText text) {
        try {
            prefs(context).edit().putString("status_v2", text.encode(context).toString()).remove("status").apply();
        } catch (org.json.JSONException e) {
            android.util.Log.w(HookEntry.TAG, "Cannot encode capture status", e);
        }
    }

    static String statusText(Context context) {
        context = AppLanguage.wrap(context);
        SharedPreferences pref = prefs(context);
        String value = pref.getString("status_v2", "");
        if (value == null || value.isEmpty())
            return context.getString(pref.contains("status") ? R.string.status_unavailable : R.string.not_captured);
        try { return AppText.decode(context, new org.json.JSONObject(value)).render(context); }
        catch (org.json.JSONException | RuntimeException e) { return context.getString(R.string.status_unavailable); }
    }
}
