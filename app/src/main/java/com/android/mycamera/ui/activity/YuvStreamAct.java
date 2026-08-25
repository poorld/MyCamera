package com.android.mycamera.ui.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YUV 原始像素裸流录制 Activity (未压缩 YUV420P/NV21/NV12 裸流直接落盘)
 */
public class YuvStreamAct extends BaseAct {
    private static final String TAG = "YuvStreamAct";
    private static final int REQUEST_CAMERA = 1001;
    private static final String CAMERA_ID = "0";
    private static final int DEFAULT_VIDEO_FPS = 30;
    private static final int YUV_READER_MAX_IMAGES = 3;

    public enum YuvPixelFormat {
        I420("YUV420P (I420)", ".yuv"),
        NV21("YUV420SP (NV21)", ".yuv"),
        NV12("YUV420SP (NV12)", ".yuv");

        public final String label;
        public final String extension;

        YuvPixelFormat(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    private TextureView previewView;
    private Button recordButton;
    private Spinner formatSpinner;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private TextView statusText;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread yuvIoThread;
    private Handler yuvIoHandler;

    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;

    private ImageReader yuvImageReader;
    private Surface yuvReaderSurface;

    private FileOutputStream fileOutputStream;
    private BufferedOutputStream bufferedOutputStream;
    private File outputFile;
    private volatile boolean isRecording;

    private long totalFrames = 0;
    private long totalBytesWritten = 0;
    private long recordStartTime = 0;
    private long lastUiUpdateTime = 0;
    private byte[] frameBuffer;

    private volatile YuvPixelFormat selectedFormat = YuvPixelFormat.I420;
    private volatile ResolutionOption selectedResolution;
    private volatile FpsOption selectedFps;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            setStatus("Camera opened: " + getSizeLabel());
            startCameraSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            setStatus("Camera disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            setStatus("Camera error: " + error);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_codec_test);

        previewView = findViewById(R.id.codecPreview);
        recordButton = findViewById(R.id.codecRecordButton);
        formatSpinner = findViewById(R.id.codecFormatSpinner);
        resolutionSpinner = findViewById(R.id.codecResolutionSpinner);
        fpsSpinner = findViewById(R.id.codecFpsSpinner);
        statusText = findViewById(R.id.codecStatus);

        recordButton.setText("Start YUV Record");

        initCameraCapabilitiesAndSpinners();

        recordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
    }

    private void initCameraCapabilitiesAndSpinners() {
        setupFormatSpinner();

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                setupFallbackSpinners();
                return;
            }
            String cameraId = CAMERA_ID;
            boolean hasHardcodedId = false;
            for (String id : manager.getCameraIdList()) {
                if (CAMERA_ID.equals(id)) {
                    hasHardcodedId = true;
                    break;
                }
            }
            if (!hasHardcodedId && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            setupResolutionSpinnerFromSensor(cameraCharacteristics);
            setupFpsSpinnerFromSensor(cameraCharacteristics);
        } catch (Exception e) {
            Log.e(TAG, "initCameraCapabilitiesAndSpinners failed", e);
            setupFallbackSpinners();
        }
    }

    private void setupFormatSpinner() {
        ArrayAdapter<YuvPixelFormat> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, YuvPixelFormat.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);
        formatSpinner.setSelection(0, false);
        selectedFormat = YuvPixelFormat.I420;

        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFormat = (YuvPixelFormat) parent.getItemAtPosition(position);
                setStatus("YUV " + selectedFormat.name() + " " + getSizeLabel() + " Ready");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupResolutionSpinnerFromSensor(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics != null
                ? characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                : null;
        if (map == null) {
            setupFallbackResolutionSpinner();
            return;
        }

        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            sizes = map.getOutputSizes(SurfaceTexture.class);
        }
        if (sizes == null || sizes.length == 0) {
            setupFallbackResolutionSpinner();
            return;
        }

        List<Size> uniqueSizes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Size size : sizes) {
            String key = size.getWidth() + "x" + size.getHeight();
            if (!seen.contains(key)) {
                seen.add(key);
                uniqueSizes.add(size);
            }
        }

        uniqueSizes.sort((a, b) -> {
            int areaA = a.getWidth() * a.getHeight();
            int areaB = b.getWidth() * b.getHeight();
            if (areaA != areaB) {
                return Integer.compare(areaB, areaA);
            }
            return Integer.compare(b.getWidth(), a.getWidth());
        });

        List<ResolutionOption> options = new ArrayList<>();
        for (Size size : uniqueSizes) {
            Size previewSize = choosePreviewSize(map, size.getWidth(), size.getHeight());
            String aspectLabel = getAspectRatioLabel(size.getWidth(), size.getHeight());
            String label = size.getWidth() + "x" + size.getHeight()
                    + (aspectLabel.isEmpty() ? "" : " (" + aspectLabel + ")");
            options.add(new ResolutionOption(label, size.getWidth(), size.getHeight(),
                    previewSize.getWidth(), previewSize.getHeight()));
        }

        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        int defaultIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            ResolutionOption opt = options.get(i);
            if (opt.width == 1920 && opt.height == 1080) {
                defaultIndex = i;
                break;
            } else if (opt.width == 1280 && opt.height == 720) {
                defaultIndex = i;
            }
        }
        resolutionSpinner.setSelection(defaultIndex, false);
        selectedResolution = options.get(defaultIndex);

        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ResolutionOption newOption = (ResolutionOption) parent.getItemAtPosition(position);
                if (selectedResolution != newOption) {
                    selectedResolution = newOption;
                    setStatus("YUV " + getSizeLabel() + " Ready");
                    if (cameraDevice != null && !isRecording) {
                        startCameraSession();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupFpsSpinnerFromSensor(CameraCharacteristics characteristics) {
        Range<Integer>[] fpsRanges = characteristics != null
                ? characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                : null;

        Set<Integer> fpsSet = new HashSet<>();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                fpsSet.add(range.getUpper());
                if (range.getLower().equals(range.getUpper())) {
                    fpsSet.add(range.getLower());
                }
            }
        }
        if (fpsSet.isEmpty()) {
            fpsSet.add(30);
        }

        List<Integer> fpsList = new ArrayList<>(fpsSet);
        fpsList.sort((a, b) -> Integer.compare(b, a));

        List<FpsOption> options = new ArrayList<>();
        for (int fps : fpsList) {
            options.add(new FpsOption(fps));
        }

        ArrayAdapter<FpsOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);

        int defaultIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).fps == 30) {
                defaultIndex = i;
                break;
            }
        }
        fpsSpinner.setSelection(defaultIndex, false);
        selectedFps = options.get(defaultIndex);

        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FpsOption newOption = (FpsOption) parent.getItemAtPosition(position);
                if (selectedFps != newOption) {
                    selectedFps = newOption;
                    setStatus("YUV " + getSizeLabel() + " Ready");
                    if (cameraDevice != null && !isRecording) {
                        startCameraSession();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupFallbackSpinners() {
        setupFallbackResolutionSpinner();
        setupFallbackFpsSpinner();
    }

    private void setupFallbackResolutionSpinner() {
        List<ResolutionOption> options = new ArrayList<>();
        options.add(new ResolutionOption("1920x1080 (16:9)", 1920, 1080, 1920, 1080));
        options.add(new ResolutionOption("1280x720 (16:9)", 1280, 720, 1280, 720));
        options.add(new ResolutionOption("640x480 (4:3)", 640, 480, 640, 480));

        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(0, false);
        selectedResolution = options.get(0);

        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedResolution = (ResolutionOption) parent.getItemAtPosition(position);
                setStatus("YUV " + getSizeLabel() + " Ready");
                if (cameraDevice != null && !isRecording) {
                    startCameraSession();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupFallbackFpsSpinner() {
        List<FpsOption> options = new ArrayList<>();
        options.add(new FpsOption(30));
        options.add(new FpsOption(25));
        options.add(new FpsOption(20));
        options.add(new FpsOption(15));

        ArrayAdapter<FpsOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);
        fpsSpinner.setSelection(0, false);
        selectedFps = options.get(0);

        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFps = (FpsOption) parent.getItemAtPosition(position);
                setStatus("YUV " + getSizeLabel() + " Ready");
                if (cameraDevice != null && !isRecording) {
                    startCameraSession();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private Size choosePreviewSize(StreamConfigurationMap map, int videoWidth, int videoHeight) {
        if (map == null) {
            return new Size(videoWidth, videoHeight);
        }
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        if (previewSizes == null || previewSizes.length == 0) {
            return new Size(videoWidth, videoHeight);
        }

        if (videoWidth <= 1920 && videoHeight <= 1080) {
            for (Size size : previewSizes) {
                if (size.getWidth() == videoWidth && size.getHeight() == videoHeight) {
                    return size;
                }
            }
        }

        Size bestSameAspect = null;
        for (Size size : previewSizes) {
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080) {
                if (isSameAspect(size, videoWidth, videoHeight)) {
                    if (bestSameAspect == null
                            || (size.getWidth() * size.getHeight() > bestSameAspect.getWidth() * bestSameAspect.getHeight())) {
                        bestSameAspect = size;
                    }
                }
            }
        }
        if (bestSameAspect != null) {
            return bestSameAspect;
        }

        for (Size size : previewSizes) {
            if (size.getWidth() == videoWidth && size.getHeight() == videoHeight) {
                return size;
            }
        }

        return previewSizes[0];
    }

    private boolean isSameAspect(Size size, int targetWidth, int targetHeight) {
        return (long) size.getWidth() * targetHeight == (long) size.getHeight() * targetWidth;
    }

    private String getAspectRatioLabel(int width, int height) {
        if (width * 9 == height * 16) {
            return "16:9";
        } else if (width * 3 == height * 4) {
            return "4:3";
        } else if (width == height) {
            return "1:1";
        } else if (width * 10 == height * 16) {
            return "16:10";
        }
        return "";
    }

    private Range<Integer> getTargetFpsRange(int targetFps) {
        if (cameraCharacteristics == null) {
            return new Range<>(targetFps, targetFps);
        }
        Range<Integer>[] ranges = cameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return new Range<>(targetFps, targetFps);
        }

        for (Range<Integer> range : ranges) {
            if (range.getLower() == targetFps && range.getUpper() == targetFps) {
                return range;
            }
        }

        Range<Integer> bestWithUpper = null;
        for (Range<Integer> range : ranges) {
            if (range.getUpper() == targetFps) {
                if (bestWithUpper == null || range.getLower() > bestWithUpper.getLower()) {
                    bestWithUpper = range;
                }
            }
        }
        if (bestWithUpper != null) {
            return bestWithUpper;
        }

        Range<Integer> bestCovering = null;
        for (Range<Integer> range : ranges) {
            if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                if (bestCovering == null || range.getUpper() < bestCovering.getUpper()) {
                    bestCovering = range;
                }
            }
        }
        if (bestCovering != null) {
            return bestCovering;
        }

        Range<Integer> closest = ranges[0];
        int minDiff = Math.abs(closest.getUpper() - targetFps);
        for (Range<Integer> range : ranges) {
            int diff = Math.abs(range.getUpper() - targetFps);
            if (diff < minDiff) {
                minDiff = diff;
                closest = range;
            }
        }
        return closest;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThreads();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        if (isRecording) {
            stopRecording();
        }
        closeCamera();
        stopBackgroundThreads();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (cameraDevice != null || cameraHandler == null) {
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                setStatus("CameraManager null");
                return;
            }
            String cameraId = CAMERA_ID;
            boolean hasHardcodedId = false;
            for (String id : manager.getCameraIdList()) {
                if (CAMERA_ID.equals(id)) {
                    hasHardcodedId = true;
                    break;
                }
            }
            if (!hasHardcodedId && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
            setStatus("Opening camera " + cameraId);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
            setStatus("Open camera failed: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (cameraDevice == null) {
            setStatus("Camera not ready");
            return;
        }
        try {
            outputFile = createOutputFile();
            fileOutputStream = new FileOutputStream(outputFile);
            bufferedOutputStream = new BufferedOutputStream(fileOutputStream, 256 * 1024);

            int width = getVideoWidth();
            int height = getVideoHeight();
            int frameSize = width * height * 3 / 2;
            frameBuffer = new byte[frameSize];

            totalFrames = 0;
            totalBytesWritten = 0;
            recordStartTime = System.currentTimeMillis();
            lastUiUpdateTime = recordStartTime;
            isRecording = true;

            recordButton.setText("Stop YUV Record");
            setControlsEnabled(false);
            startCameraSession();
            setStatus("Recording raw YUV: " + selectedFormat.name() + " " + getSizeLabel());
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            setStatus("Start failed: " + e.getMessage());
            isRecording = false;
            setControlsEnabled(true);
            releaseIoStreams();
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }
        setStatus("Stopping YUV recording...");
        isRecording = false;
        recordButton.setEnabled(false);

        // 重启预览会话（不再带 ImageReader Surface）
        startCameraSession();

        releaseIoStreams();

        recordButton.setText("Start YUV Record");
        recordButton.setEnabled(true);
        setControlsEnabled(true);

        double mbSize = totalBytesWritten / (1024.0 * 1024.0);
        String result = String.format(Locale.US, "Saved raw YUV stream:\n%s\nFrames: %d | Size: %.2f MB",
                outputFile == null ? "" : outputFile.getAbsolutePath(), totalFrames, mbSize);
        setStatus(result);
    }

    private void setControlsEnabled(boolean enabled) {
        if (formatSpinner != null) {
            formatSpinner.setEnabled(enabled);
        }
        if (resolutionSpinner != null) {
            resolutionSpinner.setEnabled(enabled);
        }
        if (fpsSpinner != null) {
            fpsSpinner.setEnabled(enabled);
        }
    }

    private void startCameraSession() {
        if (cameraDevice == null || !previewView.isAvailable()) {
            return;
        }

        closeCaptureSession();
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) {
                setStatus("Preview texture null");
                return;
            }
            texture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            previewSurface = new Surface(texture);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);

            if (isRecording) {
                setupYuvImageReader();
                if (yuvReaderSurface != null) {
                    surfaces.add(yuvReaderSurface);
                }
            }

            int template = isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(template);
            requestBuilder.addTarget(previewSurface);
            if (isRecording && yuvReaderSurface != null) {
                requestBuilder.addTarget(yuvReaderSurface);
            }
            Range<Integer> fpsRange = getTargetFpsRange(getVideoFps());
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "setRepeatingRequest failed", e);
                        setStatus("Preview request failed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    setStatus("Session config failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "startCameraSession failed", e);
            setStatus("Session failed: " + e.getMessage());
        }
    }

    private void setupYuvImageReader() {
        closeYuvImageReader();
        int width = getVideoWidth();
        int height = getVideoHeight();
        yuvImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, YUV_READER_MAX_IMAGES);
        yuvImageReader.setOnImageAvailableListener(this::onYuvImageAvailable, yuvIoHandler);
        yuvReaderSurface = yuvImageReader.getSurface();
    }

    private void onYuvImageAvailable(ImageReader reader) {
        if (!isRecording) {
            try (Image image = reader.acquireLatestImage()) {
                // discard
            } catch (Exception ignored) {
            }
            return;
        }

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int requiredSize = width * height * 3 / 2;

            if (frameBuffer == null || frameBuffer.length != requiredSize) {
                frameBuffer = new byte[requiredSize];
            }

            convertYuv420888ToContinuous(image, frameBuffer, selectedFormat);

            if (bufferedOutputStream != null) {
                bufferedOutputStream.write(frameBuffer, 0, requiredSize);
                totalBytesWritten += requiredSize;
                totalFrames++;

                long now = System.currentTimeMillis();
                if (now - lastUiUpdateTime > 500) {
                    lastUiUpdateTime = now;
                    long elapsedSec = Math.max(1, (now - recordStartTime) / 1000);
                    double currentFps = (double) totalFrames / elapsedSec;
                    double mbSize = totalBytesWritten / (1024.0 * 1024.0);
                    String stats = String.format(Locale.US,
                            "Recording YUV [%s]\nFrames: %d (%.1f fps) | Size: %.2f MB",
                            selectedFormat.name(), totalFrames, currentFps, mbSize);
                    runOnUiThread(() -> setStatus(stats));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onYuvImageAvailable write error", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static void convertYuv420888ToContinuous(Image image, byte[] outData, YuvPixelFormat format) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();

        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int pos = 0;
        // 1. Copy Y Plane
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(outData, 0, width * height);
            pos = width * height;
        } else {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                for (int col = 0; col < width; col++) {
                    outData[pos++] = yBuffer.get();
                    if (yPixelStride > 1 && col < width - 1) {
                        yBuffer.position(yBuffer.position() + yPixelStride - 1);
                    }
                }
            }
        }

        int halfWidth = width / 2;
        int halfHeight = height / 2;

        if (format == YuvPixelFormat.I420) {
            // I420 (YUV420P): Y... U... V...
            for (int row = 0; row < halfHeight; row++) {
                for (int col = 0; col < halfWidth; col++) {
                    outData[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                }
            }
            for (int row = 0; row < halfHeight; row++) {
                for (int col = 0; col < halfWidth; col++) {
                    outData[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                }
            }
        } else if (format == YuvPixelFormat.NV21) {
            // NV21 (YUV420SP): Y... VU VU VU...
            for (int row = 0; row < halfHeight; row++) {
                for (int col = 0; col < halfWidth; col++) {
                    outData[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                    outData[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                }
            }
        } else if (format == YuvPixelFormat.NV12) {
            // NV12 (YUV420SP): Y... UV UV UV...
            for (int row = 0; row < halfHeight; row++) {
                for (int col = 0; col < halfWidth; col++) {
                    outData[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                    outData[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                }
            }
        }
    }

    private File createOutputFile() throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = getFilesDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create dir: " + dir.getAbsolutePath());
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, "YUV_STREAM_" + timestamp + "_" + selectedFormat.name() + "_" + getSizeLabel() + ".yuv");
    }

    private int getVideoWidth() {
        return selectedResolution != null ? selectedResolution.width : 1920;
    }

    private int getVideoHeight() {
        return selectedResolution != null ? selectedResolution.height : 1080;
    }

    private int getPreviewWidth() {
        return selectedResolution != null ? selectedResolution.previewWidth : 1920;
    }

    private int getPreviewHeight() {
        return selectedResolution != null ? selectedResolution.previewHeight : 1080;
    }

    private int getVideoFps() {
        return selectedFps != null ? selectedFps.fps : DEFAULT_VIDEO_FPS;
    }

    private String getSizeLabel() {
        return getVideoWidth() + "x" + getVideoHeight() + "@" + getVideoFps() + "fps";
    }

    private void closeCamera() {
        closeCaptureSession();
        closeYuvImageReader();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void closeCaptureSession() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "closeCaptureSession request stop failed", e);
            }
            captureSession.close();
            captureSession = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void closeYuvImageReader() {
        if (yuvImageReader != null) {
            yuvImageReader.close();
            yuvImageReader = null;
        }
        if (yuvReaderSurface != null) {
            yuvReaderSurface.release();
            yuvReaderSurface = null;
        }
    }

    private void releaseIoStreams() {
        if (bufferedOutputStream != null) {
            try {
                bufferedOutputStream.flush();
                bufferedOutputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "bufferedOutputStream close failed", e);
            }
            bufferedOutputStream = null;
        }
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "fileOutputStream close failed", e);
            }
            fileOutputStream = null;
        }
    }

    private void startBackgroundThreads() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("YuvCameraThread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
        if (yuvIoThread == null) {
            yuvIoThread = new HandlerThread("YuvIoThread");
            yuvIoThread.start();
            yuvIoHandler = new Handler(yuvIoThread.getLooper());
        }
    }

    private void stopBackgroundThreads() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        if (yuvIoThread != null) {
            yuvIoThread.quitSafely();
            try {
                yuvIoThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            yuvIoThread = null;
            yuvIoHandler = null;
        }
    }

    private void setStatus(String message) {
        Log.d(TAG, message);
        runOnUiThread(() -> statusText.setText(message));
    }

    public static final class ResolutionOption {
        public final String label;
        public final int width;
        public final int height;
        public final int previewWidth;
        public final int previewHeight;

        public ResolutionOption(String label, int width, int height, int previewWidth, int previewHeight) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    public static final class FpsOption {
        public final int fps;
        public final String label;

        public FpsOption(int fps) {
            this.fps = fps;
            this.label = fps + " fps";
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }
}
