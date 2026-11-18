package io.github.vay1314.camerasnap;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CaptureEngine {
    private static final long FEEDBACK_DELAY_MS = 10;
    private static final long FEEDBACK_DURATION_MS = 20;
    private final Context context;
    private final SnapConfig config;
    private final Runnable finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable captureNext = this::awaitPhoto;
    private final Runnable focusTimeout = this::onFocusTimeout;
    private final Runnable photoTimeout = () -> fail(AppText.failure(R.string.photo_timeout));
    private final Runnable checkRecordingSpace = this::checkRecordingSpace;
    private final Runnable startupTimeout = () -> fail(AppText.failure(R.string.camera_timeout));
    private CameraDevice device;
    private CameraCaptureSession session;
    private CameraCharacteristics characteristics;
    private ImageReader reader;
    private ImageReader previewReader;
    private StreamConfigurationMap videoMap;
    private StreamConfigurationMap photoMap;
    private String photoFormat;
    private AppText photoNote;
    private PhotoOptions.Resolution photoResolution;
    private final PhotoFallback photoFallback = new PhotoFallback();
    private boolean photoUseRequestedSize = true, photoUsesManualSize;
    private final PhotoPipeline photoPipeline = new PhotoPipeline(
            Math.max(1024 * 1024, Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)));
    private volatile boolean photoSaveFailed;
    private long lastPhotoRequestAt, readinessStarted;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFile;
    private Uri videoUri;
    private volatile boolean recording, closed;
    private boolean waitingForPhoto, photoInFlight;
    private int autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
    private long previewStarted, minimumFocusFrame;
    private volatile boolean spaceWarningLogged;
    private AppText stopReason;
    private long feedbackUntil;
    private int photos, orientation;
    private OrientationEventListener orientationListener;

    CaptureEngine(Context context, SnapConfig config, Runnable finished) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.finished = finished;
    }

    void start() {
        io.execute(() -> {
            try {
                if (closed) return;
                if (config.saveTree.isEmpty())
                    requireSpace(new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath()).getAvailableBytes(), 0);
                main.post(() -> { if (!closed) openCamera(); });
            } catch (Exception e) { main.post(() -> fail(e)); }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String id = PhotoOptions.cameraId(manager, config.cameraId);
            characteristics = manager.getCameraCharacteristics(id);
            orientationListener = new OrientationEventListener(context) {
                @Override public void onOrientationChanged(int degrees) {
                    if (degrees != ORIENTATION_UNKNOWN) orientation = ((degrees + 45) / 90 * 90) % 360;
                }
            };
            if (orientationListener.canDetectOrientation()) orientationListener.enable();
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw AppText.failure(R.string.no_output);
            if (config.mode == SnapConfig.VIDEO) videoMap = map;
            else {
                photoMap = map;
                String format = config.photoFormat;
                if (PhotoOptions.HEIC.equals(format) && PhotoOptions.supported(map, format).isEmpty()) {
                    format = PhotoOptions.JPEG;
                    photoNote = AppText.of(R.string.heic_missing);
                }
                try { configurePhotoOutputs(format); }
                catch (RuntimeException e) {
                    if (!PhotoOptions.HEIC.equals(format)) throw e;
                    photoNote = AppText.of(R.string.heic_unavailable);
                    Log.w(HookEntry.TAG, photoNote.render(context), e);
                    configurePhotoOutputs(PhotoOptions.JPEG);
                }
            }
            main.postDelayed(startupTimeout, 15_000);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (closed) { camera.close(); return; }
                    device = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (!closed) fail(AppText.failure(R.string.camera_disconnected));
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (!closed) fail(AppText.failure(R.string.camera_error, error));
                }
            }, main);
        } catch (Exception e) { fail(e); }
    }

    private void configurePhotoOutputs(String format) {
        cleanup(() -> { if (reader != null) reader.close(); });
        cleanup(() -> { if (previewReader != null) previewReader.close(); });
        reader = null;
        previewReader = null;
        photoFormat = format;
        java.util.List<PhotoOptions.Resolution> sizes = PhotoOptions.supported(photoMap, format);
        String requested = photoUseRequestedSize ? config.photoSize : "";
        photoUsesManualSize = !requested.isEmpty() && sizes.stream().anyMatch(size -> size.key().equals(requested));
        photoResolution = PhotoOptions.choose(sizes, requested);
        if (!requested.isEmpty() && !photoUsesManualSize)
            photoNote = AppText.of(R.string.size_unavailable).then(photoNote);
        reader = ImageReader.newInstance(photoResolution.width, photoResolution.height, PhotoOptions.imageFormat(format), 2);
        reader.setOnImageAvailableListener(this::imageAvailable, main);
        Size preview = choosePreviewSize(photoMap.getOutputSizes(ImageFormat.YUV_420_888));
        double ratio = (double) Math.max(photoResolution.width, photoResolution.height) /
                Math.min(photoResolution.width, photoResolution.height);
        Size[] previews = photoMap.getOutputSizes(ImageFormat.YUV_420_888);
        if (previews != null) preview = Arrays.stream(previews)
                .filter(size -> size.getWidth() <= 640 && size.getHeight() <= 480 &&
                        Math.abs((double) Math.max(size.getWidth(), size.getHeight()) /
                                Math.min(size.getWidth(), size.getHeight()) - ratio) < 0.02)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight())).orElse(preview);
        previewReader = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), ImageFormat.YUV_420_888, 2);
        previewReader.setOnImageAvailableListener(source -> {
            if (source != previewReader || closed) return;
            try (Image frame = source.acquireLatestImage()) { }
            catch (RuntimeException e) { if (!closed) fail(e); }
        }, main);
        Log.i(HookEntry.TAG, "Photo output: " + photoResolution.key() + ", format=" + photoFormat);
    }

    private boolean fallbackPhotoSession(Exception reason) {
        if (closed || config.mode != SnapConfig.PHOTO) return false;
        PhotoFallback.Choice fallback = photoFallback.next(photoFormat, photoUsesManualSize, photoPipeline.hasStarted());
        if (fallback == null) return false;
        photoUseRequestedSize = fallback.useSelectedSize;
        photoNote = AppText.of(fallback.message).then(photoNote);
        Log.w(HookEntry.TAG, context.getString(fallback.message), reason);
        try {
            cleanup(() -> { if (session != null) session.close(); });
            session = null;
            configurePhotoOutputs(fallback.format);
            main.removeCallbacks(startupTimeout);
            main.postDelayed(startupTimeout, 15_000);
            createSession();
        } catch (RuntimeException e) { fail(e); }
        return true;
    }

    private void createSession() {
        try {
            if (config.mode == SnapConfig.VIDEO) prepareVideo(videoMap);
            Surface output = config.mode == SnapConfig.VIDEO ? recorder.getSurface() : reader.getSurface();
            java.util.List<Surface> outputs = config.mode == SnapConfig.VIDEO ? Collections.singletonList(output) :
                    Arrays.asList(previewReader.getSurface(), output);
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (closed) { configured.close(); return; }
                    session = configured;
                    main.removeCallbacks(startupTimeout);
                    if (config.mode == SnapConfig.VIDEO) startVideo(output);
                    else {
                        try {
                            CaptureRequest.Builder preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            preview.addTarget(previewReader.getSurface());
                            autoControls(preview);
                            previewStarted = SystemClock.uptimeMillis();
                            configured.setRepeatingRequest(preview.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override public void onCaptureCompleted(CameraCaptureSession captureSession,
                                        CaptureRequest request, TotalCaptureResult result) {
                                    if (closed || !waitingForPhoto || SystemClock.uptimeMillis() < previewStarted + 200 ||
                                            result.getFrameNumber() < minimumFocusFrame) return;
                                    if (CaptureReadiness.ready(autofocusMode != CaptureRequest.CONTROL_AF_MODE_OFF,
                                            result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.CONTROL_AE_STATE),
                                            result.get(CaptureResult.CONTROL_AWB_STATE))) capturePhoto();
                                }
                            }, main);
                            main.post(captureNext);
                        } catch (Exception e) { if (!fallbackPhotoSession(e)) fail(e); }
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close();
                    if (!closed) {
                        Exception error = AppText.failure(R.string.session_failed);
                        if (!fallbackPhotoSession(error)) fail(error);
                    }
                }
            }, main);
        } catch (Exception e) { if (!fallbackPhotoSession(e)) fail(e); }
    }

    private void autoControls(CaptureRequest.Builder request) {
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int desired = config.mode == SnapConfig.VIDEO ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO :
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        autofocusMode = modes != null && Arrays.stream(modes).anyMatch(mode -> mode == desired) ? desired :
                modes != null && Arrays.stream(modes).anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_AUTO) ?
                        CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF;
        request.set(CaptureRequest.CONTROL_AF_MODE, autofocusMode);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private int rotation() {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        int adjustment = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? -orientation : orientation;
        return ((sensor == null ? 0 : sensor) + adjustment + 360) % 360;
    }

    private void awaitPhoto() {
        if (closed || photoSaveFailed || waitingForPhoto || photoInFlight || !photoPipeline.canCapture()) return;
        waitingForPhoto = true;
        readinessStarted = SystemClock.uptimeMillis();
        minimumFocusFrame = autofocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO ? Long.MAX_VALUE : 0;
        main.postDelayed(focusTimeout, 3000);
        if (autofocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
            try {
                CaptureRequest.Builder trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                trigger.addTarget(previewReader.getSurface());
                autoControls(trigger);
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(trigger.build(), null, main);
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(trigger.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureStarted(CameraCaptureSession captureSession,
                            CaptureRequest request, long timestamp, long frameNumber) {
                        if (!closed && waitingForPhoto) minimumFocusFrame = frameNumber;
                    }
                }, main);
            } catch (Exception e) { fail(e); }
        }
    }

    private void onFocusTimeout() {
        if (waitingForPhoto && !closed) {
            Log.w(HookEntry.TAG, "3A wait timed out; capturing with available settings");
            capturePhoto();
        }
    }

    private void capturePhoto() {
        if (closed || photoSaveFailed) return;
        if (!waitingForPhoto || photoInFlight) return;
        waitingForPhoto = false;
        main.removeCallbacks(focusTimeout);
        if (!photoPipeline.beginCapture()) return;
        photoInFlight = true;
        lastPhotoRequestAt = SystemClock.uptimeMillis();
        Log.d(HookEntry.TAG, "Photo 3A waitMs=" + (lastPhotoRequestAt - readinessStarted));
        main.postDelayed(photoTimeout, 15_000);
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            autoControls(request);
            request.set(CaptureRequest.JPEG_ORIENTATION, rotation());
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(CameraCaptureSession captureSession, CaptureRequest request,
                                                       android.hardware.camera2.CaptureFailure failure) {
                    if (!closed) fail(AppText.failure(R.string.photo_failed, failure.getReason()));
                }
            }, main);
        } catch (Exception e) { fail(e); }
    }

    private void imageAvailable(ImageReader source) {
        if (source != reader || closed || !photoInFlight || photoSaveFailed) return;
        byte[] encoded;
        try (Image image = source.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            photoPipeline.imageReceived(buffer.remaining());
            encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
        } catch (RuntimeException e) { if (!closed) fail(e); return; }
        catch (OutOfMemoryError e) { fail(AppText.failure(e, R.string.photo_memory)); return; }
        photoInFlight = false;
        main.removeCallbacks(photoTimeout);
        Log.d(HookEntry.TAG, "Photo outputMs=" + (SystemClock.uptimeMillis() - lastPhotoRequestAt) + ", bytes=" + encoded.length);
        long queuedAt = SystemClock.elapsedRealtime();
        io.execute(() -> {
            if (photoSaveFailed) {
                main.post(() -> photoPipeline.saved(encoded.length));
                return;
            }
            Uri uri = null;
            long saveStarted = SystemClock.elapsedRealtime();
            try {
                uri = insertMedia(false);
                ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
                if (descriptor == null) throw AppText.failure(R.string.photo_write);
                try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                    requireSpace(availableBytes(descriptor.getFileDescriptor()), encoded.length);
                    stream.write(encoded);
                }
                publish(uri);
                Log.d(HookEntry.TAG, "Photo saveMs=" + (SystemClock.elapsedRealtime() - saveStarted) +
                        ", queueWaitMs=" + (saveStarted - queuedAt));
                main.post(() -> {
                    photoPipeline.saved(encoded.length);
                    photos++;
                    report(AppText.of(R.string.photos_saved, photos,
                            PhotoOptions.HEIC.equals(photoFormat) ? "HEIC" : "JPG",
                            photoResolution.key(), config.destinationText()).then(photoNote));

                    if (PhotoFeedback.shouldVibrate(config.photoVibration, photos)) vibrateShort("photo_saved");
                    if (!closed) {
                        if (photoPipeline.complete()) finished.run();
                        else scheduleNextPhoto();
                    }
                });
            } catch (Exception e) {
                photoSaveFailed = true;
                main.post(() -> {
                    photoPipeline.saved(encoded.length);
                    if (closed) report(AppText.of(R.string.photo_save_failed, AppText.error(e)));
                    else fail(e);
                });
                if (uri != null) deleteMedia(uri);
            }
        });
        scheduleNextPhoto();
    }

    private void scheduleNextPhoto() {
        if (closed || photoSaveFailed || waitingForPhoto || photoInFlight) return;
        main.removeCallbacks(captureNext);
        if (photoPipeline.canCapture())
            main.postDelayed(captureNext, Math.max(0, lastPhotoRequestAt + 200 - SystemClock.uptimeMillis()));
    }

    private void prepareVideo(StreamConfigurationMap map) throws Exception {
        Size size = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
        videoUri = insertMedia(true);
        videoFile = context.getContentResolver().openFileDescriptor(videoUri, "w");
        if (videoFile == null) throw AppText.failure(R.string.video_create);
        long available = availableBytes(videoFile.getFileDescriptor());
        requireSpace(available, 1024 * 1024);
        if (!config.saveTree.isEmpty()) {
            try { Os.lseek(videoFile.getFileDescriptor(), 0, OsConstants.SEEK_CUR); }
            catch (android.system.ErrnoException e) {
                throw AppText.failure(e, R.string.video_folder);
            }
        }
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        recorder.setOrientationHint(rotation());
        recorder.setOutputFile(videoFile.getFileDescriptor());
        recorder.setMaxDuration((int) SnapConfig.MAX_SESSION_MS);
        long videoLimit = StoragePolicy.videoLimit(available);
        if (videoLimit > 0) recorder.setMaxFileSize(videoLimit);
        recorder.setOnErrorListener((recorder, what, extra) -> fail(AppText.failure(R.string.video_error, what)));
        recorder.setOnInfoListener((recorder, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(AppText.of(R.string.video_time_limit));
            else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop(AppText.of(R.string.video_size_limit));
        });
        recorder.prepare();
    }

    private void startVideo(Surface output) {
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(output);
            autoControls(request);
            session.setRepeatingRequest(request.build(), null, main);
            recorder.start();
            recording = true;
            main.postDelayed(checkRecordingSpace, 5000);
            vibrateShort("video_started");
            SnapConfig.status(context, R.string.video_recording);
        } catch (Exception e) { fail(e); }
    }

    private void vibrateShort(String event) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else vibrator = context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(HookEntry.TAG, "Capture vibration unavailable: " + event);
                return;
            }

            VibrationEffect effect = VibrationEffect.createWaveform(
                    new long[]{FEEDBACK_DELAY_MS, FEEDBACK_DURATION_MS}, -1);

            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build());
            } else vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build());

            feedbackUntil = SystemClock.uptimeMillis() + FEEDBACK_DELAY_MS + FEEDBACK_DURATION_MS + 100;

            Log.d(HookEntry.TAG, "Capture vibration requested: " + event +
                    ", delayMs=" + FEEDBACK_DELAY_MS + ", durationMs=" +
                    FEEDBACK_DURATION_MS + ", amplitude=default" +
                    ", usage=" + (Build.VERSION.SDK_INT >= 33 ? "HARDWARE_FEEDBACK" : "ALARM"));
        } catch (RuntimeException e) {
            Log.w(HookEntry.TAG, "Capture vibration failed", e);
        }
    }

    private Uri insertMedia(boolean video) throws Exception {
        String filename = (video ? "VID_" : "IMG_") +
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + "_SNAP" +
                (video ? ".mp4" : PhotoOptions.extension(photoFormat));
        if (!config.saveTree.isEmpty()) {
            Uri tree = Uri.parse(config.saveTree);
            boolean writable = context.getContentResolver().getPersistedUriPermissions().stream()
                    .anyMatch(permission -> permission.getUri().equals(tree) && permission.isWritePermission());
            if (!writable) throw AppText.failure(R.string.folder_permission);
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri document = DocumentsContract.createDocument(context.getContentResolver(), directory,
                    video ? "video/mp4" : PhotoOptions.mime(photoFormat), filename);
            if (document == null) throw AppText.failure(R.string.media_create);
            return document;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : PhotoOptions.mime(photoFormat));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, config.savePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw AppText.failure(R.string.media_create);
        return uri;
    }

    private void publish(Uri uri) {
        if (!config.saveTree.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (context.getContentResolver().update(uri, values, null, null) != 1)
            throw AppText.failure(R.string.media_save);
    }

    private void deleteMedia(Uri uri) {
        try {
            if (!config.saveTree.isEmpty()) DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            else context.getContentResolver().delete(uri, null, null);
        } catch (Exception e) { Log.w(HookEntry.TAG, "Cannot remove failed media", e); }
    }

    private void fail(Exception e) {
        if (closed) return;
        Log.e(HookEntry.TAG, "Capture failed", e);
        stopReason = AppText.of(R.string.capture_failed, AppText.error(e));
        SnapConfig.status(context, stopReason);
        finished.run();
    }

    void stop(AppText reason) {
        if (closed) return;
        stopReason = reason;
        SnapConfig.status(context, reason);
        finished.run();
    }

    private void report(AppText text) {
        SnapConfig.status(context, text.then(stopReason));
    }

    private static void requireSpace(long available, long incoming) {
        if (!StoragePolicy.canWrite(available, incoming))
            throw AppText.failure(R.string.storage_low);
    }

    private long availableBytes(FileDescriptor descriptor) {
        try {
            android.system.StructStatVfs stats = Os.fstatvfs(descriptor);
            return Math.multiplyExact(stats.f_bavail, stats.f_frsize);
        } catch (Exception e) {
            if (!spaceWarningLogged) {
                spaceWarningLogged = true;
                Log.w(HookEntry.TAG, "Destination free space unavailable; relying on write/recorder errors", e);
            }
            return -1;
        }
    }

    private void checkRecordingSpace() {
        if (closed || !recording || videoFile == null) return;
        FileDescriptor descriptor = videoFile.getFileDescriptor();
        io.execute(() -> {
            if (closed || !recording) return;
            long available = availableBytes(descriptor);
            main.post(() -> {
                if (closed || !recording) return;
                if (!StoragePolicy.canWrite(available, 1024 * 1024)) stop(AppText.of(R.string.video_storage_low));
                else main.postDelayed(checkRecordingSpace, 5000);
            });
        });
    }

    void close() { close(() -> { }); }

    void close(Runnable completed) {
        if (closed) return;
        closed = true;
        photoPipeline.stop();
        main.removeCallbacks(captureNext);
        main.removeCallbacks(startupTimeout);
        main.removeCallbacks(focusTimeout);
        main.removeCallbacks(photoTimeout);
        main.removeCallbacks(checkRecordingSpace);
        waitingForPhoto = false;
        if (orientationListener != null) orientationListener.disable();
        boolean validVideo = false;
        boolean wasRecording = recording;
        if (recorder != null) {
            try {
                if (recording) { recorder.stop(); validVideo = true; }
            } catch (RuntimeException e) {
                report(AppText.of(R.string.video_not_saved));
                Log.w(HookEntry.TAG, "Video stop failed", e);
            } finally {
                recording = false;
                try { recorder.release(); } catch (RuntimeException e) { Log.w(HookEntry.TAG, "Recorder release failed", e); }
            }
        }

        if (wasRecording) vibrateShort("video_stopped");
        cleanup(() -> { if (session != null) session.close(); });
        cleanup(() -> { if (device != null) device.close(); });
        cleanup(() -> { if (reader != null) reader.close(); });
        cleanup(() -> { if (previewReader != null) previewReader.close(); });
        if (videoFile != null) {
            try { videoFile.close(); } catch (Exception e) { validVideo = false; }
        }
        if (videoUri != null) {
            if (validVideo) {
                try { publish(videoUri); report(AppText.of(R.string.video_saved, config.destinationText())); }
                catch (RuntimeException e) { deleteMedia(videoUri); report(AppText.of(R.string.video_save_failed, AppText.error(e))); }
            } else deleteMedia(videoUri);
        }

        io.execute(() -> main.post(() -> main.postDelayed(completed,
                Math.max(0, feedbackUntil - SystemClock.uptimeMillis()))));
        io.shutdown();
    }

    private void cleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { Log.w(HookEntry.TAG, "Resource cleanup failed", e); }
    }

    static Size chooseVideoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw AppText.failure(R.string.no_video_size);
        return Arrays.stream(sizes).filter(size -> size.getWidth() <= 1920 && size.getHeight() <= 1080 &&
                        size.getWidth() % 2 == 0 && size.getHeight() % 2 == 0)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                .orElseThrow(() -> AppText.failure(R.string.no_compatible_video));
    }

    static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw AppText.failure(R.string.no_preview);
        Comparator<Size> area = Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight());
        return Arrays.stream(sizes).filter(size -> size.getWidth() <= 640 && size.getHeight() <= 480)
                .max(area).orElseGet(() -> Arrays.stream(sizes).min(area)
                        .orElseThrow(() -> AppText.failure(R.string.no_preview)));
    }
}
