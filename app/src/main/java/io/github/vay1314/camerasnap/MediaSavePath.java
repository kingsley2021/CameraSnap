package io.github.vay1314.camerasnap;

import java.nio.charset.StandardCharsets;

final class MediaSavePath {
    static final String DEFAULT = "DCIM/Camera/Snap";

    static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("Missing save path");
        String path = input.trim();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] parts = path.split("/", -1);
        if (!parts[0].equals("DCIM"))
            throw new IllegalArgumentException("Expected a relative directory under DCIM, such as DCIM/Camera/Snap");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("."))
                throw new IllegalArgumentException("Path contains empty, hidden or relative components");
            if (!part.equals(part.trim()) || part.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IllegalArgumentException("Directory name has surrounding spaces or exceeds the length limit");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)
                    throw new IllegalArgumentException("Path contains unsupported characters");
            }
        }
        return path;
    }

    static String orDefault(String input) {
        try { return normalize(input); }
        catch (IllegalArgumentException e) { return DEFAULT; }
    }
}
