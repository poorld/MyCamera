package com.android.mycamera.multicamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class MultiCameraActivity extends AppCompatActivity {
    private static final String TAG = "MultiCameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_AUDIO_PERMISSION = 201;
    
    private LinearLayout cameraSelectionLayout;
    private LinearLayout previewContainer;
    private Button startPreviewButton;
    private Button stopPreviewButton;
    private Button startRecordButton;
    private Button stopRecordButton;
    private SwitchCompat switch_order;
    private EditText et_delay;
    private boolean orderOpenCam;
    private AtomicBoolean hasTextureViewOpen = new AtomicBoolean();
    
    private MultiCameraHelper multiCameraHelper;
    private Map<String, CameraInfo> availableCameras = new HashMap<>();
    private List<String> selectedCameras = new ArrayList<>();
    private Map<String, TextureView> cameraViews = new HashMap<>();
    private CameraManager cameraManager;

    private Handler handler;
    private final ExecutorService cameraCloseExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isClosingCameras = new AtomicBoolean(false);
    private volatile boolean isDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_camera);
        
        // 检查Android版本是否支持多摄像头
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            Toast.makeText(this, "需要Android 9.0或更高版本才能使用多摄像头功能", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Multi-camera requires Android P (API 28+) or higher");
            finish();
            return;
        }

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        initViews();
        checkPermissions();
    }
    
    private void initViews() {
        cameraSelectionLayout = findViewById(R.id.camera_selection_layout);
        previewContainer = findViewById(R.id.preview_container);
        startPreviewButton = findViewById(R.id.start_preview_button);
        stopPreviewButton = findViewById(R.id.stop_preview_button);
        startRecordButton = findViewById(R.id.start_record_button);
        stopRecordButton = findViewById(R.id.stop_record_button);
        switch_order = findViewById(R.id.switch_order);
        et_delay = findViewById(R.id.et_delay);
        et_delay.setText("3000");

        startPreviewButton.setOnClickListener(v -> startPreview());
        stopPreviewButton.setOnClickListener(v -> stopPreview());
        startRecordButton.setOnClickListener(v -> startRecording());
        stopRecordButton.setOnClickListener(v -> stopRecording());
        switch_order.setOnCheckedChangeListener((buttonView, isChecked) -> orderOpenCam = isChecked);
        
        stopPreviewButton.setEnabled(false);
        stopRecordButton.setEnabled(false);
    }
    
    private void checkPermissions() {
        boolean hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        
        if (!hasCameraPermission) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else if (!hasAudioPermission) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
        } else {
            initMultiCamera();
        }
    }
    
    private void initMultiCamera() {
        multiCameraHelper = new MultiCameraHelper(this);
        multiCameraHelper.startBackgroundThread();
        
        // 检查设备是否支持多摄像头
        if (!multiCameraHelper.isMultiCameraSupported()) {
            Toast.makeText(this, "设备不支持多摄像头功能", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Device does not support multi-camera functionality");
            // return;
        }
        
        // 检测所有可用摄像头
        findAllCameras();
        
        if (!availableCameras.isEmpty()) {
            createCameraCheckboxes();
            Toast.makeText(this, "找到 " + availableCameras.size() + " 个摄像头", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "未找到摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void findAllCameras() {
        try {
            MultiCameraManager manager = new MultiCameraManager(this);
            String[] cameraIds = manager.getCameraManager().getCameraIdList();
            
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraManager().getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                
                String cameraName = "摄像头 " + cameraId;
                if (lensFacing != null) {
                    switch (lensFacing) {
                        case CameraCharacteristics.LENS_FACING_BACK:
                            cameraName = "后摄 " + cameraId;
                            break;
                        case CameraCharacteristics.LENS_FACING_FRONT:
                            cameraName = "前摄 " + cameraId;
                            break;
                        case CameraCharacteristics.LENS_FACING_EXTERNAL:
                            cameraName = "外接摄像头 " + cameraId;
                            break;
                    }
                }
                
                CameraInfo info = new CameraInfo(cameraId, cameraName, lensFacing);
                availableCameras.put(cameraId, info);
                Log.d(TAG, "找到摄像头: " + cameraName + " (ID: " + cameraId + ")");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error finding cameras", e);
        }
    }
    
    private void createCameraCheckboxes() {
        cameraSelectionLayout.removeAllViews();

        // 为每个摄像头创建复选框
        for (CameraInfo cameraInfo : availableCameras.values()) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(cameraInfo.name);
            checkBox.setTag(cameraInfo.id);
            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    String cameraId = (String) buttonView.getTag();
                    if (isChecked) {
                        if (!selectedCameras.contains(cameraId)) {
                            selectedCameras.add(cameraId);
                        }
                    } else {
                        selectedCameras.remove(cameraId);
                    }
                    Log.d(TAG, "选中摄像头: " + selectedCameras);
                }
            });
            
            // // 默认选择前两个摄像头
            // if (selectedCameras.size() < 2) {
            //     checkBox.setChecked(true);
            //     selectedCameras.add(cameraInfo.id);
            // }
            
            cameraSelectionLayout.addView(checkBox);
        }
    }
    
    private void startPreview() {
        if (isClosingCameras.get()) {
            Toast.makeText(this, "正在关闭摄像头，请稍后再打开", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedCameras.isEmpty()) {
            Toast.makeText(this, "请先选择要预览的摄像头", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 检查是否选择了太多摄像头
        if (selectedCameras.size() > 2) {
            Toast.makeText(this, "建议不要同时打开超过2个摄像头，以免影响性能", Toast.LENGTH_LONG).show();
        }

        createPreviewViews();
        // 移除立即打开摄像头的调用，等待SurfaceTexture可用后再打开
        // openSelectedCameras();
        
        startPreviewButton.setEnabled(false);
        stopPreviewButton.setEnabled(true);
        startRecordButton.setEnabled(true);
    }

    private void stopPreview() {
        closeAllCamerasAsync();

        startPreviewButton.setEnabled(false);
        stopPreviewButton.setEnabled(false);
        startRecordButton.setEnabled(false);
    }
    
    private void createPreviewViews() {
        previewContainer.removeAllViews();
        cameraViews.clear();
        
        for (String cameraId : selectedCameras) {

            CameraInfo cameraInfo = availableCameras.get(cameraId);
            
            // 创建摄像头标题
            TextView titleView = new TextView(this);
            titleView.setText(cameraInfo.name);
            titleView.setTextSize(14f);
            titleView.setPadding(0, 8, 0, 4);
            previewContainer.addView(titleView);
            
            // 创建预览视图
            TextureView textureView = new TextureView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            );
            // params.setMargins(0, 0, 0, 16);
            textureView.setLayoutParams(params);
            textureView.setId(View.generateViewId());
            
            // 添加SurfaceTexture监听器
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    int delay = 0;
                    if (orderOpenCam) {
                        String val = et_delay.getText().toString();


                        if (!hasTextureViewOpen.getAndSet(true)) {
                            delay = 0;
                        }else {
                            if (!TextUtils.isEmpty(val)) {
                                delay = Integer.parseInt(val);
                            } else {
                                delay = 3000;
                            }
                        }
                    }
                    Log.d(TAG, "onSurfaceTextureAvailable: delay " + delay);
                    handler.postDelayed(() -> {
                        if (isClosingCameras.get() || !cameraViews.containsKey(cameraId)) {
                            Log.w(TAG, "Skip open camera after stop/close: " + cameraId);
                            return;
                        }
                        Log.d(TAG, "SurfaceTexture available for camera: " + cameraId);

                        if (multiCameraHelper != null) {
                            multiCameraHelper.addTextureView(cameraId, textureView);
                            multiCameraHelper.openSingleCamera(cameraId);
                        }
                    }, delay);

                }
                
                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "SurfaceTexture size changed for camera: " + cameraId);
                }
                
                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    Log.d(TAG, "SurfaceTexture destroyed for camera: " + cameraId);
                    return true;
                }
                
                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    // 不需要处理
                }
            });
            
            previewContainer.addView(textureView);
            cameraViews.put(cameraId, textureView);
            
            Log.d(TAG, "创建预览视图: " + cameraInfo.name);

        }
    }

    
    private void closeAllCameras() {
        handler.removeCallbacksAndMessages(null);
        previewContainer.removeAllViews();
        cameraViews.clear();
        hasTextureViewOpen.set(false);
    }

    private void closeAllCamerasAsync() {
        if (!isClosingCameras.compareAndSet(false, true)) {
            Log.w(TAG, "closeAllCamerasAsync already running");
            return;
        }

        closeAllCameras();
        cameraCloseExecutor.execute(() -> {
            long startMs = System.currentTimeMillis();
            try {
                Log.d(TAG, "closeAllCameras begin on background thread");
                if (multiCameraHelper != null) {
                    multiCameraHelper.closeAllCameras();
                }
                Log.d(TAG, "closeAllCameras done, cost=" + (System.currentTimeMillis() - startMs) + "ms");
            } catch (Exception e) {
                Log.e(TAG, "closeAllCameras failed", e);
            } finally {
                isClosingCameras.set(false);
                if (!isDestroyed) {
                    runOnUiThread(() -> {
                        startPreviewButton.setEnabled(true);
                        stopPreviewButton.setEnabled(false);
                        startRecordButton.setEnabled(false);
                    });
                }
            }
        });
    }
    
    private void startRecording() {
        if (multiCameraHelper != null && !multiCameraHelper.isRecording()) {
            multiCameraHelper.startRecording();
            startRecordButton.setEnabled(false);
            stopRecordButton.setEnabled(true);
            Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void stopRecording() {
        if (multiCameraHelper != null && multiCameraHelper.isRecording()) {
            multiCameraHelper.stopRecording();
            startRecordButton.setEnabled(true);
            stopRecordButton.setEnabled(false);
            Toast.makeText(this, "停止录制", Toast.LENGTH_SHORT).show();
            
            // 显示录制文件路径
            showRecordedFiles();
        }
    }
    
    private void showRecordedFiles() {
        if (multiCameraHelper != null) {
            StringBuilder message = new StringBuilder("录制文件：\n");
            for (String cameraId : multiCameraHelper.getRecordedVideoPaths().keySet()) {
                String path = multiCameraHelper.getRecordedVideoPaths().get(cameraId);
                CameraInfo cameraInfo = availableCameras.get(cameraId);
                String cameraName = cameraInfo != null ? cameraInfo.name : cameraId;
                message.append(cameraName).append(": ").append(path).append("\n");
            }
            Log.d(TAG, message.toString());
        }
    }
    
    // 摄像头信息类
    private static class CameraInfo {
        String id;
        String name;
        Integer lensFacing;
        
        public CameraInfo(String id, String name, Integer lensFacing) {
            this.id = id;
            this.name = name;
            this.lensFacing = lensFacing;
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissions();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initMultiCamera();
            } else {
                Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        handler.removeCallbacksAndMessages(null);

        if (multiCameraHelper != null) {
            cameraCloseExecutor.execute(() -> {
                try {
                    multiCameraHelper.closeAllCameras();
                } catch (Exception e) {
                    Log.e(TAG, "closeAllCameras on destroy failed", e);
                } finally {
                    multiCameraHelper.stopBackgroundThread();
                }
            });
        }
        cameraCloseExecutor.shutdown();
    }
}
