package com.android.mycamera.focus;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

                // 选择相机ID为2的相机
                CameraInfo targetCameraInfo = null;
                for (CameraInfo cameraInfo : availableCameras) {
                    if (cameraInfo instanceof CameraInfoInternal) {
                        String cameraId = ((CameraInfoInternal) cameraInfo).getCameraId();
                        if ("2".equals(cameraId)) {
                            targetCameraInfo = cameraInfo;
                            Log.d(TAG, "Found target camera with ID: 2");
                            break;
                        }
                    }
                }

                if (targetCameraInfo == null) {
                    targetCameraInfo = availableCameras.get(0);
                    Log.w(TAG, "Could not find camera with ID '2', falling back to the first available camera.");
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

    public void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "相机未准备好", Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建带时间戳的文件名
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(new Date());
        File photoFile = Utils.getOutputMediaFile();
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