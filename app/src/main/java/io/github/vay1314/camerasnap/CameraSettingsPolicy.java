package io.github.vay1314.camerasnap;

import java.util.List;
import java.util.Set;

final class CameraSettingsPolicy {
    static final String STREET_KEY = "pref_street_shot";
    static final String MODULE_KEY = "unlock_miui_camera_snap_settings";
    static final List<String> CATEGORY_KEYS = List.of("category_common_setting_group2",
            "category_common_setting_group1", "category_photo_setting", "category_module_setting",
            "category_common_setting");

    enum Target { STREET, EXISTING, INSERT, NONE }

    static boolean isEntryKey(String key) {
        return STREET_KEY.equals(key) || MODULE_KEY.equals(key);
    }

    static Target target(boolean streetAvailable, boolean modulePresent, boolean streetPresent,
            Set<String> visibleCategories) {
        if (streetAvailable) return Target.STREET;
        if (modulePresent) return Target.EXISTING;
        return streetPresent || insertionCategory(visibleCategories) != null ? Target.INSERT : Target.NONE;
    }

    static String insertionCategory(Set<String> visibleCategories) {
        for (String key : CATEGORY_KEYS) if (visibleCategories.contains(key)) return key;
        return null;
    }
}
