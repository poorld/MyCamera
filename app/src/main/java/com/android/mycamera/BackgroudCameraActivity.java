package com.android.mycamera;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.TextureView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

public class BackgroudCameraActivity extends BaseAct {

    public static final String TAG = "BackgroudCameraActivity";

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private TextureView textureView;
    private Button btnRecord;
    private boolean isRecording = false;
    private RecordService recordService;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected: ");
            RecordService.RecordBinder binder = (RecordService.RecordBinder) service;
            recordService = binder.getService();
            isBound = true;
            recordService.startPreview(textureView);

        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected: ");
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        textureView = findViewById(R.id.textureView);
        btnRecord = findViewById(R.id.btn_record);

        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        startServiceAndBind();

    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart: ");
        super.onStart();
        if (textureView.isAvailable()) {
            // TextureView is already available, you can proceed with camera setup
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    private void startServiceAndBind() {
        Log.d(TAG, "startServiceAndBind: ");
        Intent intent = new Intent(this, RecordService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording: isBound=" + isBound);
        if (isBound) {
            recordService.startRecording();
            btnRecord.setText("Stop");
            isRecording = true;
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (isBound) {
            recordService.stopRecording();
            btnRecord.setText("Record");
            isRecording = false;
        }
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // You can now set up the camera here if it wasn't already
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServiceAndBind();
            } else {
                // Handle permission denial
            }
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop: ");
        super.onStop();
        // if (isBound) {
        //     unbindService(connection);
        //     isBound = false;
        // }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}
