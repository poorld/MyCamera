package com.android.mycamera.model;

/**
 * Camera API types supported by the application
 */
public enum CameraApiType {
    CAMERA1("Camera1"),
    CAMERA2("Camera2"),
    CAMERAX("CameraX");
    
    private final String displayName;
    
    CameraApiType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}