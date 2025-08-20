package com.android.mycamera.bgr;

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
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

public class BackgroudCameraActivity extends BaseAct {

    public static final String TAG = "BackgroudCameraActivity";

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private TextureView textureView;
    private Button btnRecord;
    private Button switchCameraButton;
    private boolean isRecording = false;
    private BackgroundRecordService backgroundRecordService;
    private boolean isBound = false;
    private String currentCameraId = "0";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected: ");
            BackgroundRecordService.RecordBinder binder = (BackgroundRecordService.RecordBinder) service;
            backgroundRecordService = binder.getService();
            isBound = true;
            backgroundRecordService.startPreview(textureView);

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
        switchCameraButton = findViewById(R.id.switchCameraButton);

        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
        
        switchCameraButton.setOnClickListener(v -> switchCamera());

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
        Intent intent = new Intent(this, BackgroundRecordService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording: isBound=" + isBound);
        if (isBound) {
            backgroundRecordService.startRecording();
            btnRecord.setText("Stop");
            isRecording = true;
        }
    }
    
    private void switchCamera() {
        if (isRecording) {
            Toast.makeText(this, "录制中无法切换摄像头", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            android.hardware.camera2.CameraManager cameraManager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();
            
            if (cameraIds.length <= 1) {
                Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 找到当前摄像头的索引
            int currentIndex = -1;
            for (int i = 0; i < cameraIds.length; i++) {
                if (cameraIds[i].equals(currentCameraId)) {
                    currentIndex = i;
                    break;
                }
            }
            
            if (currentIndex == -1) {
                currentIndex = 0;
            }
            
            // 切换到下一个摄像头
            int nextIndex = (currentIndex + 1) % cameraIds.length;
            currentCameraId = cameraIds[nextIndex];
            
            Log.d(TAG, "Switching to camera ID: " + currentCameraId);
            
            // 重新启动预览
            if (isBound) {
                backgroundRecordService.switchCamera();
            }
            
            Toast.makeText(this, "切换到摄像头 " + currentCameraId, Toast.LENGTH_SHORT).show();
            
        } catch (android.hardware.camera2.CameraAccessException e) {
            Log.e(TAG, "切换摄像头失败", e);
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (isBound) {
            backgroundRecordService.stopRecording();
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
        // if (!isRecording && isBound) {
        //     backgroundRecordService.stopPreview();
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
