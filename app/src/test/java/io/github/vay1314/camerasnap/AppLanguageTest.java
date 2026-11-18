package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppLanguageTest {
    @Test public void emptyAndUnsupportedSelectionsFollowSystem() {
        assertEquals("", AppLanguage.normalize(null));
        assertEquals("", AppLanguage.normalize(""));
        assertEquals("", AppLanguage.normalize("fr-FR"));
        assertEquals("", AppLanguage.normalize("invalid"));
    }

    @Test public void supportedRegionalVariantsSelectSameLanguage() {
        assertEquals("en", AppLanguage.normalize("en-GB"));
        assertEquals("de", AppLanguage.normalize("de-AT"));
        assertEquals("ru", AppLanguage.normalize("ru-RU"));
    }

    @Test public void traditionalChineseRegionsUseTraditionalTranslation() {
        for (String tag : new String[]{"zh-TW", "zh-HK", "zh-MO", "zh-Hant"})
            assertEquals("zh-TW", AppLanguage.normalize(tag));
    }

    @Test public void chineseScriptTakesPriorityOverRegion() {
        assertEquals("zh-TW", AppLanguage.normalize("zh-Hant-CN"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-Hans-HK"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-SG"));
        assertEquals("zh-CN", AppLanguage.normalize("zh"));
    }
}
