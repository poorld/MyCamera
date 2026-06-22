package com.android.mycamera.camera;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
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

/**
 * 优化后的 CameraX 简洁 Demo (仅拍照)
 */
public class CamXApiActivity extends BaseAct {

    private static final String TAG = "MyCamera";

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private String currentCameraId = "0";
    private List<CameraInfo> availableCameras;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cam_x);

        previewView = findViewById(R.id.previewView);
        Button captureButton = findViewById(R.id.captureButton);
        Button switchCameraButton = findViewById(R.id.switchCameraButton);

        startCamera();

        captureButton.setOnClickListener(v -> takePhoto());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                this.cameraProvider = cameraProviderFuture.get();

                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(metrics);
                int screenWidth = metrics.widthPixels;
                int screenHeight = metrics.heightPixels;

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(screenWidth, screenHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                // 获取所有可用摄像头
                availableCameras = cameraProvider.getAvailableCameraInfos();
                if (availableCameras.isEmpty()) {
                    Log.e(TAG, "No cameras available on this device.");
                    return;
                }

                CameraInfo targetCameraInfo = findCameraInfoById(currentCameraId);
                if (targetCameraInfo == null) {
                    targetCameraInfo = availableCameras.get(0);
                    currentCameraId = getCameraId(targetCameraInfo);
                    Log.w(TAG, "Could not find camera with ID '" + currentCameraId + "', falling back to the first available camera.");
                }

                cameraProvider.unbindAll();

                CameraInfo finalTargetCameraInfo = targetCameraInfo;
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> Collections.singletonList(finalTargetCameraInfo))
                        .build();

                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "相机错误❌", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
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
                ContextCompat.getMainExecutor(this), // 在主线程中执行回调
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

    private void switchCamera() {
        if (availableCameras == null || availableCameras.size() <= 1) {
            Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
            return;
        }

        // 找到当前摄像头的索引
        int currentIndex = -1;
        for (int i = 0; i < availableCameras.size(); i++) {
            String cameraId = getCameraId(availableCameras.get(i));
            if (currentCameraId.equals(cameraId)) {
                currentIndex = i;
                break;
            }
        }

        // 切换到下一个摄像头
        int nextIndex = (currentIndex + 1) % availableCameras.size();
        currentCameraId = getCameraId(availableCameras.get(nextIndex));

        Log.d(TAG, "Switching to camera ID: " + currentCameraId);
        
        // 重新启动相机
        startCamera();
        
        Toast.makeText(this, "切换到摄像头 " + currentCameraId, Toast.LENGTH_SHORT).show();
    }

    private CameraInfo findCameraInfoById(String cameraId) {
        for (CameraInfo cameraInfo : availableCameras) {
            if (cameraInfo instanceof CameraInfoInternal) {
                String id = ((CameraInfoInternal) cameraInfo).getCameraId();
                if (cameraId.equals(id)) {
                    return cameraInfo;
                }
            }
        }
        return null;
    }

    private String getCameraId(CameraInfo cameraInfo) {
        if (cameraInfo instanceof CameraInfoInternal) {
            return ((CameraInfoInternal) cameraInfo).getCameraId();
        }
        return "0";
    }

}
