package com.android.mycamera.camera.strategy;

import android.view.Surface;
import android.view.TextureView;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Resolution;

import java.util.List;

/**
 * Strategy interface for different camera API implementations
 */
public interface CameraStrategy {
    
    /**
     * Open camera with given configuration
     */
    void openCamera(CameraConfig config);
    
    /**
     * Start camera preview on given surface
     */
    void startPreview(TextureView textureView, Object lifecycleOwner);
    
    /**
     * Stop camera preview
     */
    void stopPreview();
    
    /**
     * Start recording video
     */
    void startRecording();
    
    /**
     * Stop recording video
     */
    void stopRecording();
    
    /**
     * Capture photo
     */
    void capturePhoto();
    
    /**
     * Close camera and release resources
     */
    void closeCamera();
    
    /**
     * Get supported resolutions
     */
    List<Resolution> getSupportedResolutions();
    
    /**
     * Get supported frame rates
     */
    List<Integer> getSupportedFrameRates();
    
    /**
     * Get current camera state
     */
    CameraState getCurrentState();
    
    /**
     * Check if camera is available
     */
    boolean isCameraAvailable();
    
    /**
     * Switch camera ID
     */
    boolean switchCamera(String cameraId);
    
    /**
     * Toggle flash mode
     */
    boolean toggleFlash();
    
    /**
     * Check if flash is available
     */
    boolean isFlashAvailable();
    
    /**
     * Check if flash is enabled
     */
    boolean isFlashEnabled();

    void setFocusPoint(float x, float y);

    boolean isFocusSupported();
    
    /**
     * Add state change listener
     */
    void addStateListener(CameraStateListener listener);
    
    /**
     * Remove state change listener
     */
    void removeStateListener(CameraStateListener listener);
    
    /**
     * Update camera configuration
     */
    void updateConfiguration(CameraConfig config);
    
    /**
     * Interface for camera state listeners
     */
    interface CameraStateListener {
        void onStateChanged(CameraState state);
        void onError(String errorMessage);
        void onRecordingStarted();
        void onRecordingStopped();
        void onPhotoCaptured(String filePath);
        void onPreviewStarted();
    }
}