package com.android.mycamera.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Still-photo resolution options.
 * cam0 (main): high-res 12M/36M/48M/64M
 * cam1 (sub): 12M/4M/3M/2M/1M set matching device capability
 */
public enum PhotoResolution {
    // cam0
    MP_12("12M(4:3)", 4032, 3024),
    MP_36("36M(4:3)", 6912, 5184),
    MP_48("48M(4:3)", 8000, 6000),
    MP_64("64M(4:3)", 9216, 6912),

    // cam1
    MP_4_16_9("4M(16:9)", 2560, 1440),
    MP_3_4_3("3M(4:3)", 1920, 1440),
    MP_2_16_9("2M(16:9)", 1920, 1080),
    MP_1_4_3("1M(4:3)", 1280, 960),
    MP_1_16_9("1M(16:9)", 1280, 720);

    /** Default for main camera (cam0). */
    public static final PhotoResolution DEFAULT = MP_12;
    /** Default for sub camera (cam1). */
    public static final PhotoResolution DEFAULT_CAM1 = MP_4_16_9;

    private static final List<PhotoResolution> SELECTABLE_CAM0 = Collections.unmodifiableList(
            Arrays.asList(MP_12, MP_36, MP_48, MP_64)
    );
    private static final List<PhotoResolution> SELECTABLE_CAM1 = Collections.unmodifiableList(
            Arrays.asList(MP_12, MP_4_16_9, MP_3_4_3, MP_2_16_9, MP_1_4_3, MP_1_16_9)
    );

    private final String label;
    private final int width;
    private final int height;

    PhotoResolution(String label, int width, int height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public String getLabel() {
        return label;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getDisplayName() {
        return label + " " + width + "x" + height;
    }

    public static boolean isSubCamera(String cameraId) {
        return cameraId != null && !"0".equals(cameraId.trim());
    }

    public static PhotoResolution defaultForCamera(String cameraId) {
        return isSubCamera(cameraId) ? DEFAULT_CAM1 : DEFAULT;
    }

    public static List<PhotoResolution> selectableValues() {
        return SELECTABLE_CAM0;
    }

    public static List<PhotoResolution> selectableValuesForCamera(String cameraId) {
        return isSubCamera(cameraId) ? SELECTABLE_CAM1 : SELECTABLE_CAM0;
    }

    public static PhotoResolution fromName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return DEFAULT;
        }
        try {
            return PhotoResolution.valueOf(name.trim());
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    /** Normalize against cam0 list (legacy). Prefer {@link #normalize(PhotoResolution, String)}. */
    public static PhotoResolution normalize(PhotoResolution value) {
        return normalize(value, "0");
    }

    public static PhotoResolution normalize(PhotoResolution value, String cameraId) {
        List<PhotoResolution> allowed = selectableValuesForCamera(cameraId);
        if (value != null && allowed.contains(value)) {
            return value;
        }
        return defaultForCamera(cameraId);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
