package com.android.mycamera.model;

/**
 * Camera state enumeration
 */
public enum CameraState {
    IDLE("Idle"),
    INITIALIZING("Initializing"),
    OPENED("Camera Opened"),
    PREVIEW_STARTED("Preview Started"),
    RECORDING("Recording"),
    CAPTURING("Capturing"),
    ERROR("Error"),
    CLOSED("Camera Closed");
    
    private final String displayName;
    
    CameraState(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}