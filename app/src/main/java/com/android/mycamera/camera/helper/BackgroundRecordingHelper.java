package com.android.mycamera.camera.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.manager.CameraManager;
import com.android.mycamera.camera.observer.CameraStateManager;
import com.android.mycamera.camera.observer.CameraStateObserver;
import com.android.mycamera.camera.strategy.BackgroundRecordingService;
import com.android.mycamera.camera.strategy.CameraStrategy;
import com.android.mycamera.model.CameraApiType;

public class BackgroundRecordingHelper {
    
    private static final String TAG = "BackgroundRecordingHelper";
    
    private final Context context;
    private final CameraManager cameraManager;
    private BackgroundRecordingService backgroundRecordingService;
    private boolean isServiceBound = false;

    private Callback callback;




    public interface Callback {
        void onServiceConn();

        void onServiceDisconn();
    }
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected: ");
            BackgroundRecordingService.LocalBinder binder = (BackgroundRecordingService.LocalBinder) service;
            backgroundRecordingService = binder.getService();
            isServiceBound = true;

            if (callback != null) {
                callback.onServiceConn();
            }

        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected: ");
            isServiceBound = false;
            backgroundRecordingService = null;
            callback.onServiceDisconn();
        }
    };
    
    public BackgroundRecordingHelper(Context context, CameraManager cameraManager) {
        this.context = context.getApplicationContext();
        this.cameraManager = cameraManager;
    }
    
    public void initialize(Callback callback) {
        this.callback = callback;
        if (isServiceBound && backgroundRecordingService != null) {
            callback.onServiceConn();
            return;
        }

        startAndBindService();
    }
    
    private void startAndBindService() {
        Intent serviceIntent = new Intent(context, BackgroundRecordingService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public void setCurrentStrategy(CameraStrategy currentStrategy) {
        Log.d(TAG, "setCurrentStrategy: ");
        if (isServiceBound) {
            backgroundRecordingService.setCurrentStrategy(currentStrategy);
        }
    }

    public void openCamera(CameraConfig currentConfig) {
        if (isServiceBound) {
            backgroundRecordingService.openCamera(currentConfig);
        }
    }
    
    public void startRecording() {
        Log.d(TAG, "startRecording: ");
        Log.d(TAG, "isServiceBound: " + isServiceBound);
        if (isServiceBound) {
            backgroundRecordingService.startRecording();
        }
    }
    
    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (isServiceBound) {
            backgroundRecordingService.stopRecording();
        }
    }

    public void startPreview(Object textureView) {
        if (isServiceBound) {
            backgroundRecordingService.startPreview(textureView);
        }
    }

    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (isServiceBound) {
            backgroundRecordingService.stopPreview();
        }
    }

    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        if (isServiceBound) {
            backgroundRecordingService.closeCamera();
        }
    }

    public boolean isBackgroundRecordingServiceReady() {
        return isServiceBound && backgroundRecordingService != null && backgroundRecordingService.isReady();
    }
    public boolean isBackgroundRecordingActive() {
        return isServiceBound && backgroundRecordingService != null && backgroundRecordingService.isRecording();
    }

    public void updateBackgroundRecordingConfig(CameraConfig config) {
        if (isServiceBound) {
            backgroundRecordingService.updateConfiguration(config);
        }
    }
    // public BackgroundRecordingService getService() {
    //     return backgroundRecordingService;
    // }
    
    public boolean isRecording() {
        boolean res = isServiceBound && backgroundRecordingService != null && backgroundRecordingService.isRecording();
        Log.d(TAG, "isRecording: " + res);
        return res;
    }
    
    public void release() {
        Log.d(TAG, "release: ");
        if (isServiceBound) {
            context.unbindService(serviceConnection);
            isServiceBound = false;
        }
        Intent serviceIntent = new Intent(context, BackgroundRecordingService.class);
        context.stopService(serviceIntent);
    }

}
