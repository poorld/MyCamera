package com.android.mycamera.camera.strategy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.android.mycamera.R;
import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.factory.CameraHelperFactory;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CameraState;

public class BackgroundRecordingService extends LifecycleService {
    
    private static final String TAG = "BackgroundRecordingService";
    private static final String CHANNEL_ID = "BackgroundRecordingChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private final IBinder binder = new LocalBinder();
    private CameraStrategy cameraStrategy;

    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Ready"));
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    public CameraStrategy getCameraStrategy() {
        Log.d(TAG, "getCameraStrategy: cameraStrategy=" + cameraStrategy);
        return cameraStrategy;
    }

    public void setCurrentStrategy(CameraStrategy currentStrategy) {
        cameraStrategy = currentStrategy;
    }



    public class LocalBinder extends Binder {
        public BackgroundRecordingService getService() {
            return BackgroundRecordingService.this;
        }
    }


    public void openCamera(CameraConfig config) {
        Log.d(TAG, "openCamera: ");
        if (cameraStrategy == null) {
            return;
        }
        cameraStrategy.openCamera(config);
    }
    
    public boolean startRecording() {
        Log.d(TAG, "startRecording,cameraStrategy=" + cameraStrategy);
        if (cameraStrategy == null) {
            return false;
        }
        updateNotification("Recording...");
        cameraStrategy.startRecording();
        return true;
    }
    
    public boolean stopRecording() {
        if (cameraStrategy == null) {
            return false;
        }
        updateNotification("Ready");
        cameraStrategy.stopRecording();
        return true;
    }
    
    public boolean isReady() {
        Log.d(TAG, "isReady: "+ cameraStrategy.getCurrentState());
        return cameraStrategy != null && (cameraStrategy.getCurrentState() == CameraState.OPENED || cameraStrategy.getCurrentState() == CameraState.PREVIEW_STARTED);
    }
    
    public boolean isRecording() {
        Log.d(TAG, "isRecording: " + cameraStrategy.getCurrentState());
        return cameraStrategy != null && cameraStrategy.getCurrentState() == CameraState.RECORDING;
    }


    public void startPreview(Object textureView) {
        Log.d(TAG, "startPreview: ");
        cameraStrategy.startPreview((android.view.TextureView) textureView, this);
    }

    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        cameraStrategy.stopPreview();
    }

    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        cameraStrategy.closeCamera();
    }
    
    public void updateConfiguration(CameraConfig config) {
        if (cameraStrategy != null) {
            cameraStrategy.updateConfiguration(config);
        }
    }
    

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        if (cameraStrategy != null) {
            cameraStrategy.closeCamera();
        }
        super.onDestroy();
    }
    
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Background Recording",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    
    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Background Recording")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }
    
    private void updateNotification(String text) {
        Notification notification = createNotification(text);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }
}
