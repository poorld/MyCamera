package com.android.mycamera.model;

/**
 * App capture pipeline mode.
 * Photo uses still/JPEG streams; video uses a video-encoder consumer so MTK HAL
 * selects video sensor mode (hasVideoConsumer / videoImageSize).
 */
public enum CaptureMode {
    PHOTO,
    VIDEO;

    public static CaptureMode normalize(CaptureMode mode) {
        return mode == null ? PHOTO : mode;
    }

    public boolean isVideo() {
        return this == VIDEO;
    }
}
