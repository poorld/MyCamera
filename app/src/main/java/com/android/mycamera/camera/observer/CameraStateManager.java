package com.android.mycamera.camera.observer;

import android.util.Log;

import com.android.mycamera.model.CameraState;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages camera state observers and notifications
 */
public class CameraStateManager {

    private static final String TAG = "CameraStateManager";
    private final List<CameraStateObserver> observers = new ArrayList<>();
    private CameraState currentState = CameraState.IDLE;
    
    /**
     * Add observer
     */
    public void addObserver(CameraStateObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }
    
    /**
     * Remove observer
     */
    public void removeObserver(CameraStateObserver observer) {
        observers.remove(observer);
    }
    
    /**
     * Remove all observers
     */
    public void removeAllObservers() {
        observers.clear();
    }
    
    /**
     * Get current camera state
     */
    public CameraState getCurrentState() {
        return currentState;
    }
    
    /**
     * Set current state and notify observers
     */
    public void setState(CameraState newState) {
        Log.d(TAG, "setState: " + newState);
        if (currentState != newState) {
            CameraState oldState = currentState;
            currentState = newState;
            notifyStateChanged(oldState, newState);
        }
    }

    /**
     * Notify observers when a camera pipeline has been rebuilt without changing
     * its public state, such as OPENED -> OPENED after a mode switch.
     */
    public void forceNotifyState(CameraState state) {
        CameraState oldState = currentState;
        currentState = state;
        notifyStateChanged(oldState, state);
    }
    
    /**
     * Notify observers of state change
     */
    private void notifyStateChanged(CameraState oldState, CameraState newState) {
        Log.d(TAG, "notifyStateChanged: " + newState);

        for (CameraStateObserver observer : observers) {
            observer.onCameraStateChanged(newState);
        }
    }
    
    /**
     * Notify observers of error
     */
    public void notifyError(String errorMessage) {
        setState(CameraState.ERROR);
        for (CameraStateObserver observer : observers) {
            observer.onCameraError(errorMessage);
        }
    }
    
    /**
     * Notify observers that recording started
     */
    public void notifyRecordingStarted() {
        Log.d(TAG, "notifyRecordingStarted: ");
        setState(CameraState.RECORDING);
        for (CameraStateObserver observer : observers) {
            observer.onRecordingStarted();
        }
    }
    
    /**
     * Notify observers that recording stopped
     */
    public void notifyRecordingStopped() {
        setState(CameraState.PREVIEW_STARTED);
        for (CameraStateObserver observer : observers) {
            observer.onRecordingStopped();
        }
    }
    
    /**
     * Notify observers that photo was captured
     */
    public void notifyPhotoCaptured(String filePath) {
        for (CameraStateObserver observer : observers) {
            observer.onPhotoCaptured(filePath);
        }
    }
    
    /**
     * Notify observers that preview started
     */
    public void notifyPreviewStarted() {
        setState(CameraState.PREVIEW_STARTED);
        for (CameraStateObserver observer : observers) {
            observer.onPreviewStarted();
        }
    }

    

    
    
    /**
     * Get number of observers
     */
    public int getObserverCount() {
        return observers.size();
    }
}
