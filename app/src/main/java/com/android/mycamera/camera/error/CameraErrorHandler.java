package com.android.mycamera.camera.error;

import android.content.Context;

/**
 * Centralized error handling for camera operations
 */
public class CameraErrorHandler {
    
    public enum ErrorType {
        PERMISSION_DENIED,
        CAMERA_NOT_AVAILABLE,
        HARDWARE_ERROR,
        INITIALIZATION_FAILED,
        RECORDING_FAILED,
        PHOTO_CAPTURE_FAILED,
        PREVIEW_FAILED,
        CONFIGURATION_ERROR,
        RESOURCE_BUSY,
        UNKNOWN_ERROR
    }
    
    private final Context context;
    
    public CameraErrorHandler(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Get user-friendly error message for error type
     */
    public String getErrorMessage(ErrorType errorType, String technicalDetails) {
        String baseMessage;
        
        switch (errorType) {
            case PERMISSION_DENIED:
                baseMessage = "Camera permission is required to use this feature";
                break;
            case CAMERA_NOT_AVAILABLE:
                baseMessage = "Camera is not available or is being used by another app";
                break;
            case HARDWARE_ERROR:
                baseMessage = "Camera hardware error occurred";
                break;
            case INITIALIZATION_FAILED:
                baseMessage = "Failed to initialize camera";
                break;
            case RECORDING_FAILED:
                baseMessage = "Failed to start/stop recording";
                break;
            case PHOTO_CAPTURE_FAILED:
                baseMessage = "Failed to capture photo";
                break;
            case PREVIEW_FAILED:
                baseMessage = "Failed to start camera preview";
                break;
            case CONFIGURATION_ERROR:
                baseMessage = "Camera configuration error";
                break;
            case RESOURCE_BUSY:
                baseMessage = "Camera resources are busy";
                break;
            case UNKNOWN_ERROR:
            default:
                baseMessage = "An unknown camera error occurred";
                break;
        }
        
        // Add technical details if available and in debug mode
        if (technicalDetails != null && !technicalDetails.isEmpty()) {
            return baseMessage + " (" + technicalDetails + ")";
        }
        
        return baseMessage;
    }
    
    /**
     * Determine error type from exception
     */
    public ErrorType getErrorTypeFromException(Exception exception) {
        if (exception == null) {
            return ErrorType.UNKNOWN_ERROR;
        }
        
        String exceptionName = exception.getClass().getSimpleName();
        String message = exception.getMessage();
        
        if (exception instanceof SecurityException) {
            return ErrorType.PERMISSION_DENIED;
        }
        
        if (exception instanceof IllegalStateException) {
            if (message != null && message.contains("busy")) {
                return ErrorType.RESOURCE_BUSY;
            }
            return ErrorType.CAMERA_NOT_AVAILABLE;
        }
        
        if (exception instanceof IllegalArgumentException) {
            return ErrorType.CONFIGURATION_ERROR;
        }
        
        if (exception instanceof RuntimeException) {
            if (message != null) {
                if (message.contains("initialize") || message.contains("init")) {
                    return ErrorType.INITIALIZATION_FAILED;
                }
                if (message.contains("record")) {
                    return ErrorType.RECORDING_FAILED;
                }
                if (message.contains("capture")) {
                    return ErrorType.PHOTO_CAPTURE_FAILED;
                }
                if (message.contains("preview")) {
                    return ErrorType.PREVIEW_FAILED;
                }
            }
        }
        
        // Default to hardware error for camera-specific exceptions
        if (exceptionName.contains("Camera") || 
            (message != null && message.toLowerCase().contains("camera"))) {
            return ErrorType.HARDWARE_ERROR;
        }
        
        return ErrorType.UNKNOWN_ERROR;
    }
    
    /**
     * Check if error is recoverable
     */
    public boolean isRecoverable(ErrorType errorType) {
        switch (errorType) {
            case PERMISSION_DENIED:
            case CONFIGURATION_ERROR:
            case RESOURCE_BUSY:
                return true;
            case CAMERA_NOT_AVAILABLE:
            case HARDWARE_ERROR:
            case INITIALIZATION_FAILED:
                return false; // These typically require app restart
            case RECORDING_FAILED:
            case PHOTO_CAPTURE_FAILED:
            case PREVIEW_FAILED:
                return true; // Can retry
            case UNKNOWN_ERROR:
            default:
                return false;
        }
    }
    
    /**
     * Get suggested action for error recovery
     */
    public String getRecoveryAction(ErrorType errorType) {
        switch (errorType) {
            case PERMISSION_DENIED:
                return "Please grant camera permission in app settings";
            case CAMERA_NOT_AVAILABLE:
                return "Please close other camera apps and try again";
            case HARDWARE_ERROR:
                return "Please restart your device and try again";
            case INITIALIZATION_FAILED:
                return "Please restart the app and try again";
            case RECORDING_FAILED:
                return "Please try recording again";
            case PHOTO_CAPTURE_FAILED:
                return "Please try capturing photo again";
            case PREVIEW_FAILED:
                return "Please try starting preview again";
            case CONFIGURATION_ERROR:
                return "Please check camera settings and try again";
            case RESOURCE_BUSY:
                return "Please wait a moment and try again";
            case UNKNOWN_ERROR:
            default:
                return "Please restart the app and try again";
        }
    }
}