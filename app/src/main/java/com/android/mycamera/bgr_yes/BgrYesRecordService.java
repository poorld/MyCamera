package com.android.mycamera.bgr_yes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.TextureView;

import androidx.camera.video.Quality;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.android.mycamera.R;

public class BgrYesRecordService extends LifecycleService {

    public static final String TAG = "BgrYesRecordService";
    private static final String CHANNEL_ID = "BgrYesRecordServiceChannel";
    private ICameraHelper cameraHelper;
    private final IBinder binder = new BgrYesRecordBinder();

    public class BgrYesRecordBinder extends Binder {
        public BgrYesRecordService getService() {
            return BgrYesRecordService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                // .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("BGR Yes Recording Service")
                .setContentText("Recording video in the background.")
                .build();
        startForeground(1, notification);
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

    private String currentApi;

    public boolean isReady() {
        return cameraHelper != null;
    }

    public String getCurrentApi() {
        return currentApi;
    }


    public void switchCamera(String api, TextureView textureView, int width, int height, int fps) {

        if (cameraHelper != null) {
            cameraHelper.closeCamera();
            cameraHelper = null;
        }

        // Pass the service's own lifecycle to the helper
        androidx.lifecycle.LifecycleOwner lifecycleOwner = this;

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            createAndStartPreview(api, textureView, width, height, fps, lifecycleOwner);
        }, 300); // 200ms delay as a pragmatic solution
    }

    public void switchCameraX(String api, TextureView textureView, Quality quality, int fps, CameraXHelper.CameraInfoListener cameraInfoListener) {

        if (cameraHelper != null) {
            cameraHelper.closeCamera();
            cameraHelper = null;
        }

        // Pass the service's own lifecycle to the helper
        androidx.lifecycle.LifecycleOwner lifecycleOwner = this;

        // Give CameraX a moment to release resources asynchronously
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            createAndStartXPreview(api, textureView, quality, fps, lifecycleOwner, cameraInfoListener);
        }, 300); // 200ms delay as a pragmatic solution
    }

    private void createAndStartPreview(String api, TextureView textureView, int width, int height, int fps, androidx.lifecycle.LifecycleOwner lifecycleOwner) {
        this.currentApi = api;
        switch (api) {
            case "Camera1":
                cameraHelper = new Camera1Helper(this);
                break;
            case "Camera2":
                cameraHelper = new Camera2Helper(this);
                break;
        }

        if (cameraHelper != null) {
            cameraHelper.openCamera(width, height, fps);
            cameraHelper.startPreview(textureView, lifecycleOwner);
        }
    }

    private void createAndStartXPreview(String api, TextureView textureView, Quality quality, int fps, androidx.lifecycle.LifecycleOwner lifecycleOwner, CameraXHelper.CameraInfoListener cameraInfoListener) {
        this.currentApi = api;
        cameraHelper = new CameraXHelper(this);
        ((CameraXHelper) cameraHelper).setCameraInfoListener(cameraInfoListener);
        ((ICameraXHelper) cameraHelper).openCamera(quality, fps);
        cameraHelper.startPreview(textureView, lifecycleOwner);
    }

    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (cameraHelper != null) {
            cameraHelper.closeCamera();
            cameraHelper = null;
        }
    }

    public void startRecording() {
        Log.d(TAG, "startRecording: ");
        if (cameraHelper != null) {
            cameraHelper.startRecord();
        }
    }

    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (cameraHelper != null) {
            cameraHelper.stopRecord();
        }
    }

    @Override
    public void onDestroy() {
        if (cameraHelper != null) {
            cameraHelper.closeCamera();
            cameraHelper = null;
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "BGR Yes Recording Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
