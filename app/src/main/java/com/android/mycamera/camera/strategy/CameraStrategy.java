package com.android.mycamera.camera.strategy;

import android.view.Surface;
import android.view.TextureView;
import android.util.Range;

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

    default boolean isZoomSupported() { return false; }

    default float getMinZoom() { return 1f; }

    default float getMaxZoom() { return 1f; }

    default float getZoom() { return 1f; }

    default void setZoom(float zoomRatio) { }

    /** Returns whether the active camera supports manual ISO and exposure time. */
    default boolean isManualExposureSupported() { return false; }

    default Range<Integer> getSupportedIsoRange() { return null; }

    default Range<Long> getSupportedExposureTimeRange() { return null; }

    default int getManualIso() { return 100; }

    default long getManualExposureTimeNs() { return 10_000_000L; }

    default boolean isManualExposureEnabled() { return false; }

    default void setManualExposure(int iso, long exposureTimeNs) { }

    default void resetAutoExposure() { }

    /** Returns whether the active video pipeline supports lens focus control. */
    default boolean isVideoFocusSupported() { return false; }

    /** Returns the nearest-focus limit in diopters (0 means no controllable lens). */
    default float getVideoMinimumFocusDistance() { return 0f; }

    /** Returns the requested video lens position in diopters. Zero is infinity. */
    default float getVideoFocusDistance() { return 0f; }

    default boolean isVideoManualFocusEnabled() { return false; }

    /** Returns whether a video tap-to-focus lock is currently active. */
    default boolean isVideoTapFocusActive() { return false; }

    /** Applies a manual video lens position in diopters. */
    default void setVideoFocusDistance(float focusDistance) { }

    /** Restores the default fixed-infinity video focus. */
    default void resetVideoFocus() { }

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
     * Push latest config into the live strategy without forcing an open/close.
     * Used when capture mode flips for recording so stop-preview rebuilds the right pipeline.
     */
    default void bindConfiguration(CameraConfig config) {}

    /**
     * Return a best-effort snapshot for the recording diagnostics screen.
     */
    default RecordingStats getRecordingStats() {
        return RecordingStats.idle(getClass().getSimpleName());
    }
    
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
