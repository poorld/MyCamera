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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class S1HMediaCodecAct extends BaseAct {
    private static final String TAG = "S1HMediaCodec";
    private static final int REQUEST_CAMERA = 1001;

    private static final String CAMERA_ID = "0";
    private static final int DEFAULT_VIDEO_FPS = 30;
    private static final int I_FRAME_INTERVAL = 1;

    // 固定分辨率 854x480
    public static final int FIXED_WIDTH = 854;
    public static final int FIXED_HEIGHT = 480;

    private static final CodecOption[] CODEC_OPTIONS = {
            new CodecOption("H.264 (AVC)", MediaFormat.MIMETYPE_VIDEO_AVC, "H264"),
            new CodecOption("H.265 (HEVC)", MediaFormat.MIMETYPE_VIDEO_HEVC, "H265")
    };

    private TextureView previewView;
    private Button recordButton;
    private Spinner codecFormatSpinner;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private TextView statusText;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;

    private MediaCodec encoder;
    private Surface encoderSurface;
    private MediaMuxer muxer;
    private Thread drainThread;
    private volatile boolean encoderDraining;
    private boolean muxerStarted;
    private int videoTrackIndex = -1;
    private File outputFile;
    private boolean isRecording;
    private String activeCodecName = "";

    private volatile CodecOption selectedCodec = CODEC_OPTIONS[0];
    private volatile ResolutionOption selectedResolution = new ResolutionOption("854x480 (16:9 [固定])", FIXED_WIDTH, FIXED_HEIGHT, FIXED_WIDTH, FIXED_HEIGHT);
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
            setStatus("Camera opened: " + getVideoSizeLabel());
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
        codecFormatSpinner = findViewById(R.id.codecFormatSpinner);
        resolutionSpinner = findViewById(R.id.codecResolutionSpinner);
        fpsSpinner = findViewById(R.id.codecFpsSpinner);
        statusText = findViewById(R.id.codecStatus);

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
        setupCodecSpinner();

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
            setupFixedResolutionSpinner(cameraCharacteristics);
            setupFpsSpinnerFromSensor(cameraCharacteristics);
        } catch (Exception e) {
            Log.e(TAG, "initCameraCapabilitiesAndSpinners failed", e);
            setupFallbackSpinners();
        }
    }

    private void setupCodecSpinner() {
        ArrayAdapter<CodecOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, CODEC_OPTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codecFormatSpinner.setAdapter(adapter);
        codecFormatSpinner.setSelection(0, false);
        selectedCodec = CODEC_OPTIONS[0];

        codecFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCodec = (CodecOption) parent.getItemAtPosition(position);
                setStatus("MediaCodec " + selectedCodec.shortName + " " + getVideoSizeLabel() + " Ready");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupFixedResolutionSpinner(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics != null
                ? characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                : null;

        Size previewSize = choosePreviewSize(map, FIXED_WIDTH, FIXED_HEIGHT);
        ResolutionOption fixedOption = new ResolutionOption(
                FIXED_WIDTH + "x" + FIXED_HEIGHT + " (16:9 [S1H固定])",
                FIXED_WIDTH, FIXED_HEIGHT,
                previewSize.getWidth(), previewSize.getHeight());

        List<ResolutionOption> options = Collections.singletonList(fixedOption);
        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(0, false);
        resolutionSpinner.setEnabled(false); // 锁定分辨率不可更改
        selectedResolution = fixedOption;
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
                    setStatus("MediaCodec " + getVideoSizeLabel() + " Ready");
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
        setupFixedResolutionSpinner(null);
        setupFallbackFpsSpinner();
    }

    private void setupFallbackFpsSpinner() {
        List<FpsOption> options = new ArrayList<>();
        options.add(new FpsOption(30));
        options.add(new FpsOption(24));
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
                setStatus("MediaCodec " + getVideoSizeLabel() + " Ready");
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

        // 精确匹配
        for (Size size : previewSizes) {
            if (size.getWidth() == videoWidth && size.getHeight() == videoHeight) {
                return size;
            }
        }

        // 寻找同比例 (16:9)
        Size bestSameAspect = null;
        for (Size size : previewSizes) {
            if (isSameAspect(size, videoWidth, videoHeight)) {
                if (bestSameAspect == null || (size.getWidth() * size.getHeight() < bestSameAspect.getWidth() * bestSameAspect.getHeight())) {
                    bestSameAspect = size;
                }
            }
        }
        if (bestSameAspect != null) {
            return bestSameAspect;
        }

        return previewSizes[0];
    }

    private boolean isSameAspect(Size size, int targetWidth, int targetHeight) {
        return (long) size.getWidth() * targetHeight == (long) size.getHeight() * targetWidth;
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
        startCameraThread();
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
        stopCameraThread();
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
            dumpCameraCapabilities(manager, cameraId);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
            setStatus("Opening camera " + cameraId);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
            setStatus("Open camera failed: " + e.getMessage());
        }
    }

    private void dumpCameraCapabilities(CameraManager manager, String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.w(TAG, "camera " + cameraId + " has no stream configuration map");
            return;
        }
        Range<Integer>[] fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Log.d(TAG, "camera " + cameraId + " fps ranges=" + Arrays.toString(fpsRanges));
    }

    private void startRecording() {
        if (cameraDevice == null) {
            setStatus("Camera not ready");
            return;
        }
        try {
            setupEncoder();
            encoder.start();
            encoderDraining = true;
            drainThread = new Thread(this::drainEncoder, "CodecDrainThread");
            drainThread.start();
            isRecording = true;
            recordButton.setText("Stop Codec");
            setControlsEnabled(false);
            startCameraSession();
            setStatus("Recording " + activeCodecName + " " + getVideoSizeLabel());
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            setStatus("Start failed: " + e.getMessage());
            isRecording = false;
            setControlsEnabled(true);
            releaseEncoder();
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }
        setStatus("Stopping...");
        isRecording = false;
        recordButton.setEnabled(false);
        closeCaptureSession();

        try {
            if (encoder != null) {
                encoder.signalEndOfInputStream();
            }
            if (drainThread != null) {
                drainThread.join(3000);
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecording failed", e);
        }

        releaseEncoder();
        recordButton.setText("Start Codec");
        recordButton.setEnabled(true);
        setControlsEnabled(true);
        setStatus("Saved: " + (outputFile == null ? "" : outputFile.getAbsolutePath()));
        startCameraSession();
    }

    private void setControlsEnabled(boolean enabled) {
        if (codecFormatSpinner != null) {
            codecFormatSpinner.setEnabled(enabled);
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
            if (isRecording && encoderSurface != null) {
                surfaces.add(encoderSurface);
            }

            int template = isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(template);
            requestBuilder.addTarget(previewSurface);
            if (isRecording && encoderSurface != null) {
                requestBuilder.addTarget(encoderSurface);
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

    private void setupEncoder() throws IOException {
        Exception lastError = null;
        String mimeType = getSelectedMimeType();
        List<String> candidates = getEncoderCandidates(mimeType);
        List<FormatOption> options = buildFormatOptions(mimeType, getVideoBitRate(), getVideoFps());
        for (String codecName : candidates) {
            dumpEncoderCapability(codecName, mimeType);
            for (FormatOption option : options) {
                MediaCodec trialEncoder = null;
                try {
                    MediaFormat format = createEncodeFormat(mimeType, option);
                    Log.d(TAG, "try configure codec=" + codecName + " option=" + option.label
                            + " format=" + format);
                    trialEncoder = MediaCodec.createByCodecName(codecName);
                    trialEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    encoder = trialEncoder;
                    activeCodecName = codecName + "/" + option.label;
                    Log.d(TAG, "configure success codec=" + activeCodecName);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    Log.e(TAG, "configure failed codec=" + codecName + " option=" + option.label, e);
                    if (trialEncoder != null) {
                        try {
                            trialEncoder.release();
                        } catch (Exception releaseError) {
                            Log.w(TAG, "release trial encoder failed", releaseError);
                        }
                    }
                }
            }
            if (encoder != null) {
                break;
            }
        }

        if (encoder == null) {
            if (lastError instanceof IOException) {
                throw (IOException) lastError;
            }
            IOException wrapped = new IOException("No encoder accepts " + mimeType + " " + getVideoSizeLabel());
            if (lastError != null) {
                wrapped.initCause(lastError);
            }
            throw wrapped;
        }

        outputFile = createOutputFile();
        encoderSurface = encoder.createInputSurface();

        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        videoTrackIndex = -1;
        muxerStarted = false;
    }

    private MediaFormat createEncodeFormat(String mimeType, FormatOption option) {
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, getVideoWidth(), getVideoHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, option.bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, option.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        if (option.bitRateMode >= 0) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, option.bitRateMode);
        }
        if (option.profile >= 0) {
            format.setInteger(MediaFormat.KEY_PROFILE, option.profile);
        }
        if (option.level >= 0) {
            format.setInteger(MediaFormat.KEY_LEVEL, option.level);
        }
        if (option.setColorAspects) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        return format;
    }

    private List<FormatOption> buildFormatOptions(String mimeType, int bitRate, int fps) {
        List<FormatOption> options = new ArrayList<>();
        boolean isHevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mimeType);

        if (isHevc) {
            options.add(new FormatOption("cbr-hevc-main-l51-bt709", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51));
            options.add(new FormatOption("cbr-hevc-main-bt709", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, -1));
            options.add(new FormatOption("cbr-bt709", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true));
            options.add(new FormatOption("cbr", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
            options.add(new FormatOption("no-bitrate-mode", bitRate, fps,
                    -1, false));
            options.add(new FormatOption("vbr", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, false));
            options.add(new FormatOption("cbr-4m", 4_000_000, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        } else {
            options.add(new FormatOption("cbr-high-l51-bt709", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51));
            options.add(new FormatOption("no-bitrate-mode-high-l51-bt709", bitRate, fps,
                    -1, true,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51));
            options.add(new FormatOption("cbr-4m-high-l51-bt709", 4_000_000, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51));
            options.add(new FormatOption("cbr-bt709", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true));
            options.add(new FormatOption("cbr-avc-high-l51", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51));
            options.add(new FormatOption("cbr", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
            options.add(new FormatOption("no-bitrate-mode", bitRate, fps,
                    -1, false));
            options.add(new FormatOption("vbr", bitRate, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, false));
            options.add(new FormatOption("cbr-4m", 4_000_000, fps,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        }
        return options;
    }

    private List<String> getEncoderCandidates(String mimeType) {
        List<String> vendorCandidates = new ArrayList<>();
        List<String> omxCandidates = new ArrayList<>();
        List<String> otherCandidates = new ArrayList<>();
        MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            for (String type : codecInfo.getSupportedTypes()) {
                if (!mimeType.equalsIgnoreCase(type)) {
                    continue;
                }
                String name = codecInfo.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("unisoc") || lower.contains("sprd")) {
                    vendorCandidates.add(name);
                } else if (name.startsWith("c2.") && !lower.contains("android") && !lower.contains("google")) {
                    vendorCandidates.add(name);
                } else if (name.startsWith("OMX.") && !lower.contains("google")) {
                    omxCandidates.add(name);
                } else {
                    otherCandidates.add(name);
                }
                break;
            }
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(vendorCandidates);
        candidates.addAll(omxCandidates);
        candidates.addAll(otherCandidates);
        Log.d(TAG, "encoder candidates for " + mimeType + ": " + candidates);
        return candidates;
    }

    private void dumpEncoderCapability(String codecName, String mimeType) {
        try {
            MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo codecInfo : codecInfos) {
                if (!codecInfo.isEncoder() || !codecName.equals(codecInfo.getName())) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(mimeType);
                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                Log.d(TAG, codecName + " (" + mimeType + ") size=" + getVideoSizeLabel()
                        + " widths=" + videoCaps.getSupportedWidths()
                        + " heights=" + videoCaps.getSupportedHeights()
                        + " bitrate=" + videoCaps.getBitrateRange()
                        + " fps=" + videoCaps.getSupportedFrameRates()
                        + " isSizeSupported="
                        + videoCaps.isSizeSupported(getVideoWidth(), getVideoHeight())
                        + " isRateSupported="
                        + videoCaps.areSizeAndRateSupported(getVideoWidth(), getVideoHeight(), getVideoFps()));
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "dumpEncoderCapability failed " + codecName, e);
        }
    }

    public static final class CodecOption {
        public final String label;
        public final String mimeType;
        public final String shortName;

        public CodecOption(String label, String mimeType, String shortName) {
            this.label = label;
            this.mimeType = mimeType;
            this.shortName = shortName;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
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

        public FpsOption(int fps, String label) {
            this.fps = fps;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class FormatOption {
        final String label;
        final int bitRate;
        final int fps;
        final int bitRateMode;
        final boolean setColorAspects;
        final int profile;
        final int level;

        FormatOption(String label, int bitRate, int fps, int bitRateMode, boolean setColorAspects) {
            this(label, bitRate, fps, bitRateMode, setColorAspects, -1, -1);
        }

        FormatOption(String label, int bitRate, int fps, int bitRateMode, boolean setColorAspects,
                int profile, int level) {
            this.label = label;
            this.bitRate = bitRate;
            this.fps = fps;
            this.bitRateMode = bitRateMode;
            this.setColorAspects = setColorAspects;
            this.profile = profile;
            this.level = level;
        }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            while (encoderDraining) {
                int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Output format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (outputBufferId < 0) {
                    continue;
                }

                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
                if (encodedData == null) {
                    throw new IllegalStateException("Encoder output buffer was null");
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                }

                encoder.releaseOutputBuffer(outputBufferId, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "drainEncoder failed", e);
            runOnUiThread(() -> setStatus("Drain failed: " + e.getMessage()));
        } finally {
            encoderDraining = false;
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
        String codecTag = selectedCodec != null ? selectedCodec.shortName : "AVC";
        return new File(dir, "S1H_MC_" + timestamp + "_" + codecTag + "_" + getVideoSizeLabel() + ".mp4");
    }

    private int getVideoWidth() {
        return FIXED_WIDTH;
    }

    private int getVideoHeight() {
        return FIXED_HEIGHT;
    }

    private int getPreviewWidth() {
        return selectedResolution != null ? selectedResolution.previewWidth : FIXED_WIDTH;
    }

    private int getPreviewHeight() {
        return selectedResolution != null ? selectedResolution.previewHeight : FIXED_HEIGHT;
    }

    private int getVideoFps() {
        return selectedFps != null ? selectedFps.fps : DEFAULT_VIDEO_FPS;
    }

    private int getVideoBitRate() {
        return calculateBitRate(getVideoWidth(), getVideoHeight(), getVideoFps());
    }

    private int calculateBitRate(int width, int height, int fps) {
        long pixels = (long) width * height;
        if (pixels >= 3840L * 2160) {
            return 25_000_000;
        } else if (pixels >= 2560L * 1440) {
            return 16_000_000;
        } else if (pixels >= 2304L * 1296) {
            return 14_000_000;
        } else if (pixels >= 1920L * 1080) {
            return 12_000_000;
        } else if (pixels >= 1280L * 720) {
            return 6_000_000;
        } else {
            return 3_500_000;
        }
    }

    private String getSelectedMimeType() {
        return selectedCodec != null ? selectedCodec.mimeType : MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    private String getVideoSizeLabel() {
        return getVideoWidth() + "x" + getVideoHeight() + "@" + getVideoFps() + "fps";
    }

    private void closeCamera() {
        closeCaptureSession();
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

    private void releaseEncoder() {
        encoderDraining = false;
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "encoder stop failed", e);
            }
            encoder.release();
            encoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "muxer stop failed", e);
            }
            muxer.release();
            muxer = null;
        }
        muxerStarted = false;
        videoTrackIndex = -1;
        drainThread = null;
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("S1HMediaCodecCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void setStatus(String message) {
        Log.d(TAG, message);
        runOnUiThread(() -> statusText.setText(message));
    }
}
