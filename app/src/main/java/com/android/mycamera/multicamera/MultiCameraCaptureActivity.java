package com.android.mycamera.multicamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Multi-camera capture screen with a separate photo and video size for every camera. */
public class MultiCameraCaptureActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 230;

    private final Map<String, CameraOption> cameraOptions = new LinkedHashMap<>();
    private final Map<String, TextureView> previewViews = new LinkedHashMap<>();
    private final ExecutorService closeExecutor = Executors.newSingleThreadExecutor();

    private LinearLayout cameraOptionsContainer;
    private LinearLayout previewContainer;
    private Button startPreviewButton;
    private Button stopPreviewButton;
    private Button captureButton;
    private RadioGroup modeGroup;
    private CameraManager cameraManager;
    private MultiCameraHelper cameraHelper;
    private MultiCameraHelper.CaptureMode currentMode = MultiCameraHelper.CaptureMode.VIDEO;
    private boolean previewStarted;
    private boolean closing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_camera_capture);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        initViews();
        requestPermissionsIfNeeded();
    }

    private void initViews() {
        cameraOptionsContainer = findViewById(R.id.camera_options_container);
        previewContainer = findViewById(R.id.preview_container);
        startPreviewButton = findViewById(R.id.start_preview_button);
        stopPreviewButton = findViewById(R.id.stop_preview_button);
        captureButton = findViewById(R.id.capture_button);
        modeGroup = findViewById(R.id.mode_group);

        startPreviewButton.setOnClickListener(view -> startPreview());
        stopPreviewButton.setOnClickListener(view -> stopPreview());
        captureButton.setOnClickListener(view -> capture());
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (previewStarted) {
                Toast.makeText(this, "请先停止预览后再切换模式", Toast.LENGTH_SHORT).show();
                modeGroup.check(currentMode == MultiCameraHelper.CaptureMode.PHOTO
                        ? R.id.photo_mode_button : R.id.video_mode_button);
            } else {
                currentMode = checkedId == R.id.photo_mode_button
                        ? MultiCameraHelper.CaptureMode.PHOTO : MultiCameraHelper.CaptureMode.VIDEO;
                updateResolutionSelectorVisibility();
                updateCaptureButton();
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (missing.isEmpty()) {
            initializeCameras();
        } else {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void initializeCameras() {
        cameraHelper = new MultiCameraHelper(this);
        cameraHelper.startBackgroundThread();
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                List<Size> photoSizes = sortedSizes(map.getOutputSizes(ImageFormat.JPEG));
                List<Size> videoSizes = sortedSizes(map.getOutputSizes(MediaRecorder.class));
                if (photoSizes.isEmpty() || videoSizes.isEmpty()) {
                    continue;
                }
                CameraOption option = new CameraOption(cameraId, cameraName(cameraId, characteristics), photoSizes, videoSizes);
                cameraOptions.put(cameraId, option);
                addCameraOptionView(option);
            }
        } catch (CameraAccessException exception) {
            Toast.makeText(this, "无法查询摄像头分辨率", Toast.LENGTH_LONG).show();
        }
        if (cameraOptions.isEmpty()) {
            Toast.makeText(this, "未找到可用摄像头", Toast.LENGTH_LONG).show();
        }
    }

    private void addCameraOptionView(CameraOption option) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 8, 0, 8);
        CheckBox enabled = new CheckBox(this);
        enabled.setText(option.name);
        enabled.setOnCheckedChangeListener((button, checked) -> option.enabled = checked);
        row.addView(enabled);
        option.photoResolutionSelector = createSizeSelector("拍照分辨率", option.photoSizes,
                size -> option.photoSize = size);
        option.videoResolutionSelector = createSizeSelector("录像分辨率", option.videoSizes,
                size -> option.videoSize = size);
        row.addView(option.photoResolutionSelector);
        row.addView(option.videoResolutionSelector);
        updateResolutionSelectorVisibility(option);
        cameraOptionsContainer.addView(row);
    }

    private void updateResolutionSelectorVisibility() {
        for (CameraOption option : cameraOptions.values()) {
            updateResolutionSelectorVisibility(option);
        }
    }

    private void updateResolutionSelectorVisibility(CameraOption option) {
        if (option.photoResolutionSelector == null || option.videoResolutionSelector == null) return;
        option.photoResolutionSelector.setVisibility(isPhotoMode() ? View.VISIBLE : View.GONE);
        option.videoResolutionSelector.setVisibility(isPhotoMode() ? View.GONE : View.VISIBLE);
    }

    private View createSizeSelector(String label, List<Size> sizes, SizeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView text = new TextView(this);
        text.setText(label);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.36f));
        Spinner spinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (Size size : sizes) {
            labels.add(formatSize(size));
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        listener.onSize(sizes.get(0));
        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> listener.onSize(sizes.get(position))));
        row.addView(spinner, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.64f));
        return row;
    }

    private void startPreview() {
        if (closing) {
            return;
        }
        List<CameraOption> selected = selectedOptions();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一个摄像头", Toast.LENGTH_SHORT).show();
            return;
        }
        cameraHelper.setCaptureMode(currentMode);
        for (CameraOption option : selected) {
            cameraHelper.setPhotoSize(option.id, option.photoSize);
            cameraHelper.setVideoSize(option.id, option.videoSize);
        }
        createPreviewViews(selected);
        previewStarted = true;
        startPreviewButton.setEnabled(false);
        stopPreviewButton.setEnabled(true);
        updateCaptureButton();
    }

    private void createPreviewViews(List<CameraOption> selected) {
        previewContainer.removeAllViews();
        previewViews.clear();
        for (CameraOption option : selected) {
            TextView title = new TextView(this);
            title.setText(option.name);
            previewContainer.addView(title);
            TextureView preview = new TextureView(this);
            preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    if (previewStarted) {
                        cameraHelper.addTextureView(option.id, preview);
                        cameraHelper.openSingleCamera(option.id);
                    }
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) { }
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
            });
            previewContainer.addView(preview);
            previewViews.put(option.id, preview);
        }
    }

    private void capture() {
        if (isPhotoMode()) {
            cameraHelper.takePictures();
            Toast.makeText(this, "已请求拍照，图片保存到应用外部文件目录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (cameraHelper.isRecording()) {
            cameraHelper.stopRecording();
            Toast.makeText(this, "已停止录制", Toast.LENGTH_SHORT).show();
        } else {
            for (CameraOption option : selectedOptions()) {
                cameraHelper.setVideoSize(option.id, option.videoSize);
            }
            cameraHelper.startRecording();
            Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();
        }
        updateCaptureButton();
    }

    private void stopPreview() {
        if (!previewStarted || closing) {
            return;
        }
        closing = true;
        captureButton.setEnabled(false);
        closeExecutor.execute(() -> {
            cameraHelper.closeAllCameras();
            runOnUiThread(() -> {
                previewContainer.removeAllViews();
                previewViews.clear();
                previewStarted = false;
                closing = false;
                startPreviewButton.setEnabled(true);
                stopPreviewButton.setEnabled(false);
                updateCaptureButton();
            });
        });
    }

    private void updateCaptureButton() {
        captureButton.setEnabled(previewStarted && !closing);
        if (isPhotoMode()) {
            captureButton.setText("拍照");
        } else if (cameraHelper != null && cameraHelper.isRecording()) {
            captureButton.setText("停止录制");
        } else {
            captureButton.setText("开始录制");
        }
    }

    private boolean isPhotoMode() {
        return currentMode == MultiCameraHelper.CaptureMode.PHOTO;
    }

    private List<CameraOption> selectedOptions() {
        List<CameraOption> selected = new ArrayList<>();
        for (CameraOption option : cameraOptions.values()) {
            if (option.enabled) {
                selected.add(option);
            }
        }
        return selected;
    }

    private static List<Size> sortedSizes(Size[] sizes) {
        List<Size> result = new ArrayList<>();
        if (sizes != null) {
            result.addAll(Arrays.asList(sizes));
        }
        result.sort(Comparator.comparingLong((Size size) -> (long) size.getWidth() * size.getHeight()).reversed());
        return result;
    }

    private static String formatSize(Size size) {
        return size.getWidth() + " x " + size.getHeight();
    }

    private static String cameraName(String cameraId, CameraCharacteristics characteristics) {
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return "前摄 " + cameraId;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return "后摄 " + cameraId;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "外接摄像头 " + cameraId;
        return "摄像头 " + cameraId;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && grantResults.length > 0) {
            requestPermissionsIfNeeded();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraHelper != null) {
            closeExecutor.execute(() -> {
                cameraHelper.closeAllCameras();
                cameraHelper.stopBackgroundThread();
            });
        }
        closeExecutor.shutdown();
    }

    private interface SizeListener { void onSize(Size size); }

    private static class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer selectionListener;

        SimpleItemSelectedListener(java.util.function.IntConsumer selectionListener) {
            this.selectionListener = selectionListener;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            selectionListener.accept(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) { }
    }

    private static class CameraOption {
        final String id;
        final String name;
        final List<Size> photoSizes;
        final List<Size> videoSizes;
        boolean enabled;
        Size photoSize;
        Size videoSize;
        View photoResolutionSelector;
        View videoResolutionSelector;

        CameraOption(String id, String name, List<Size> photoSizes, List<Size> videoSizes) {
            this.id = id;
            this.name = name;
            this.photoSizes = photoSizes;
            this.videoSizes = videoSizes;
        }
    }
}
