package io.github.vay1314.camerasnap;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraSettingsPolicyTest {
    @Test public void onlyExactEntryKeysAreRedirected() {
        assertTrue(CameraSettingsPolicy.isEntryKey("pref_street_shot"));
        assertTrue(CameraSettingsPolicy.isEntryKey(CameraSettingsPolicy.MODULE_KEY));
        assertFalse(CameraSettingsPolicy.isEntryKey("pref_street_shot_help"));
        assertFalse(CameraSettingsPolicy.isEntryKey("pref_camera_volume_function_key"));
        assertFalse(CameraSettingsPolicy.isEntryKey(null));
    }

    @Test public void availableStreetEntryReplacesFallback() {
        assertEquals(CameraSettingsPolicy.Target.STREET,
                CameraSettingsPolicy.target(true, true, true, Set.of()));
    }

    @Test public void repeatedResumeDoesNotInsertDuplicate() {
        assertEquals(CameraSettingsPolicy.Target.EXISTING,
                CameraSettingsPolicy.target(false, true, false, Set.of("category_photo_setting")));
    }

    @Test public void hiddenOrDisabledStreetEntryGetsAccessibleFallback() {
        assertEquals(CameraSettingsPolicy.Target.INSERT,
                CameraSettingsPolicy.target(false, false, true, Set.of()));
    }

    @Test public void knownCameraCategoriesAllowMissingStreetEntry() {
        for (String key : CameraSettingsPolicy.CATEGORY_KEYS)
            assertEquals(CameraSettingsPolicy.Target.INSERT,
                    CameraSettingsPolicy.target(false, false, false, Set.of(key)));
    }

    @Test public void unrelatedNestedSettingsStayUntouched() {
        assertEquals(CameraSettingsPolicy.Target.NONE,
                CameraSettingsPolicy.target(false, false, false, Set.of("category_sound_setting")));
        assertEquals(CameraSettingsPolicy.Target.NONE,
                CameraSettingsPolicy.target(false, false, false, Set.of()));
    }

    @Test public void placementUsesAvailableCategoryWithStablePriority() {
        assertEquals("category_common_setting_group2", CameraSettingsPolicy.insertionCategory(
                Set.of("category_common_setting_group2", "category_photo_setting")));
        assertEquals("category_photo_setting", CameraSettingsPolicy.insertionCategory(Set.of("category_photo_setting")));
        assertNull(CameraSettingsPolicy.insertionCategory(Set.of("category_photo_settings")));
    }
}
