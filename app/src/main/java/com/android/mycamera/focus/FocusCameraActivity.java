package com.android.mycamera.focus;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.Utils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

// 选择相机ID为2的相机
public class FocusCameraActivity extends BaseAct {

    private static final String TAG = "FocusCamera";

    private PreviewView previewView;
    private FocusView focusView;
    private ImageCapture imageCapture;
    private FocusHelper focusHelper;
    private String currentCameraId = "0"; // 默认使用第一个摄像头

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_focus_camera);

        previewView = findViewById(R.id.previewView);
        focusView = findViewById(R.id.focusView);
        
        // 初始化聚焦帮助类
        focusHelper = new FocusHelper(this);
        
        // 设置聚焦监听器
        focusView.setOnFocusListener((x, y) -> {
            if (focusHelper != null) {
                focusHelper.tapToFocus(x, y, focusView.getWidth(), focusView.getHeight());
            }
        });
        
        // 添加拍照按钮功能
        Button captureButton = findViewById(R.id.captureButton);
        captureButton.setOnClickListener(v -> takePhoto());

        // 添加摄像头切换按钮
        Button switchCameraButton = findViewById(R.id.switchCameraButton);
        switchCameraButton.setOnClickListener(v -> switchCamera());

        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                // 获取可用相机列表
                List<CameraInfo> availableCameras = cameraProvider.getAvailableCameraInfos();
                if (availableCameras.isEmpty()) {
                    Log.e(TAG, "No cameras available on this device.");
                    return;
                }

                // 根据当前摄像头ID选择相机
                CameraInfo targetCameraInfo = null;
                for (CameraInfo cameraInfo : availableCameras) {
                    if (cameraInfo instanceof CameraInfoInternal) {
                        String cameraId = ((CameraInfoInternal) cameraInfo).getCameraId();
                        if (currentCameraId.equals(cameraId)) {
                            targetCameraInfo = cameraInfo;
                            Log.d(TAG, "Found target camera with ID: " + currentCameraId);
                            break;
                        }
                    }
                }

                if (targetCameraInfo == null) {
                    targetCameraInfo = availableCameras.get(0);
                    currentCameraId = ((CameraInfoInternal) targetCameraInfo).getCameraId();
                    Log.w(TAG, "Could not find camera with ID '" + currentCameraId + "', falling back to the first available camera.");
                }

                CameraInfo finalTargetCameraInfo = targetCameraInfo;
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> Collections.singletonList(finalTargetCameraInfo))
                        .build();

                cameraProvider.unbindAll();
                
                // 绑定相机生命周期
                var camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                
                // 设置相机控制和信息到聚焦帮助类
                focusHelper.setCameraControl(camera.getCameraControl());
                focusHelper.setCameraInfo(camera.getCameraInfo());
                focusHelper.setCurrentCameraId(currentCameraId);
                
                // 记录相机对焦模式信息
                focusHelper.logCameraFocusModes();

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "相机错误❌", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void switchCamera() {
        try {
            ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
            List<CameraInfo> availableCameras = cameraProvider.getAvailableCameraInfos();
            
            if (availableCameras.size() <= 1) {
                Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
                return;
            }

            // 找到下一个可用的摄像头ID
            List<String> cameraIds = new ArrayList<>();
            for (CameraInfo cameraInfo : availableCameras) {
                if (cameraInfo instanceof CameraInfoInternal) {
                    cameraIds.add(((CameraInfoInternal) cameraInfo).getCameraId());
                }
            }

            int currentIndex = cameraIds.indexOf(currentCameraId);
            int nextIndex = (currentIndex + 1) % cameraIds.size();
            currentCameraId = cameraIds.get(nextIndex);

            Log.d(TAG, "Switching to camera ID: " + currentCameraId);
            
            // 重新启动相机
            startCamera();
            
            Toast.makeText(this, "切换到摄像头 " + currentCameraId, Toast.LENGTH_SHORT).show();
            
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "切换摄像头失败", e);
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "相机未准备好", Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建带时间戳的文件名
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(new Date());
        File photoFile = Utils.getOutputMediaFile(this);
        if (photoFile == null) {
            Log.e(TAG, "错误：无法创建文件，请检查存储权限。");
            return;
        }

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        String msg = "照片保存成功: " + photoFile.getAbsolutePath();
                        Toast.makeText(getBaseContext(), "照片已保存 " + photoFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                        Log.d(TAG, msg);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "拍照失败: ", exception);
                        Toast.makeText(getBaseContext(), "拍照失败", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (focusHelper != null) {
            focusHelper.resetFocus();
            focusHelper.release();
        }
    }
}
