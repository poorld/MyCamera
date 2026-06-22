package com.android.mycamera.model;

/**
 * Video quality options
 */
public enum Quality {
    HD("HD(1280x720)", 1280, 720),
    FULL_HD("Full HD(1920x1080)", 1920, 1080),
    UHD("4K UHD(3840x2160)", 3840, 2160),
    QHD("2k QHD(2560x1440)", 2560, 1440),
    SD("SD(720x480)", 720, 480),
    LOWEST("Lowest(320x240)", 320, 240),
    HIGHEST("Highest(4090x2160)", 4096, 2160);
    
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
}