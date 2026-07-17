package com.android.mycamera.camera;


import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

public class MultipleCam2ApiActivity extends BaseAct {

    private static final String TAG = "Camera2Demo";

    private TextureView textureView1;
    private TextureView textureView2;
    private Button btnCapture;
    private Button switchCameraButton;

    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    private Size previewSize;
    private ImageReader imageReader;

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    private static String fileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multiple_cam2_api);

        textureView1 = findViewById(R.id.textureView1);
        textureView2 = findViewById(R.id.textureView2);
        btnCapture = findViewById(R.id.button_capture);
        switchCameraButton = findViewById(R.id.switchCameraButton);

        // TextureView 的生命周期监听
        textureView1.setSurfaceTextureListener(textureListener);
        textureView2.setSurfaceTextureListener(textureListener);

        btnCapture.setOnClickListener(v -> takePicture());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    // TextureView 的监听器，在其可用时打开相机
    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            // 当 TextureView 准备好时，打开相机
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            // 可以在这里处理预览尺寸变化
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            // TextureView 销毁时返回 true
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            // 每一帧更新时调用
        }
    };

    private void openCamera(int width, int height) {


        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // 获取所有可用的摄像头
            cameraIds = manager.getCameraIdList();
            if (cameraIds == null || cameraIds.length == 0) {
                Log.e(TAG, "No cameras found on this device.");
                Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 选择当前摄像头
            cameraId = cameraIds[currentCameraIndex];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            Size largest = Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());
            previewSize = chooseOptimalSize(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceTexture.class), width, height, largest);

            // 设置 ImageReader 用于拍照
            imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

            // 打开相机
            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access the camera.", e);
        }
    }

    // CameraDevice 状态回调
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            // 相机成功打开
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            // 相机断开连接
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            // 发生错误
            camera.close();
            cameraDevice = null;
            Log.e(TAG, "Camera device error: " + error);
        }
    };

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession: ");
        try {
            SurfaceTexture texture1 = textureView1.getSurfaceTexture();
            Log.d(TAG, "texture1: " + texture1);
            if (texture1 == null) {
                return;
            }
            texture1.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface1 = new Surface(texture1);

            SurfaceTexture texture2 = textureView2.getSurfaceTexture();
            //镜像
            textureView2.setScaleX(-1);
            Log.d(TAG, "texture2: " + texture2);
            if (texture2 == null) {
                return;
            }
            texture2.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface2 = new Surface(texture2);

            // 创建预览请求
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface1); // 将预览 Surface1 添加为目标
            captureRequestBuilder.addTarget(surface2); // 将预览 Surface2 添加为目标

            cameraDevice.createCaptureSession(Arrays.asList(surface1, surface2, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        return;
                    }
                    cameraCaptureSession = session;
                    // 设置自动对焦、自动曝光等
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                    try {
                        // 开始无限重复的捕获，实现预览效果
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start camera preview.", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MultipleCam2ApiActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera preview session.", e);
        }
    }

    private void takePicture() {
        if (cameraDevice == null) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        try {
            // 创建一个用于拍照的 CaptureRequest
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // 使用与预览相同的 AE 和 AF 模式
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 拍照
            cameraCaptureSession.stopRepeating(); // 停止预览
            cameraCaptureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MultipleCam2ApiActivity.this, "照片已保存 " + fileName, Toast.LENGTH_SHORT).show();
                    // Toast.makeText(this, "图片已保存: " + pictureFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    // 拍完照后，重新开始预览
                    createCameraPreviewSession();
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to take picture.", e);
        }
    }

    // 当 ImageReader 中有可用图像时调用
    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            // 在后台线程中保存图片
            backgroundHandler.post(new ImageSaver(reader.acquireNextImage()));

        }
    };

    // 用于在后台线程保存图片的 Runnable
    private class ImageSaver implements Runnable {
        private final Image image;

        ImageSaver(Image image) {
            this.image = image;
        }

        @Override
        public void run() {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                File file = Utils.getOutputMediaFile(MultipleCam2ApiActivity.this);
                fileName = file.getAbsolutePath();
                if (file == null) {
                    Log.e(TAG, "Error creating media file, check storage permissions.");
                    return;
                }
                output = new FileOutputStream(file);
                output.write(bytes);
                Log.d(TAG, "Picture saved to " + file.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to save image.", e);
            } finally {
                image.close(); // 非常重要：必须关闭 Image，否则相机将停止发送新图像
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    private void closeCamera() {
        if (null != cameraCaptureSession) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }
    
    private void switchCamera() {
        if (cameraIds == null || cameraIds.length <= 1) {
            Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 关闭当前相机
        closeCamera();
        
        // 切换到下一个摄像头
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        
        Log.d(TAG, "Switching to camera ID: " + cameraId);
        
        // 重新打开相机
        if (textureView1.isAvailable()) {
            openCamera(textureView1.getWidth(), textureView1.getHeight());
        }
        
        Toast.makeText(this, "切换到摄像头 " + cameraId, Toast.LENGTH_SHORT).show();
    }

    // 启动后台线程
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    // 停止后台线程
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to stop background thread.", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        // 如果 TextureView 已经可用，则直接打开相机
        if (textureView1.isAvailable()) {
            openCamera(textureView1.getWidth(), textureView1.getHeight());
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }


    // 帮助类：选择最佳预览尺寸
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, Size aspectRatio) {
        // ... 此处应有更复杂的逻辑来选择最佳尺寸
        // 为简化，我们直接返回第一个
        return choices[0];
    }

    // 帮助类：比较尺寸
    static class CompareSizesByArea implements java.util.Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
