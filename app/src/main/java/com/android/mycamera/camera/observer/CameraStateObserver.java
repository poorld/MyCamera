package com.android.mycamera.camera.observer;

import com.android.mycamera.model.CameraState;

/**
 * Interface for camera state observers
 */
public interface CameraStateObserver {
    
    /**
     * Called when camera state changes
     */
    void onCameraStateChanged(CameraState newState);
    
    /**
     * Called when camera encounters an error
     */
    void onCameraError(String errorMessage);
    
    /**
     * Called when recording starts
     */
    void onRecordingStarted();
    
    /**
     * Called when recording stops
     */
    void onRecordingStopped();
    
    /**
     * Called when photo is captured
     */
    void onPhotoCaptured(String filePath);
    
    /**
     * Called when preview starts
     */
    void onPreviewStarted();
}