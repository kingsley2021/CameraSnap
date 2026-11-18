package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoFeedbackTest {
    @Test public void everyPhotoVibratesOnlyAfterSuccessfulSave() {
        assertFalse(PhotoFeedback.shouldVibrate(PhotoFeedback.EVERY, 0));
        for (int saved = 1; saved <= 100; saved++) assertTrue(PhotoFeedback.shouldVibrate(PhotoFeedback.EVERY, saved));
    }

    @Test public void firstPhotoModeDoesNotRepeatWithinBurst() {
        assertFalse(PhotoFeedback.shouldVibrate(PhotoFeedback.FIRST, 0));
        assertTrue(PhotoFeedback.shouldVibrate(PhotoFeedback.FIRST, 1));
        for (int saved = 2; saved <= 100; saved++) assertFalse(PhotoFeedback.shouldVibrate(PhotoFeedback.FIRST, saved));
        assertTrue(PhotoFeedback.shouldVibrate(PhotoFeedback.FIRST, 1));
    }

    @Test public void disabledModeNeverVibrates() {
        for (int saved = 0; saved <= 100; saved++) assertFalse(PhotoFeedback.shouldVibrate(PhotoFeedback.OFF, saved));
    }

    @Test public void unknownPreferencePreservesOriginalFeedback() {
        assertEquals(PhotoFeedback.EVERY, PhotoFeedback.normalize(-1));
        assertEquals(PhotoFeedback.EVERY, PhotoFeedback.normalize(99));
        assertTrue(PhotoFeedback.shouldVibrate(99, 2));
    }
}
