package com.android.mycamera.bgr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.TextureView;

import androidx.core.app.NotificationCompat;

public class BackgroundRecordService extends Service {

    public static final String TAG = "RecordService";
    private static final String CHANNEL_ID = "RecordServiceChannel";
    private Camera2Helper camera2Helper;
    private final IBinder binder = new RecordBinder();

    public class RecordBinder extends Binder {
        public BackgroundRecordService getService() {
            return BackgroundRecordService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Recording Service")
                .setContentText("Recording video in the background.")
                .setSmallIcon(com.android.mycamera.R.mipmap.ic_launcher)
                .build();
        startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void startPreview(TextureView textureView) {
        Log.d(TAG, "startPreview: ");
        if (camera2Helper == null) {
            camera2Helper = new Camera2Helper(this, textureView);
            camera2Helper.startBackgroundThread();
            camera2Helper.openCamera();
        } else {
            camera2Helper.startPreview();
        }
    }

    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (camera2Helper != null) {
            camera2Helper.closeCamera();
            camera2Helper.stopBackgroundThread();
            camera2Helper = null;
        }
    }

    public void startRecording() {
        Log.d(TAG, "startRecording: camera2Helper=" + camera2Helper);
        if (camera2Helper != null) {
            camera2Helper.startRecording();
        }
    }

    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (camera2Helper != null) {
            camera2Helper.stopRecording();
        }
    }
    
    public void switchCamera() {
        Log.d(TAG, "switchCamera: ");
        if (camera2Helper != null) {
            camera2Helper.switchToNextCamera();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        if (camera2Helper != null) {
            camera2Helper.closeCamera();
            camera2Helper.stopBackgroundThread();
            camera2Helper = null;
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Recording Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
