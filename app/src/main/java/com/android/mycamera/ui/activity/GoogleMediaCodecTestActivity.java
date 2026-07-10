package com.android.mycamera.ui.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
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

public class GoogleMediaCodecTestActivity extends BaseAct {
    private static final String TAG = "MediaCodecTest";
    private static final int REQUEST_CAMERA = 2304;

    private static final String CAMERA_ID = "0";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final EncoderOption[] ENCODER_OPTIONS = {
            new EncoderOption("OMX.google.h264.encoder", "google"),
            new EncoderOption("c2.unisoc.avc.encoder", "unisoc")
    };
    private static final int VIDEO_WIDTH = 2304;
    private static final int VIDEO_HEIGHT = 1296;
    private static final int VIDEO_FPS = 24;
    private static final int VIDEO_BIT_RATE = 12_000_000;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int PROBE_WIDTH = 2304;
    private static final int PROBE_HEIGHT = 1296;

    private TextureView previewView;
    private Button googleRecordButton;
    private Button unisocRecordButton;
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
    private volatile EncoderOption selectedEncoder = ENCODER_OPTIONS[0];

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
            setStatus("Camera opened: " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT);
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
        setContentView(R.layout.activity_google_media_codec_test);

        previewView = findViewById(R.id.codecPreview);
        googleRecordButton = findViewById(R.id.codecGoogleRecordButton);
        unisocRecordButton = findViewById(R.id.codecUnisocRecordButton);
        statusText = findViewById(R.id.codecStatus);

        googleRecordButton.setOnClickListener(v -> handleRecordButton(ENCODER_OPTIONS[0]));
        unisocRecordButton.setOnClickListener(v -> handleRecordButton(ENCODER_OPTIONS[1]));

        setStatus("Ready " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + "@" + VIDEO_FPS);

    }

    private void handleRecordButton(EncoderOption encoderOption) {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording(encoderOption);
        }
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
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
            setStatus("Opening camera " + cameraId);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
            setStatus("Open camera failed: " + e.getMessage());
        }
    }

    private void startRecording(EncoderOption encoderOption) {
        if (cameraDevice == null) {
            setStatus("Camera not ready");
            return;
        }
        try {
            selectedEncoder = encoderOption;
            setupEncoder();
            encoder.start();
            encoderDraining = true;
            drainThread = new Thread(this::drainEncoder, "CodecDrainThread");
            drainThread.start();
            isRecording = true;
            updateRecordButtons(true);
            startCameraSession();
            setStatus("Recording " + activeCodecName + " " + VIDEO_WIDTH + "x"
                    + VIDEO_HEIGHT + "@" + VIDEO_FPS);
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            setStatus("Start failed: " + e.getMessage());
            isRecording = false;
            updateRecordButtons(false);
            releaseEncoder();
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }
        setStatus("Stopping...");
        isRecording = false;
        googleRecordButton.setEnabled(false);
        unisocRecordButton.setEnabled(false);
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
        updateRecordButtons(false);
        setStatus("Saved: " + (outputFile == null ? "" : outputFile.getAbsolutePath()));
        startCameraSession();
    }

    private void updateRecordButtons(boolean recording) {
        if (!recording) {
            googleRecordButton.setText("Start Google");
            unisocRecordButton.setText("Start Unisoc");
            googleRecordButton.setEnabled(true);
            unisocRecordButton.setEnabled(true);
            return;
        }
        boolean recordingGoogle = "google".equals(selectedEncoder.fileLabel);
        googleRecordButton.setText(recordingGoogle ? "Stop Google" : "Start Google");
        unisocRecordButton.setText(recordingGoogle ? "Start Unisoc" : "Stop Unisoc");
        googleRecordButton.setEnabled(recordingGoogle);
        unisocRecordButton.setEnabled(!recordingGoogle);
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
            texture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT);
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
                    new android.util.Range<>(VIDEO_FPS, VIDEO_FPS));

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
            IOException wrapped = new IOException("No encoder accepts " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT);
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
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, option.bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, option.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        if (option.bitRateMode >= 0) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, option.bitRateMode);
        }
        if (option.setColorAspects) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        if (option.setAvcLevel) {
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            format.setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51);
        }
        return format;
    }

    private List<FormatOption> buildFormatOptions() {
        List<FormatOption> options = new ArrayList<>();
        options.add(new FormatOption("cbr", VIDEO_BIT_RATE, VIDEO_FPS,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false, false));
        options.add(new FormatOption("no-bitrate-mode", VIDEO_BIT_RATE, VIDEO_FPS,
                -1, false, false));
        options.add(new FormatOption("vbr", VIDEO_BIT_RATE, VIDEO_FPS,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, false, false));
        options.add(new FormatOption("cbr-bt709", VIDEO_BIT_RATE, VIDEO_FPS,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, true, false));
        options.add(new FormatOption("cbr-avc-high-l51", VIDEO_BIT_RATE, VIDEO_FPS,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false, true));
        options.add(new FormatOption("cbr-8m", 8_000_000, VIDEO_FPS,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false, false));
        options.add(new FormatOption("cbr-25fps", VIDEO_BIT_RATE, 25,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, false, false));
        return options;
    }

    private List<String> getEncoderCandidates(String mimeType) {
        List<String> candidates = new ArrayList<>();
        if (hasEncoder(selectedEncoder.codecName, mimeType)) {
            candidates.add(selectedEncoder.codecName);
        }
        Log.d(TAG, "encoder candidates for " + mimeType + ": " + candidates);
        return candidates;
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
                    + " isSizeSupported(" + PROBE_WIDTH + "x" + PROBE_HEIGHT + ")="
                    + videoCaps.isSizeSupported(PROBE_WIDTH, PROBE_HEIGHT)
                    + " is30fpsSupported="
                    + videoCaps.areSizeAndRateSupported(PROBE_WIDTH, PROBE_HEIGHT, VIDEO_FPS)
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

    private static final class FormatOption {
        final String label;
        final int bitRate;
        final int fps;
        final int bitRateMode;
        final boolean setColorAspects;
        final boolean setAvcLevel;

        FormatOption(String label, int bitRate, int fps, int bitRateMode,
                     boolean setColorAspects, boolean setAvcLevel) {
            this.label = label;
            this.bitRate = bitRate;
            this.fps = fps;
            this.bitRateMode = bitRateMode;
            this.setColorAspects = setColorAspects;
            this.setAvcLevel = setAvcLevel;
        }
    }

    private static final class EncoderOption {
        final String codecName;
        final String fileLabel;

        EncoderOption(String codecName, String fileLabel) {
            this.codecName = codecName;
            this.fileLabel = fileLabel;
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
        return new File(dir, "MC_" + timestamp + "_2304x1296@" + VIDEO_FPS
                + "_" + selectedEncoder.fileLabel + ".mp4");
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
