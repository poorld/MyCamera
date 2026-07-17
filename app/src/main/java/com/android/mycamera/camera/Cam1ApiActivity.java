package com.android.mycamera.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;



/**
 * Camera API
 */
public class Cam1ApiActivity extends BaseAct {

    private static final String TAG = "Cam1ApiActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;

    private Camera mCamera;
    private Cam1ApiPreview mPreview;
    private FrameLayout cameraPreviewLayout;
    private int currentCameraIndex = 0;
    private int cameraCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cam1_api);

        cameraPreviewLayout = findViewById(R.id.camera_preview);
        Button captureButton = findViewById(R.id.button_capture);
        Button switchCameraButton = findViewById(R.id.switchCameraButton);

        // 检查相机权限
        if (checkCameraPermission()) {
            // 有权限，初始化相机
            // mCamera = getCameraInstance();
            // if (mCamera != null) {
            //     setupCameraPreview();
            // }
        } else {
            // 没有权限，请求权限
            requestCameraPermission();
        }

        captureButton.setOnClickListener(v -> {
            if (mCamera != null) {
                mCamera.takePicture(null, null, mPicture);
            }
        });
        
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: ");
        // 有权限，初始化相机
        mCamera = getCameraInstance();
        Log.d(TAG, "mCamera: " + mCamera);
        if (mCamera != null) {
            setupCameraPreview();
        }
    }

    private void setupCameraPreview() {
        Log.d(TAG, "setupCameraPreview: ");
        mPreview = new Cam1ApiPreview(this, mCamera);
        cameraPreviewLayout.addView(mPreview);
    }

    // 检查相机权限
    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // 请求权限
    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                CAMERA_PERMISSION_CODE);
    }

    // 处理权限请求结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                mCamera = getCameraInstance();
                if (mCamera != null) {
                    setupCameraPreview();
                }
            } else {
                // 权限被拒绝
                Toast.makeText(this, "权限被拒绝，无法使用相机", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 安全地获取相机实例的方法
    public Camera getCameraInstance() {
        Log.d(TAG, "getCameraInstance: ");

        cameraCount = Camera.getNumberOfCameras();
        Log.d(TAG, "numCameras: " + cameraCount);
        if (cameraCount == 0) {
            Log.e(TAG, "No cameras found on this device.");
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show();
            return null;
        }

        Camera c = null;
        try {
            c = Camera.open(currentCameraIndex); // 尝试获取指定摄像头实例
        } catch (Exception e) {
            // 相机不可用（正在使用或不存在）
            Log.e(TAG, "相机不可用: " + e.getMessage());
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show();
        }
        return c; // 返回 null 如果相机不可用
    }

    // 处理拍照回调
    private Camera.PictureCallback mPicture = (data, camera) -> {
        File pictureFile = Utils.getOutputMediaFile(this);
        if (pictureFile == null) {
            Log.d(TAG, "错误：检查存储权限，无法创建文件");
            return;
        }

        try {
            FileOutputStream fos = new FileOutputStream(pictureFile);
            fos.write(data);
            fos.close();
            Toast.makeText(this, "图片已保存: " + pictureFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            // 重新开始预览
            mCamera.startPreview();
        } catch (FileNotFoundException e) {
            Log.d(TAG, "文件未找到: " + e.getMessage());
        } catch (IOException e) {
            Log.d(TAG, "写入文件时出错: " + e.getMessage());
        }
    };


    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: ");
        releaseCameraAndPreview(); // 在 onPause() 中释放相机，以便其他应用可以使用
    }

    private void releaseCameraAndPreview() {
        Log.d(TAG, "releaseCameraAndPreview: ");
        if (mCamera != null) {
            // 1. 停止预览 (最重要的一步)
            // 在进行任何清理之前，首先告诉相机停止发送预览帧。
            // 这是一个优雅的关闭流程，可以防止在某些设备上出现 RuntimeException。
            try {
                mCamera.stopPreview();
            } catch (Exception e) {
                // 如果预览已经停止或从未开始，可能会抛出异常，忽略即可。
                Log.e(TAG, "Error stopping camera preview", e);
            }

            // 2. 断开与UI的连接
            if (mPreview != null) {
                // 将预览视图从其父容器中移除
                cameraPreviewLayout.removeView(mPreview);
                mPreview = null;
            }

            // 3. 释放相机硬件资源
            // 此时相机已经处于空闲状态，可以安全地释放。
            mCamera.release();
            mCamera = null;
        }
    }
    
    private void switchCamera() {
        if (cameraCount <= 1) {
            Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 释放当前相机
        releaseCameraAndPreview();
        
        // 切换到下一个摄像头
        currentCameraIndex = (currentCameraIndex + 1) % cameraCount;
        
        Log.d(TAG, "Switching to camera index: " + currentCameraIndex);
        
        // 重新初始化相机
        mCamera = getCameraInstance();
        if (mCamera != null) {
            setupCameraPreview();
            Toast.makeText(this, "切换到摄像头 " + currentCameraIndex, Toast.LENGTH_SHORT).show();
        }
    }
}
