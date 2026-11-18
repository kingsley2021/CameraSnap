package io.github.vay1314.camerasnap;

final class PhotoFeedback {
    static final int EVERY = 0, FIRST = 1, OFF = 2;

    static int normalize(int mode) { return mode == FIRST || mode == OFF ? mode : EVERY; }

    static boolean shouldVibrate(int mode, int savedPhotos) {
        return savedPhotos > 0 && (normalize(mode) == EVERY || mode == FIRST && savedPhotos == 1);
    }
}
