package com.android.mycamera.camera;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.Utils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * 优化后的 CameraX 简洁 Demo (仅拍照)
 */
public class CamXApiActivity extends BaseAct {

    private static final String TAG = "MyCamera";

    private PreviewView previewView;
    private ImageCapture imageCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_cam_x);

        previewView = findViewById(R.id.previewView);
        Button captureButton = findViewById(R.id.captureButton);

        startCamera();

        captureButton.setOnClickListener(v -> takePhoto());
    }

    private void startCamera() {
        // ProcessCameraProvider 的获取是异步的
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // 成功获取 ProcessCameraProvider
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. 创建 Preview 用例
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 2. 创建 ImageCapture 用例
                imageCapture = new ImageCapture.Builder().build();

                // 3. 选择后置摄像头作为默认相机
                // 【优化点】使用 CameraSelector.DEFAULT_BACK_CAMERA 替代复杂的过滤器
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 4. 绑定用例到生命周期
                // 在绑定前先解绑，确保没有其他用例在运行
                cameraProvider.unbindAll();

                // 【核心】将相机用例绑定到 Activity 的生命周期
                // CameraX 会自动处理相机的打开和关闭
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show();
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

        // 创建包含文件和元数据的输出选项
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // 执行拍照
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





}