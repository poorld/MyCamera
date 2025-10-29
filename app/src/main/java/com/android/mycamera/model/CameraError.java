package com.android.mycamera.model;

/**
 * Camera error types
 */
public enum CameraError {
    PERMISSION_DENIED("Permission denied"),
    CAMERA_NOT_AVAILABLE("Camera not available"),
    INITIALIZATION_FAILED("Camera initialization failed"),
    PREVIEW_FAILED("Preview start failed"),
    RECORDING_FAILED("Recording failed"),
    CAPTURE_FAILED("Capture failed"),
    CONFIGURATION_ERROR("Configuration error"),
    UNKNOWN_ERROR("Unknown error");
    
    private final String message;
    
    CameraError(String message) {
        this.message = message;
    }
    
    public String getMessage() {
        return message;
    }
}