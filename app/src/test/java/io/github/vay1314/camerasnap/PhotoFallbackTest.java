package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoFallbackTest {
    @Test public void incompatibleHeicSessionFallsBackToJpeg() {
        PhotoFallback fallback = new PhotoFallback();
        PhotoFallback.Choice choice = fallback.next(PhotoOptions.HEIC, false, false);
        assertEquals(PhotoOptions.JPEG, choice.format);
        assertFalse(choice.useSelectedSize);
        assertNull(fallback.next(PhotoOptions.HEIC, false, false));
    }

    @Test public void keepsRequestedResolutionOnFirstFormatFallback() {
        PhotoFallback.Choice choice = new PhotoFallback().next(PhotoOptions.HEIC, true, false);
        assertTrue(choice.useSelectedSize);
    }

    @Test public void invalidManualSessionCanFallBackOnceToAutomatic() {
        PhotoFallback fallback = new PhotoFallback();
        PhotoFallback.Choice choice = fallback.next(PhotoOptions.JPEG, true, false);
        assertEquals(PhotoOptions.JPEG, choice.format);
        assertFalse(choice.useSelectedSize);
        assertNull(fallback.next(PhotoOptions.JPEG, true, false));
        assertNull(fallback.next(PhotoOptions.JPEG, false, false));
    }

    @Test public void formatAndResolutionFallbacksCannotLoop() {
        PhotoFallback fallback = new PhotoFallback();
        assertTrue(fallback.next(PhotoOptions.HEIC, true, false).useSelectedSize);
        assertFalse(fallback.next(PhotoOptions.JPEG, true, false).useSelectedSize);
        assertNull(fallback.next(PhotoOptions.JPEG, false, false));
        assertNull(fallback.next(PhotoOptions.HEIC, false, false));
    }

    @Test public void neverChangesFormatAfterCapturingBegins() {
        PhotoFallback fallback = new PhotoFallback();
        assertNull(fallback.next(PhotoOptions.HEIC, true, true));
        assertNull(fallback.next(PhotoOptions.JPEG, true, true));
    }

    @Test public void automaticJpegFailureIsReportedWithoutRetryLoop() {
        assertNull(new PhotoFallback().next(PhotoOptions.JPEG, false, false));
    }
}
