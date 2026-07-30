package com.android.mycamera.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Video quality options
 */
public enum Quality {
    HD("HD (1280x720)", 1280, 720),
    FULL_HD("FHD (1920x1080)", 1920, 1080),
    UHD("UHD (3840x2160)", 3840, 2160),
    QHD("2K (2560x1440)", 2560, 1440),
    SD("SD (720x480)", 720, 480),
    LOWEST("LOWEST (320x240)", 320, 240),
    HIGHEST("HIGHEST (4096x2160)", 4096, 2160);

    /** Default CameraX quality. */
    public static final Quality DEFAULT = FULL_HD;

    private static final List<Quality> SELECTABLE = Collections.unmodifiableList(
            Arrays.asList(QHD, FULL_HD, HD)
    );
    
    private final String displayName;
    private final int width;
    private final int height;
    
    Quality(String displayName, int width, int height) {
        this.displayName = displayName;
        this.width = width;
        this.height = height;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }

    /**
     * Qualities exposed in settings UI.
     */
    public static List<Quality> selectableValues() {
        return SELECTABLE;
    }

    /**
     * Map any stored quality to one of the selectable values.
     */
    public static Quality normalizeSelectable(Quality quality) {
        if (quality == null) {
            return DEFAULT;
        }
        if (SELECTABLE.contains(quality)) {
            return quality;
        }
        if (quality == UHD || quality == HIGHEST) {
            return QHD;
        }
        if (quality == SD || quality == LOWEST) {
            return HD;
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return displayName;
    }
}