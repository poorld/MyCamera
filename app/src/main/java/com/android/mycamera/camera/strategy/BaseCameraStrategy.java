package com.android.mycamera.camera.strategy;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;

import com.android.mycamera.camera.error.CameraErrorHandler;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Resolution;

import java.util.ArrayList;
import java.util.List;

/**
 * Base implementation of CameraStrategy with common functionality
 * and enhanced error handling
 */
public abstract class BaseCameraStrategy implements CameraStrategy {

    public static final String TAG = "BaseCameraStrategy";
    
    protected final Context context;
    protected final List<CameraStateListener> stateListeners = new ArrayList<>();
    protected final CameraErrorHandler errorHandler;
    protected volatile CameraState currentState = CameraState.IDLE;
    private OrientationEventListener orientationEventListener;
    private int deviceOrientationDegrees = 0;
    
    public BaseCameraStrategy(Context context) {
        this.context = context.getApplicationContext();
        this.errorHandler = new CameraErrorHandler(context);
    }
    
    @Override
    public void addStateListener(CameraStateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }
    
    @Override
    public void removeStateListener(CameraStateListener listener) {
        stateListeners.remove(listener);
    }
    
    @Override
    public void updateConfiguration(com.android.mycamera.camera.config.CameraConfig config) {
        closeCamera();
        openCamera(config);
    }
    
    @Override
    public CameraState getCurrentState() {
        return currentState;
    }
    
    protected void notifyStateChanged(CameraState newState) {
        Log.d(TAG, "notifyStateChanged: " + this);
        Log.d(TAG, "notifyStateChanged: currentState=" + currentState + " ,newState=" + newState, new RuntimeException());
        if (currentState != newState) {
            currentState = newState;
            for (CameraStateListener listener : stateListeners) {
                listener.onStateChanged(newState);
            }
        }
    }
    
    protected void notifyError(String errorMessage) {
        notifyStateChanged(CameraState.ERROR);
        for (CameraStateListener listener : stateListeners) {
            listener.onError(errorMessage);
        }
    }
    
    protected void notifyError(Exception exception) {
        CameraErrorHandler.ErrorType errorType = errorHandler.getErrorTypeFromException(exception);
        String userMessage = errorHandler.getErrorMessage(errorType, exception.getMessage());
        notifyError(userMessage);
    }
    
    protected void notifyError(CameraErrorHandler.ErrorType errorType, String technicalDetails) {
        String userMessage = errorHandler.getErrorMessage(errorType, technicalDetails);
        notifyError(userMessage);
    }
    
    protected void notifyRecordingStarted() {
        notifyStateChanged(CameraState.RECORDING);
        for (CameraStateListener listener : stateListeners) {
            listener.onRecordingStarted();
        }
    }
    
    protected void notifyRecordingStopped() {
        notifyStateChanged(CameraState.OPENED);
        for (CameraStateListener listener : stateListeners) {
            listener.onRecordingStopped();
        }
    }
    
    protected void notifyPhotoCaptured(String filePath) {
        for (CameraStateListener listener : stateListeners) {
            listener.onPhotoCaptured(filePath);
        }
    }
    
    protected void notifyPreviewStarted() {
        for (CameraStateListener listener : stateListeners) {
            listener.onPreviewStarted();
        }
    }
    
    protected void logError(String message, Throwable throwable) {
        Log.e(getTag(), message, throwable);
    }
    
    protected void logDebug(String message) {
        Log.d(getTag(), message);
    }
    
    protected abstract String getTag();

    protected void startOrientationUpdates() {
        if (orientationEventListener == null) {
            orientationEventListener = new OrientationEventListener(context) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return;
                    }
                    deviceOrientationDegrees = ((orientation + 45) / 90 * 90) % 360;
                }
            };
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    protected void stopOrientationUpdates() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    protected int getCameraXTargetRotation() {
        switch ((360 - deviceOrientationDegrees) % 360) {
            case 90:
                return Surface.ROTATION_90;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_270;
            case 0:
            default:
                return Surface.ROTATION_0;
        }
    }

    protected int getVideoOrientationHint(String cameraId) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (sensorOrientation != null) {
                    return (sensorOrientation + deviceOrientationDegrees) % 360;
                }
            }
        } catch (CameraAccessException e) {
            logError("Failed to get camera sensor orientation", e);
        }
        return deviceOrientationDegrees;
    }
    
    @Override
    public List<Resolution> getSupportedResolutions() {
        List<Resolution> resolutions = new ArrayList<>();
        resolutions.add(Resolution.VGA_640x480);
        resolutions.add(Resolution.HD_720P);
        resolutions.add(Resolution.FULL_HD_1080P);
        resolutions.add(Resolution.QHD_2K);
        resolutions.add(Resolution.UHD_4K);

        return resolutions;
    }
    
    @Override
    public List<Integer> getSupportedFrameRates() {
        List<Integer> frameRates = new ArrayList<>();
        frameRates.add(15);
        frameRates.add(24);
        frameRates.add(30);
        frameRates.add(60);
        return frameRates;
    }
    
    @Override
    public boolean toggleFlash() {
        return false;
    }
    
    @Override
    public boolean isFlashAvailable() {
        return false;
    }
    
    @Override
    public boolean isFlashEnabled() {
        return false;
    }
}
