package io.github.vay1314.camerasnap;

final class PhotoFallback {
    static final class Choice {
        final String format;
        final int message;
        final boolean useSelectedSize;
        Choice(String format, boolean useSelectedSize, int message) {
            this.format = format;
            this.useSelectedSize = useSelectedSize;
            this.message = message;
        }
    }

    private boolean triedJpeg, triedAutomatic;

    Choice next(String format, boolean manualSize, boolean captureStarted) {
        if (captureStarted) return null;
        if (PhotoOptions.HEIC.equals(format) && !triedJpeg) {
            triedJpeg = true;
            return new Choice(PhotoOptions.JPEG, manualSize, R.string.heic_session_fallback);
        }
        if (PhotoOptions.JPEG.equals(format) && manualSize && !triedAutomatic) {
            triedAutomatic = true;
            return new Choice(PhotoOptions.JPEG, false, R.string.size_session_fallback);
        }
        return null;
    }
}
