package com.android.mycamera.model;

/**
 * Target video bitrate options exposed by the recording settings.
 *
 * MediaRecorder treats this as a target bitrate. The codec's actual bitrate
 * mode remains platform-defined because MediaRecorder does not expose the
 * MediaFormat bitrate-mode controls.
 */
public enum VideoBitrate {
    // 720p H.265 / low-storage option.
    LOW("2 Mbps", 2_000_000),
    // 720p H.264 or 1080p H.265.
    STANDARD("4 Mbps", 4_000_000),
    // 2K H.265, midway between 1080p and 4K.
    QHD_H265("6 Mbps", 6_000_000),
    // Recommended 1080p H.264 target.
    HIGH("8 Mbps", 8_000_000),
    // Intermediate 2K target between 1080p and 4K.
    QHD("12 Mbps", 12_000_000),
    // Recommended 4K H.264 target.
    UHD("16 Mbps", 16_000_000);

    public static final VideoBitrate DEFAULT = HIGH;

    private final String displayName;
    private final int bitsPerSecond;

    VideoBitrate(String displayName, int bitsPerSecond) {
        this.displayName = displayName;
        this.bitsPerSecond = bitsPerSecond;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getBitsPerSecond() {
        return bitsPerSecond;
    }

    /**
     * Returns the recommended target bitrate for the selected resolution and codec.
     * Values follow the product targets: H.264 4/8/12/16 Mbps and H.265 half that.
     */
    public static VideoBitrate recommendedFor(Resolution resolution, VideoCodec codec) {
        if (resolution == null) {
            return DEFAULT;
        }
        boolean h265 = codec == VideoCodec.H265;
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        int bitsPerSecond;
        if (width >= 3840 || height >= 2160) {
            bitsPerSecond = h265 ? 8_000_000 : 16_000_000;
        } else if (width >= 2560 || height >= 1440) {
            bitsPerSecond = h265 ? 6_000_000 : 12_000_000;
        } else if (width >= 1920 || height >= 1080) {
            bitsPerSecond = h265 ? 4_000_000 : 8_000_000;
        } else {
            bitsPerSecond = h265 ? 2_000_000 : 4_000_000;
        }
        return forBitsPerSecond(bitsPerSecond);
    }

    private static VideoBitrate forBitsPerSecond(int bitsPerSecond) {
        for (VideoBitrate bitrate : values()) {
            if (bitrate.bitsPerSecond == bitsPerSecond) {
                return bitrate;
            }
        }
        return DEFAULT;
    }

    public static VideoBitrate fromName(String name) {
        if (name == null) {
            return DEFAULT;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
