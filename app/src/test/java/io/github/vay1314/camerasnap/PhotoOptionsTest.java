package io.github.vay1314.camerasnap;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoOptionsTest {
    private static PhotoOptions.Resolution size(int width, int height) { return new PhotoOptions.Resolution(width, height); }

    @Test public void standardTwelvePointFiveMegapixelsIsNotExcluded() {
        PhotoOptions.Resolution full = size(4096, 3072);
        assertSame(full, PhotoOptions.choose(Arrays.asList(size(4096, 2304), full), ""));
        assertEquals(12_582_912, full.pixels());
    }

    @Test public void prefersSensorAspectRatioAroundStandardResolution() {
        PhotoOptions.Resolution full = size(4000, 3000);
        assertSame(full, PhotoOptions.choose(Arrays.asList(size(4608, 2592), full, size(8192, 6144)), ""));
    }

    @Test public void recognizesPortraitFourThreeWithoutChangingDimensions() {
        PhotoOptions.Resolution portrait = size(3072, 4096);
        assertTrue(portrait.isFourThree());
        assertEquals("3072x4096", portrait.key());
        assertSame(portrait, PhotoOptions.choose(Arrays.asList(portrait, size(2304, 4096)), ""));
    }

    @Test public void explicitSupportedSizeOverridesAutomaticChoice() {
        PhotoOptions.Resolution wide = size(4096, 2304);
        assertSame(wide, PhotoOptions.choose(Arrays.asList(size(4096, 3072), wide), "4096x2304"));
    }

    @Test public void unavailableSavedSizeFallsBackToAutomatic() {
        PhotoOptions.Resolution standard = size(4032, 3024);
        assertSame(standard, PhotoOptions.choose(Collections.singletonList(standard), "9999x9999"));
    }

    @Test public void fallsBackToLargestReasonableSizeWithoutFourThree() {
        PhotoOptions.Resolution wide = size(4096, 2304);
        assertSame(wide, PhotoOptions.choose(Arrays.asList(size(1920, 1080), wide, size(7680, 4320)), ""));
    }

    @Test public void tinyFourThreeDoesNotOverrideUsefulPhotoResolution() {
        PhotoOptions.Resolution wide = size(4096, 2304);
        assertSame(wide, PhotoOptions.choose(Arrays.asList(size(640, 480), wide), ""));
    }

    @Test public void choosesSmallestIfOnlyLargeOutputsAreAvailable() {
        PhotoOptions.Resolution smaller = size(6000, 4000);
        assertSame(smaller, PhotoOptions.choose(Arrays.asList(size(8192, 6144), smaller), ""));
    }

    @Test public void largeDimensionsCannotOverflowPixelCount() {
        assertEquals(4_294_967_296L, size(65536, 65536).pixels());
    }

    @Test public void rejectsMissingAndInvalidDimensions() {
        assertThrows(IllegalStateException.class, () -> PhotoOptions.choose(Collections.emptyList(), ""));
        assertThrows(IllegalArgumentException.class, () -> size(0, 3072));
        assertThrows(IllegalArgumentException.class, () -> size(4096, -1));
    }

    @Test public void formatMimeAndExtensionAlwaysAgree() {
        assertEquals("image/heic", PhotoOptions.mime(PhotoOptions.HEIC));
        assertEquals(".heic", PhotoOptions.extension(PhotoOptions.HEIC));
        assertEquals(1212500294, PhotoOptions.imageFormat(PhotoOptions.HEIC));
        assertEquals("image/jpeg", PhotoOptions.mime(PhotoOptions.JPEG));
        assertEquals(".jpg", PhotoOptions.extension(PhotoOptions.JPEG));
        assertEquals(256, PhotoOptions.imageFormat(PhotoOptions.JPEG));
        assertEquals(PhotoOptions.JPEG, PhotoOptions.format(null));
        assertEquals(PhotoOptions.JPEG, PhotoOptions.format("unsupported"));
    }

    @Test public void labelsShowActualPixelsAndAspectRatio() {
        assertEquals("4096×3072 · 12.58 MP · 4:3", size(4096, 3072).label());
        assertEquals("4096×2304 · 9.44 MP · 16:9", size(4096, 2304).label());
    }

    @Test public void resolutionLabelsFollowLocaleWithoutChangingStoredKeys() {
        PhotoOptions.Resolution resolution = size(4096, 3072);
        assertEquals("4096×3072 · 12,58 MP · 4:3", resolution.label(java.util.Locale.GERMAN));
        assertEquals("4096×3072 · 12.58 MP · 4:3", resolution.label(java.util.Locale.ENGLISH));
        assertEquals("4096x3072", resolution.key());
    }
}
