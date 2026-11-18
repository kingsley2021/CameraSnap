package io.github.vay1314.camerasnap;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PhotoOptions {
    static final String JPEG = "jpeg", HEIC = "heic";
    private static final long TARGET_PIXELS = 12_500_000;
    private static final long AUTO_MAX_PIXELS = 13_500_000;

    static final class Resolution {
        final int width, height;
        Resolution(int width, int height) {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid photo dimensions");
            this.width = width;
            this.height = height;
        }
        long pixels() { return (long) width * height; }
        String key() { return width + "x" + height; }
        boolean isFourThree() {
            return Math.abs((double) Math.max(width, height) / Math.min(width, height) - 4.0 / 3) < 0.015;
        }
        String label() {
            return label(Locale.ROOT);
        }
        String label(Locale locale) {
            int larger = Math.max(width, height), smaller = Math.min(width, height);
            int a = larger, b = smaller;
            while (b != 0) { int remainder = a % b; a = b; b = remainder; }
            return String.format(locale, "%d×%d · %.2f MP · %d:%d",
                    width, height, pixels() / 1_000_000.0, larger / a, smaller / a);
        }
    }

    static String format(String value) { return HEIC.equals(value) ? HEIC : JPEG; }
    static int imageFormat(String value) { return HEIC.equals(value) ? ImageFormat.HEIC : ImageFormat.JPEG; }
    static String mime(String value) { return HEIC.equals(value) ? "image/heic" : "image/jpeg"; }
    static String extension(String value) { return HEIC.equals(value) ? ".heic" : ".jpg"; }

    static String cameraId(CameraManager manager, String selected) throws android.hardware.camera2.CameraAccessException {
        if (!selected.isEmpty()) return selected;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        throw AppText.failure(R.string.no_back_camera);
    }

    static List<Resolution> supported(StreamConfigurationMap map, String format) {
        if (map == null) return Collections.emptyList();
        Map<String, Resolution> unique = new LinkedHashMap<>();
        int imageFormat = imageFormat(format);
        Size[] normal;
        try { normal = map.getOutputSizes(imageFormat); }
        catch (IllegalArgumentException e) { return Collections.emptyList(); }
        addSizes(unique, normal);
        try { addSizes(unique, map.getHighResolutionOutputSizes(imageFormat)); }
        catch (IllegalArgumentException ignored) { }
        List<Resolution> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingLong(Resolution::pixels).reversed().thenComparing(Resolution::key));
        return result;
    }

    private static void addSizes(Map<String, Resolution> output, Size[] sizes) {
        if (sizes == null) return;
        for (Size size : sizes) {
            if (size.getWidth() <= 0 || size.getHeight() <= 0) continue;
            Resolution resolution = new Resolution(size.getWidth(), size.getHeight());
            output.put(resolution.key(), resolution);
        }
    }

    static Resolution choose(List<Resolution> sizes, String selected) {
        if (sizes.isEmpty()) throw AppText.failure(R.string.no_photo_size);
        for (Resolution size : sizes) if (size.key().equals(selected)) return size;
        Comparator<Resolution> nearTarget = Comparator.comparingLong((Resolution size) -> Math.abs(size.pixels() - TARGET_PIXELS))
                .thenComparing(Resolution::key);
        return sizes.stream().filter(size -> size.isFourThree() && size.pixels() >= 7_500_000 && size.pixels() <= AUTO_MAX_PIXELS)
                .min(nearTarget).orElseGet(() -> sizes.stream().filter(size -> size.pixels() <= AUTO_MAX_PIXELS)
                        .max(Comparator.comparingLong(Resolution::pixels)).orElseGet(() -> sizes.stream()
                                .min(Comparator.comparingLong(Resolution::pixels)).orElseThrow()));
    }
}
