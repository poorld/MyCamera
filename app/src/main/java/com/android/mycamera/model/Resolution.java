package com.android.mycamera.model;

/**
 * Camera resolution options
 */
public enum Resolution {
    HD_720P(1280, 720, "720p HD"),
    FULL_HD_1080P(1920, 1080, "1080p Full HD"),
    UHD_4K(3840, 2160, "4K UHD"),
    VGA_640x480(640, 480, "VGA"),
    QVGA_320x240(320, 240, "QVGA");
    
    private final int width;
    private final int height;
    private final String displayName;
    
    Resolution(int width, int height, String displayName) {
        this.width = width;
        this.height = height;
        this.displayName = displayName;
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
    
    @Override
    public String toString() {
        return width + "x" + height;
    }
    
    public static Resolution fromString(String resolutionString) {
        String[] parts = resolutionString.split("x");
        if (parts.length == 2) {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            for (Resolution resolution : values()) {
                if (resolution.width == width && resolution.height == height) {
                    return resolution;
                }
            }
        }
        return FULL_HD_1080P; // Default
    }
}