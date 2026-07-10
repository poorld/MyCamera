package com.android.mycamera.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Camera resolution option. Keeps the original fixed constants while allowing
 * Camera2 to use device-reported custom sizes.
 */
public final class Resolution {
    public static final Resolution HD_720P = new Resolution(1280, 720, "720p HD", true);
    public static final Resolution FULL_HD_1080P = new Resolution(1920, 1080, "1080p Full HD", true);
    public static final Resolution QHD_2K = new Resolution(2560, 1440, "2K QHD", true);
    public static final Resolution UHD_4K = new Resolution(3840, 2160, "4K UHD", true);
    public static final Resolution VGA_640x480 = new Resolution(640, 480, "VGA", true);
    public static final Resolution QVGA_320x240 = new Resolution(320, 240, "QVGA", true);

    private static final Resolution[] FIXED_VALUES = {
            HD_720P,
            FULL_HD_1080P,
            QHD_2K,
            UHD_4K,
            VGA_640x480,
            QVGA_320x240
    };

    private final int width;
    private final int height;
    private final String displayName;
    private final boolean fixed;

    private Resolution(int width, int height, String displayName, boolean fixed) {
        this.width = width;
        this.height = height;
        this.displayName = displayName;
        this.fixed = fixed;
    }

    public static Resolution of(int width, int height) {
        for (Resolution resolution : FIXED_VALUES) {
            if (resolution.width == width && resolution.height == height) {
                return resolution;
            }
        }
        return new Resolution(width, height, width + "x" + height, false);
    }

    public static Resolution[] values() {
        return Arrays.copyOf(FIXED_VALUES, FIXED_VALUES.length);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isFixed() {
        return fixed;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Resolution)) return false;
        Resolution that = (Resolution) obj;
        return width == that.width && height == that.height;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height);
    }

    public static Resolution fromString(String resolutionString) {
        if (resolutionString == null) {
            return FULL_HD_1080P;
        }

        String[] parts = resolutionString.trim().toLowerCase().split("x");
        if (parts.length == 2) {
            try {
                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());
                return of(width, height);
            } catch (NumberFormatException ignored) {
                // Fall through to default.
            }
        }
        return FULL_HD_1080P;
    }
}
