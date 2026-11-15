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
        this.mode = mode == PHOTO || mode == VIDEO ? mode : OFF;
        this.cameraId = cameraId == null ? "" : cameraId;
        this.savePath = MediaSavePath.orDefault(savePath);
        this.saveTree = saveTree == null ? "" : saveTree;
        this.saveTreeName = saveTreeName == null || saveTreeName.isEmpty() ? "所选文件夹" : saveTreeName;
        this.photoFormat = PhotoOptions.format(photoFormat);
        this.photoSize = photoSize == null ? "" : photoSize;
    }

    String destinationLabel() {
        if (saveTree.isEmpty()) return savePath;
        try {
            Uri tree = Uri.parse(saveTree);
            if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
                String id = DocumentsContract.getTreeDocumentId(tree);
                int separator = id.indexOf(':');
                if (separator > 0) {
                    String volume = id.substring(0, separator);
                    String path = id.substring(separator + 1);
                    if ("primary".equals(volume)) return path.isEmpty() ? "内部存储/" : path;
                    if ("home".equals(volume)) return path.isEmpty() ? "Documents/" : "Documents/" + path;
                    return "存储卡（" + volume + "）/" + path;
                }
            }
        } catch (RuntimeException ignored) { }
        return "所选文件夹：" + saveTreeName;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("snap_config_v2", Context.MODE_PRIVATE);
    }

    static SnapConfig read(Context context) {
        SharedPreferences pref = prefs(context);
        return new SnapConfig(pref.getInt("mode", OFF), pref.getString("camera_id", ""),
                pref.getString("save_path", MediaSavePath.DEFAULT),
                pref.getString("save_tree", ""), pref.getString("save_tree_name", ""),
                pref.getString("photo_format", PhotoOptions.JPEG), pref.getString("photo_size", ""));
    }

    static SnapConfig fromBundle(Bundle bundle) {
        return bundle == null ? new SnapConfig(OFF, "", MediaSavePath.DEFAULT, "", "") :
                new SnapConfig(bundle.getInt("mode", OFF), bundle.getString("camera_id", ""),
                        bundle.getString("save_path", MediaSavePath.DEFAULT),
                        bundle.getString("save_tree", ""), bundle.getString("save_tree_name", ""),
                        bundle.getString("photo_format", PhotoOptions.JPEG), bundle.getString("photo_size", ""));
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
        return bundle;
    }

    static void status(Context context, String text) {
        prefs(context).edit().putString("status", text).apply();
    }
}
