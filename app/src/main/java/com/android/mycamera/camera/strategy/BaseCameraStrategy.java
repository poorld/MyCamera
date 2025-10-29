package com.android.mycamera.camera.strategy;

import android.content.Context;
import android.util.Log;

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
    
    @Override
    public List<Resolution> getSupportedResolutions() {
        List<Resolution> resolutions = new ArrayList<>();
        resolutions.add(Resolution.VGA_640x480);
        resolutions.add(Resolution.HD_720P);
        resolutions.add(Resolution.FULL_HD_1080P);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            resolutions.add(Resolution.UHD_4K);
        }
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