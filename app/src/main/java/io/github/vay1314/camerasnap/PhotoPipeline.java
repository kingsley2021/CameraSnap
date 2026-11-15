package io.github.vay1314.camerasnap;

import java.util.ArrayDeque;

final class PhotoPipeline {
    static final int MAX_PHOTOS = 100;
    private final long frameLimit, byteLimit;
    private int submitted;
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private long pendingBytes;
    private boolean inFlight, stopped;

    PhotoPipeline(long frameLimit) {
        if (frameLimit <= 0 || frameLimit > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid frame limit");
        this.frameLimit = frameLimit;
        byteLimit = frameLimit * 2;
    }

    boolean canCapture() {
        return !stopped && !inFlight && submitted < MAX_PHOTOS && pending.size() < 2 && pendingBytes <= byteLimit - frameLimit;
    }

    boolean beginCapture() {
        if (!canCapture()) return false;
        inFlight = true;
        submitted++;
        return true;
    }

    void imageReceived(long bytes) {
        if (!inFlight) throw new IllegalStateException("没有等待中的照片");
        if (bytes <= 0 || bytes > frameLimit || pendingBytes > byteLimit - bytes)
            throw new IllegalStateException("照片数据过大，请降低分辨率");
        inFlight = false;
        pending.addLast(bytes);
        pendingBytes += bytes;
    }

    void saved(long bytes) {
        if (pending.isEmpty() || bytes != pending.peekFirst()) throw new IllegalStateException("Invalid photo queue completion");
        pending.removeFirst();
        pendingBytes -= bytes;
    }

    boolean complete() { return submitted == MAX_PHOTOS && !inFlight && pending.isEmpty(); }
    boolean hasStarted() { return submitted != 0; }
    void stop() { stopped = true; }
}
