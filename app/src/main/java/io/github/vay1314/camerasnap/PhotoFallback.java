package io.github.vay1314.camerasnap;

final class PhotoFallback {
    static final class Choice {
        final String format, message;
        final boolean useSelectedSize;
        Choice(String format, boolean useSelectedSize, String message) {
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
            return new Choice(PhotoOptions.JPEG, manualSize, "HEIC 会话不兼容，已回退 JPG");
        }
        if (PhotoOptions.JPEG.equals(format) && manualSize && !triedAutomatic) {
            triedAutomatic = true;
            return new Choice(PhotoOptions.JPEG, false, "所选分辨率会话不兼容，已使用自动分辨率");
        }
        return null;
    }
}
