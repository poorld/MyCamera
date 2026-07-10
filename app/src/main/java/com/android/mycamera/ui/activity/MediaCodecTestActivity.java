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
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MediaCodecTestActivity extends BaseAct {
    private static final String TAG = "MediaCodecTest";
    private static final int REQUEST_CAMERA = 1001;

    private static final String CAMERA_ID = "0";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String PREFERRED_CODEC_NAME = "c2.unisoc.avc.encoder";
    private static final boolean REQUIRE_PREFERRED_CODEC = true;
    private static final int DEFAULT_VIDEO_FPS = 30;
    private static final int DEFAULT_VIDEO_BIT_RATE = 12_000_000;
    private static final int I_FRAME_INTERVAL = 1;
    private static final ResolutionOption[] RESOLUTION_OPTIONS = {
            // new ResolutionOption("2560x1440p30", 2560, 1440, 1920, 1080, 30, 14_000_000),
            // new ResolutionOption("1920x1080p30", 1920, 1080),
            new ResolutionOption("2304x1296p30", 2304, 1296),
    };

    private TextureView previewView;
    private Button recordButton;
    private Spinner resolutionSpinner;
    private TextView statusText;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
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
    private volatile ResolutionOption selectedResolution = RESOLUTION_OPTIONS[0];

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
        resolutionSpinner = findViewById(R.id.codecResolutionSpinner);
        statusText = findViewById(R.id.codecStatus);

        setupResolutionSpinner();

        recordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

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
        logTargetSizes("SurfaceTexture", map.getOutputSizes(SurfaceTexture.class));
        logTargetSizes("MediaRecorder", map.getOutputSizes(MediaRecorder.class));
        logTargetSizes("JPEG", map.getOutputSizes(ImageFormat.JPEG));
    }

    private void logTargetSizes(String output, Size[] sizes) {
        Log.d(TAG, "camera " + output
                + " has 1920x1080=" + containsSize(sizes, 1920, 1080)
                + " 2560x1440=" + containsSize(sizes, 2560, 1440));
    }

    private boolean containsSize(Size[] sizes, int width, int height) {
        if (sizes == null) {
            return false;
        }
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                return true;
            }
        }
        return false;
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
            resolutionSpinner.setEnabled(false);
            startCameraSession();
            setStatus("Recording " + activeCodecName + " " + getVideoSizeLabel());
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            setStatus("Start failed: " + e.getMessage());
            isRecording = false;
            resolutionSpinner.setEnabled(true);
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
        resolutionSpinner.setEnabled(true);
        setStatus("Saved: " + (outputFile == null ? "" : outputFile.getAbsolutePath()));
        startCameraSession();
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
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new android.util.Range<>(getVideoFps(), getVideoFps()));

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "setRepeatingRequest failed", e);
                        setStatus("Preview request failed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    setStatus("Session config failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "startCameraSession failed", e);
            setStatus("Session failed: " + e.getMessage());
        }
    }

    private void setupEncoder() throws IOException {
        Exception lastError = null;
        List<String> candidates = getEncoderCandidates(MIME_TYPE);
        List<FormatOption> options = buildFormatOptions();
        for (String codecName : candidates) {
            dumpEncoderCapability(codecName, MIME_TYPE);
            for (FormatOption option : options) {
                MediaCodec trialEncoder = null;
                try {
                    MediaFormat format = createEncodeFormat(option);
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
            IOException wrapped = new IOException("No encoder accepts " + getVideoSizeLabel());
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

    private MediaFormat createEncodeFormat(FormatOption option) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, getVideoWidth(), getVideoHeight());
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

    private List<FormatOption> buildFormatOptions() {
        List<FormatOption> options = new ArrayList<>();
        options.add(new FormatOption("cbr-high-l51-bt709", getVideoBitRate(), getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("no-bitrate-mode-high-l51-bt709", getVideoBitRate(), getVideoFps(),
                -1, true,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("cbr-8m-high-l51-bt709", 8_000_000, getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("cbr-25fps-high-l51-bt709", getVideoBitRate(), 25,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("cbr-23fps-high-l51-bt709", getVideoBitRate(), 23,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("cbr", getVideoBitRate(), getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        options.add(new FormatOption("no-bitrate-mode", getVideoBitRate(), getVideoFps(),
                -1, false));
        options.add(new FormatOption("vbr", getVideoBitRate(), getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, false));
        options.add(new FormatOption("cbr-bt709", getVideoBitRate(), getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true));
        options.add(new FormatOption("cbr-avc-high-l51", getVideoBitRate(), getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51));
        options.add(new FormatOption("cbr-8m", 8_000_000, getVideoFps(),
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        options.add(new FormatOption("cbr-23fps", getVideoBitRate(), 23,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        options.add(new FormatOption("cbr-25fps", getVideoBitRate(), 25,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false));
        return options;
    }

    private List<String> getEncoderCandidates(String mimeType) {
        if (REQUIRE_PREFERRED_CODEC) {
            List<String> candidates = new ArrayList<>();
            if (hasEncoder(PREFERRED_CODEC_NAME, mimeType)) {
                candidates.add(PREFERRED_CODEC_NAME);
            } else {
                Log.e(TAG, "preferred encoder not found: " + PREFERRED_CODEC_NAME
                        + " mimeType=" + mimeType);
            }
            return candidates;
        }

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
                if (name.startsWith("OMX.")) {
                    omxCandidates.add(name);
                } else {
                    otherCandidates.add(name);
                }
                break;
            }
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(omxCandidates);
        candidates.addAll(otherCandidates);
        moveEncoderToFront(candidates, PREFERRED_CODEC_NAME, mimeType);
        Log.d(TAG, "encoder candidates for " + mimeType + ": " + candidates);
        return candidates;
    }

    private void moveEncoderToFront(List<String> candidates, String codecName, String mimeType) {
        while (candidates.remove(codecName)) {
            // Remove duplicates before applying preferred order.
        }
        if (hasEncoder(codecName, mimeType)) {
            candidates.add(0, codecName);
        }
    }

    private boolean hasEncoder(String codecName, String mimeType) {
        MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder() || !codecName.equals(codecInfo.getName())) {
                continue;
            }
            for (String type : codecInfo.getSupportedTypes()) {
                if (mimeType.equalsIgnoreCase(type)) {
                    return true;
                }
            }
        }
        return false;
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
                Log.d(TAG, codecName + " selected size=" + getVideoSizeLabel()
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

    private void dumpEncoderCapabilities() {
        try {
            MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo codecInfo : codecInfos) {
                if (!codecInfo.isEncoder()) {
                    continue;
                }
                Log.d(TAG, "encoder " + codecInfo.getName()
                        + " types=" + Arrays.toString(codecInfo.getSupportedTypes()));
                for (String type : codecInfo.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)
                            && !MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type)) {
                        continue;
                    }
                    dumpVideoCapability(codecInfo, type);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "dumpEncoderCapabilities failed", e);
        }
    }

    private void dumpVideoCapability(MediaCodecInfo codecInfo, String type) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(type);
            MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
            MediaCodecInfo.EncoderCapabilities encoderCaps = caps.getEncoderCapabilities();
            Log.d(TAG, codecInfo.getName() + " " + type
                    + " widths=" + videoCaps.getSupportedWidths()
                    + " heights=" + videoCaps.getSupportedHeights()
                    + " widthAlign=" + videoCaps.getWidthAlignment()
                    + " heightAlign=" + videoCaps.getHeightAlignment()
                    + " bitrate=" + videoCaps.getBitrateRange()
                    + " fps=" + videoCaps.getSupportedFrameRates()
                    + " isSizeSupported(" + getVideoSizeLabel() + ")="
                    + videoCaps.isSizeSupported(getVideoWidth(), getVideoHeight())
                    + " isTargetFpsSupported="
                    + videoCaps.areSizeAndRateSupported(getVideoWidth(), getVideoHeight(), getVideoFps())
                    + " modes[cbr/vbr/cq]="
                    + encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    + "/"
                    + encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    + "/"
                    + encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ));
            for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) {
                Log.d(TAG, codecInfo.getName() + " " + type
                        + " profile=" + level.profile + " level=" + level.level);
            }
        } catch (Exception e) {
            Log.e(TAG, "dumpVideoCapability failed " + codecInfo.getName() + " " + type, e);
        }
    }

    private static final class ResolutionOption {
        final String label;
        final int width;
        final int height;
        final int previewWidth;
        final int previewHeight;
        final int fps;
        final int bitRate;

        ResolutionOption(String label, int width, int height) {
            this(label, width, height, width, height, DEFAULT_VIDEO_FPS, DEFAULT_VIDEO_BIT_RATE);
        }

        ResolutionOption(String label, int width, int height, int previewWidth, int previewHeight,
                int fps, int bitRate) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.fps = fps;
            this.bitRate = bitRate;
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
        return new File(dir, "MC_" + timestamp + "_" + getVideoSizeLabel() + ".mp4");
    }

    private void setupResolutionSpinner() {
        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, RESOLUTION_OPTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(0, false);
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                selectedResolution = RESOLUTION_OPTIONS[position];
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

    private int getVideoWidth() {
        return selectedResolution.width;
    }

    private int getVideoHeight() {
        return selectedResolution.height;
    }

    private int getPreviewWidth() {
        return selectedResolution.previewWidth;
    }

    private int getPreviewHeight() {
        return selectedResolution.previewHeight;
    }

    private int getVideoFps() {
        return selectedResolution.fps;
    }

    private int getVideoBitRate() {
        return selectedResolution.bitRate;
    }

    private String getVideoSizeLabel() {
        return selectedResolution.width + "x" + selectedResolution.height
                + "@" + selectedResolution.fps;
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
        cameraThread = new HandlerThread("MediaCodecCameraThread");
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
