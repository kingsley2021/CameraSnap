package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoPipelineTest {
    @Test public void nextCaptureCanOverlapSavingPreviousPhoto() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        assertTrue(pipeline.beginCapture());
        assertFalse(pipeline.canCapture());
        pipeline.imageReceived(80);
        assertTrue(pipeline.canCapture());
        assertTrue(pipeline.beginCapture());
    }

    @Test public void slowStorageCannotAccumulateMoreThanTwoPhotos() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(80);
        pipeline.beginCapture();
        pipeline.imageReceived(90);
        assertFalse(pipeline.canCapture());
        assertFalse(pipeline.beginCapture());
        pipeline.saved(80);
        assertTrue(pipeline.canCapture());
    }

    @Test public void reservesMemoryForAnotherMaximumSizedFrame() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(100);
        assertTrue(pipeline.beginCapture());
        pipeline.imageReceived(100);
        assertFalse(pipeline.canCapture());
        pipeline.saved(100);
        assertTrue(pipeline.canCapture());
    }

    @Test public void enforcesOneHundredRequestedPhotosIncludingPendingSaves() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        for (int i = 0; i < PhotoPipeline.MAX_PHOTOS - 1; i++) {
            assertTrue(pipeline.beginCapture());
            pipeline.imageReceived(10);
            pipeline.saved(10);
        }
        assertTrue(pipeline.beginCapture());
        pipeline.imageReceived(10);
        assertFalse(pipeline.canCapture());
        assertFalse(pipeline.complete());
        pipeline.saved(10);
        assertTrue(pipeline.complete());
        assertFalse(pipeline.beginCapture());
    }

    @Test public void releaseStopsNewCapturesButAllowsQueuedSavesToDrain() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(50);
        pipeline.stop();
        assertFalse(pipeline.beginCapture());
        pipeline.saved(50);
        assertFalse(pipeline.canCapture());
    }

    @Test public void cannotHaveTwoSimultaneousCaptureRequests() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        assertTrue(pipeline.beginCapture());
        assertFalse(pipeline.beginCapture());
    }

    @Test public void oversizedEncodedDataIsRejectedBeforeAllocation() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        assertThrows(IllegalStateException.class, () -> pipeline.imageReceived(101));
        pipeline.stop();
        assertFalse(pipeline.canCapture());
    }

    @Test public void rejectsEmptyOrUnexpectedFramesAndCompletions() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        assertThrows(IllegalStateException.class, () -> pipeline.imageReceived(50));
        assertThrows(IllegalStateException.class, () -> pipeline.saved(50));
        pipeline.beginCapture();
        assertThrows(IllegalStateException.class, () -> pipeline.imageReceived(0));
    }

    @Test public void failedSavingCanStopQueueAndDrainWithoutRestart() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(50);
        pipeline.beginCapture();
        pipeline.imageReceived(60);
        pipeline.stop();
        pipeline.saved(50);
        pipeline.saved(60);
        assertFalse(pipeline.canCapture());
    }

    @Test public void frameLimitCannotOverflowQueueBudget() {
        assertThrows(IllegalArgumentException.class, () -> new PhotoPipeline(0));
        assertThrows(IllegalArgumentException.class, () -> new PhotoPipeline(Long.MAX_VALUE));
        PhotoPipeline pipeline = new PhotoPipeline(Integer.MAX_VALUE);
        pipeline.beginCapture();
        pipeline.imageReceived(Integer.MAX_VALUE);
        assertTrue(pipeline.canCapture());
    }

    @Test public void completionsMustMatchTheSavedFrameInOrder() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(40);
        pipeline.beginCapture();
        pipeline.imageReceived(60);
        assertThrows(IllegalStateException.class, () -> pipeline.saved(60));
        pipeline.saved(40);
        pipeline.saved(60);
        assertTrue(pipeline.canCapture());
    }

    @Test public void completionCannotSilentlyReleaseWrongMemoryAmount() {
        PhotoPipeline pipeline = new PhotoPipeline(100);
        pipeline.beginCapture();
        pipeline.imageReceived(50);
        assertThrows(IllegalStateException.class, () -> pipeline.saved(49));
        pipeline.saved(50);
        assertTrue(pipeline.canCapture());
    }
}
