package com.android.mycamera.camera.strategy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.PhotoResolution;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.CameraUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Camera2Strategy extends BaseCameraStrategy {
    
    private static final String TAG = "Camera2Strategy";
    private static final String MTK_HFPS_MODE_KEY =
            "com.mediatek.streamingfeature.hfpsMode";
    private static final int MTK_HFPS_MODE_60FPS = 1;
    private static final int SLOW_MOTION_PLAYBACK_FPS = 30;
    
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    /** Dummy VIDEO_ENCODER consumer so MTK HAL sets hasVideoConsumer/videoImageSize. */
    private ImageReader videoImageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private TextureView textureView;
    private CameraConfig currentConfig;
    private CameraConfig pendingOpenConfig;
    private boolean isCameraClosing = false;
    private boolean isFlashEnabled = false;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private boolean isStoppingRecording = false;
    private File recordingOutputFile;
    private float zoomRatio = 1f;
    private Range<Integer> supportedIsoRange;
    private Range<Long> supportedExposureTimeRange;
    private boolean manualExposureSupported;
    private boolean manualExposureEnabled;
    private int manualIso = 100;
    private long manualExposureTimeNs = 10_000_000L;
    private Range<Integer> autoBrightnessCompensationRange;
    private int autoBrightnessCompensationIndex;
    private long lastAutoBrightnessAdjustmentMs;
    private final CameraCaptureSession.CaptureCallback autoBrightnessCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    adjustAutoBrightness(result);
                }
            };

    public Camera2Strategy(Context context) {
        super(context);
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void openCamera(CameraConfig config) {
        Log.d(TAG, "openCamera: config=" + (config != null ? config.getResolution() : null));
        if (config == null) {
            return;
        }

        // CameraDevice.close() is async. Queue reopen until onClosed.
        if (isCameraClosing) {
            Log.d(TAG, "openCamera: camera still closing, queue reopen for " + config.getResolution());
            pendingOpenConfig = config;
            this.currentConfig = config;
            return;
        }

        boolean sameDevice = cameraDevice != null
                && currentState != CameraState.ERROR
                && currentState != CameraState.CLOSED
                && currentState != CameraState.IDLE;
        boolean pipelineChanged = currentConfig == null
                || currentConfig.getResolution() == null
                || !currentConfig.getResolution().equals(config.getResolution())
                || currentConfig.getFrameRate() != config.getFrameRate()
                || currentConfig.getCaptureMode() != config.getCaptureMode()
                || currentConfig.getPhotoResolution() != config.getPhotoResolution()
                || !TextUtilsEquals(currentConfig.getCameraId(), config.getCameraId());

        this.currentConfig = config;

        if (sameDevice && !pipelineChanged) {
            Log.d(TAG, "openCamera: already open with same config, re-notify OPENED");
            // Force UI out of "Initializing..." even if state did not change.
            forceNotifyState(CameraState.OPENED);
            return;
        }

        if (sameDevice && pipelineChanged) {
            // Sensor mode is chosen at session configure time. Rebuild session only;
            // full device close/reopen is what freezes 2K->1080 on this platform.
            Log.d(TAG, "openCamera: pipeline changed (res/mode/photo), keep device and rebuild session"
                    + " mode=" + config.getCaptureMode()
                    + " res=" + config.getResolution()
                    + " photo=" + config.getPhotoResolution());
            forceNotifyState(CameraState.OPENED);
            return;
        }

        loadManualExposureCapabilities(config.getCameraId());
        startOrientationUpdates();
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "openCamera: requesting cameraId=" + config.getCameraId()
                    + " resolution=" + config.getResolution());
            manager.openCamera(config.getCameraId(), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: ");
                    isCameraClosing = false;
                    cameraDevice = camera;
                    forceNotifyState(CameraState.OPENED);
                }
                @Override
                public void onClosed(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onClosed: pending=" + pendingOpenConfig);
                    isCameraClosing = false;
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                    forceNotifyState(CameraState.CLOSED);
                    CameraConfig pending = pendingOpenConfig;
                    pendingOpenConfig = null;
                    if (pending != null) {
                        openCamera(pending);
                    }
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onDisconnected: ");
                    isCameraClosing = false;
                    camera.close();
                    cameraDevice = null;
                    forceNotifyState(CameraState.CLOSED);
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "onError: code=" + error);
                    isCameraClosing = false;
                    try {
                        camera.close();
                    } catch (Exception ignored) {
                    }
                    cameraDevice = null;
                    CameraConfig pending = pendingOpenConfig;
                    pendingOpenConfig = null;
                    notifyError("Camera error: " + error);
                    // Retry once after error if a pending config exists or current config remains.
                    if (pending != null) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> openCamera(pending), 200);
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to access camera", e);
            notifyError("Failed to access camera: " + e.getMessage());
        }
    }

    private static boolean TextUtilsEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public void bindConfiguration(CameraConfig config) {
        if (config != null) {
            this.currentConfig = config;
            Log.d(TAG, "bindConfiguration mode=" + config.getCaptureMode()
                    + ", videoRes=" + config.getResolution()
                    + ", photoRes=" + config.getPhotoResolution());
        }
    }

    @Override
    public void startPreview(TextureView textureView, Object lifecycleOwner) {
        Log.d(TAG, "startPreview: cameraDevice=" + cameraDevice
                + ", resolution=" + (currentConfig != null ? currentConfig.getResolution() : null));
        if (cameraDevice == null) {
            Log.w(TAG, "startPreview: cameraDevice is null, state=" + currentState);
            return;
        }
        this.textureView = textureView;
        // Always rebuild session so resolution switches (2K <-> 1080p) take effect.
        if (captureSession != null) {
            Log.d(TAG, "startPreview: recreating capture session for updated config");
            closeCaptureSession();
        }
        try {
            createPreviewSession();
        } catch (Exception e) {
            logError("Failed to create preview session", e);
        }
    }

    @Override
    public void capturePhoto() {
        if (cameraDevice == null || captureSession == null) return;
        if (imageReader == null) {
            Log.w(TAG, "capturePhoto: JPEG ImageReader missing (video pipeline active?)");
            notifyError("Switch to photo mode to capture stills");
            return;
        }
        try {
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());
            applyZoom(captureBuilder);
            applyFlashToRequest(captureBuilder);

            imageReader.setOnImageAvailableListener(reader -> {
                try (android.media.Image image = reader.acquireNextImage()) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    File outputFile = CameraUtils.generateUniqueMediaFile(context, "jpg");
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(bytes);
                    }
                    notifyPhotoCaptured(outputFile.getAbsolutePath());
                } catch (IOException e) {
                    logError("Failed to save photo", e);
                }
            }, backgroundHandler);

            captureSession.capture(captureBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to capture photo", e);
        }
    }

    @Override
    public void setFocusPoint(float x, float y) {
        if (cameraDevice == null || captureSession == null || textureView == null || previewRequestBuilder == null) return;

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensorArraySize == null) return;

            int y_coord = (int) ((x / textureView.getWidth()) * (float) sensorArraySize.height());
            int x_coord = (int) ((y / textureView.getHeight()) * (float) sensorArraySize.width());
            int halfTouchWidth = 150;
            MeteringRectangle focusArea = new MeteringRectangle(
                Math.max(x_coord - halfTouchWidth,  0),
                Math.max(y_coord - halfTouchWidth, 0),
                halfTouchWidth  * 2,
                halfTouchWidth * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);

            // Cancel any previous trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);

            // Set new focus area
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{focusArea});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            CameraCaptureSession.CaptureCallback focusCallback = new CameraCaptureSession.CaptureCallback() {
                private boolean focusFinished = false;

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    if (focusFinished) return;
                    
                    Integer afState = result.get(TotalCaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        finishFocus(true); // Assume success if state is not available
                        return;
                    }

                    if (afState == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureRequest.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        finishFocus(afState == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED);
                    }
                }

                private void finishFocus(boolean success) {
                    if (focusFinished) return;
                    focusFinished = true;
                    resetFocusMode();
                }
            };

            captureSession.setRepeatingRequest(previewRequestBuilder.build(), focusCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            logError("Failed to set focus point", e);
        }
    }

    private void resetFocusMode() {
        if (captureSession == null || previewRequestBuilder == null) return;
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to reset focus", e);
        }
    }

    @Override
    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        stopOrientationUpdates();
        if (isRecording) {
            stopRecordingInternal(false);
        }
        if (captureSession != null) {
            closeCaptureSession();
        }
        releasePhotoImageReader();
        releaseVideoEncoderReader();
        if (cameraDevice != null) {
            isCameraClosing = true;
            try {
                cameraDevice.close();
            } catch (Exception e) {
                logError("Error closing camera device", e);
                isCameraClosing = false;
            }
            // Nulled now; onClosed still arrives on the original callback.
            cameraDevice = null;
            // Failsafe: some devices may not deliver onClosed promptly.
            if (pendingOpenConfig != null) {
                Handler handler = backgroundHandler != null
                        ? backgroundHandler
                        : new Handler(Looper.getMainLooper());
                final CameraConfig pending = pendingOpenConfig;
                handler.postDelayed(() -> {
                    if (isCameraClosing || cameraDevice == null) {
                        Log.w(TAG, "closeCamera failsafe reopen for " + pending.getResolution());
                        isCameraClosing = false;
                        if (pendingOpenConfig == pending) {
                            pendingOpenConfig = null;
                            openCamera(pending);
                        }
                    }
                }, 400);
            }
        } else {
            isCameraClosing = false;
            forceNotifyState(CameraState.CLOSED);
            CameraConfig pending = pendingOpenConfig;
            pendingOpenConfig = null;
            if (pending != null) {
                openCamera(pending);
            }
        }
        // Keep background thread alive until pending reopen finishes; stop if no pending.
        if (pendingOpenConfig == null && !isCameraClosing) {
            stopBackgroundThread();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                logError("Failed to stop background thread", e);
            }
        }
    }

    @Override
    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (captureSession != null) {
            closeCaptureSession();
        }
    }

    @Override
    public void startRecording() {
        Log.d(TAG, "startRecording: ");
        Log.d(TAG, "cameraDevice=" + cameraDevice);
        Log.d(TAG, "isRecording=" + isRecording);
        if (cameraDevice == null || isRecording) return;

        // Recording always uses the video pipeline. Keep CaptureMode.VIDEO so
        // stopRecording -> createPreviewSession does not fall back to photo/JPEG.
        if (currentConfig != null
                && CaptureMode.normalize(currentConfig.getCaptureMode()) != CaptureMode.VIDEO) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setCaptureMode(CaptureMode.VIDEO)
                    .build();
            Log.d(TAG, "startRecording: force CaptureMode.VIDEO for recording/preview restore");
        }

        try {
            if (!setupMediaRecorder()) return;

            if (textureView == null) {
                logError("Cannot start Camera2 recording: textureView is null", null);
                releaseMediaRecorder();
                return;
            }
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                logError("Cannot start Camera2 recording: surfaceTexture is null", null);
                releaseMediaRecorder();
                return;
            }
            
            Resolution resolution = currentConfig.getResolution();
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recorderSurface = mediaRecorder.getSurface();

            closeCaptureSession();

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            applyFpsToRequest(builder);
            applyMtkHfpsMode(builder);
            applyFlashToRequest(builder);

            if (isCurrentHighSpeedVideoConfiguration()) {
                startConstrainedHighSpeedRecording(previewSurface, recorderSurface, builder);
                return;
            }

            // MTK selects the sensor scenario from sessionParams, before it sees
            // the repeating request. Put hfpsMode/FPS here so 1080p60 selects a
            // 60fps sensor scenario rather than the normal VIDEO 30fps mode.
            CaptureRequest.Builder sessionParamsBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            sessionParamsBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            applyFpsToRequest(sessionParamsBuilder);
            applyMtkHfpsMode(sessionParamsBuilder);

            SessionConfiguration sessionConfiguration = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(new OutputConfiguration(previewSurface),
                            new OutputConfiguration(recorderSurface)),
                    context.getMainExecutor(), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null || backgroundHandler == null) {
                        session.close();
                        return;
                    }

                    captureSession = session;
                    previewRequestBuilder = builder;
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        isRecording = true;
                        notifyRecordingStarted();
                    } catch (CameraAccessException | IllegalStateException e) {
                        logError("Failed to start Camera2 media recorder", e);
                        closeCaptureSession();
                        releaseMediaRecorder();
                        createPreviewSession();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    session.close();
                    releaseMediaRecorder();
                    createPreviewSession();
                    notifyError("Recording configuration failed");
                }
            });
            sessionConfiguration.setSessionParameters(sessionParamsBuilder.build());
            cameraDevice.createCaptureSession(sessionConfiguration);
        } catch (CameraAccessException e) {
            logError("Failed to create recording session", e);
            releaseMediaRecorder();
            createPreviewSession();
        } catch (IllegalArgumentException | IllegalStateException e) {
            logError("Failed to start Camera2 recording", e);
            releaseMediaRecorder();
            createPreviewSession();
        }
    }

    private void startConstrainedHighSpeedRecording(
            Surface previewSurface,
            Surface recorderSurface,
            CaptureRequest.Builder builder) throws CameraAccessException {
        final int highSpeedFps = currentConfig.getFrameRate();
        Range<Integer> highSpeedRange = getCurrentHighSpeedFpsRange();
        if (highSpeedRange == null) {
            throw new IllegalStateException("No high-speed FPS range for " + highSpeedFps + " FPS");
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                highSpeedRange);

        cameraDevice.createConstrainedHighSpeedCaptureSession(
                Arrays.asList(previewSurface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (!(session instanceof CameraConstrainedHighSpeedCaptureSession)
                                || cameraDevice == null || backgroundHandler == null) {
                            session.close();
                            releaseMediaRecorder();
                            createPreviewSession();
                            notifyError("High-speed recording configuration failed");
                            return;
                        }

                        captureSession = session;
                        previewRequestBuilder = builder;
                        CameraConstrainedHighSpeedCaptureSession highSpeedSession =
                                (CameraConstrainedHighSpeedCaptureSession) session;
                        try {
                            List<CaptureRequest> requests =
                                    highSpeedSession.createHighSpeedRequestList(builder.build());
                            highSpeedSession.setRepeatingBurst(requests, null, backgroundHandler);
                            mediaRecorder.start();
                            isRecording = true;
                            notifyRecordingStarted();
                        } catch (CameraAccessException | IllegalStateException e) {
                            logError("Failed to start constrained high-speed recording", e);
                            closeCaptureSession();
                            releaseMediaRecorder();
                            createPreviewSession();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        session.close();
                        releaseMediaRecorder();
                        createPreviewSession();
                        notifyError("High-speed recording configuration failed");
                    }
                }, backgroundHandler);
    }

    private boolean isCurrentHighSpeedVideoConfiguration() {
        return getCurrentHighSpeedFpsRange() != null;
    }

    private Range<Integer> getCurrentHighSpeedFpsRange() {
        if (currentConfig == null) {
            return null;
        }
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return null;
            }
            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(currentConfig.getCameraId());
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Size size = new Size(currentConfig.getResolution().getWidth(),
                    currentConfig.getResolution().getHeight());
            Range<Integer> bestRange = null;
            for (Range<Integer> range : map.getHighSpeedVideoFpsRangesFor(size)) {
                if (range != null && range.getUpper() == currentConfig.getFrameRate()) {
                    if (range.getLower().equals(range.getUpper())) {
                        return range;
                    }
                    if (bestRange == null) {
                        bestRange = range;
                    }
                }
            }
            return bestRange;
        } catch (CameraAccessException | IllegalArgumentException e) {
            logError("Failed to query high-speed video capability", e);
        }
        return null;
    }

    @Override
    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (backgroundHandler == null) {
            stopRecordingInternal(true);
            return;
        }
        backgroundHandler.post(() -> stopRecordingInternal(true));
    }

    private void stopRecordingInternal(boolean restartPreview) {
        if ((!isRecording && mediaRecorder == null) || isStoppingRecording) return;

        isStoppingRecording = true;
        boolean wasRecording = isRecording;
        try {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                    captureSession.abortCaptures();
                } catch (CameraAccessException | IllegalStateException e) {
                    logError("Failed to stop recording session requests", e);
                }
            }
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (RuntimeException e) {
                    logError("Failed to stop media recorder cleanly", e);
                }
            }
            closeCaptureSession();
            releaseMediaRecorder();
            isRecording = false;
            if (wasRecording) {
                notifyRecordingStopped();
                if (recordingOutputFile != null) {
                    notifyPhotoCaptured(recordingOutputFile.getAbsolutePath());
                }
            }
            if (restartPreview) {
                Log.d(TAG, "stopRecordingInternal: restore preview mode="
                        + (currentConfig != null ? currentConfig.getCaptureMode() : null)
                        + ", res=" + (currentConfig != null ? currentConfig.getResolution() : null));
                // If UI/recording path requested video, never restore photo JPEG pipeline here.
                if (currentConfig != null
                        && CaptureMode.normalize(currentConfig.getCaptureMode()) != CaptureMode.VIDEO) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setCaptureMode(CaptureMode.VIDEO)
                            .build();
                }
                createPreviewSession();
            }
        } finally {
            isStoppingRecording = false;
        }
    }

    private void createPreviewSession() {
        Log.d(TAG, "createPreviewSession: mode="
                + (currentConfig != null ? currentConfig.getCaptureMode() : null)
                + ", videoRes=" + (currentConfig != null ? currentConfig.getResolution() : null)
                + ", photoRes=" + (currentConfig != null ? currentConfig.getPhotoResolution() : null));
        if (cameraDevice == null || textureView == null || currentConfig == null) {
            Log.w(TAG, "createPreviewSession: missing camera/texture/config");
            return;
        }
        closeCaptureSession();
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                Log.w(TAG, "createPreviewSession: surfaceTexture is null");
                return;
            }

            boolean videoMode = isVideoCaptureMode();
            Resolution resolution = currentConfig.getResolution();

            Size previewBufferSize;
            Size photoSize = null;
            if (videoMode) {
                previewBufferSize = new Size(resolution.getWidth(), resolution.getHeight());
            } else {
                // Still mode: choose JPEG first, then a moderate 4:3 preview so HAL
                // maxImageSize follows the still target (12M/36M/...), not video 2560x1440.
                photoSize = requirePhotoCaptureSize();
                try {
                    CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    StreamConfigurationMap map = cm.getCameraCharacteristics(currentConfig.getCameraId())
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    previewBufferSize = choosePhotoPreviewSize(map, photoSize);
                } catch (Exception e) {
                    previewBufferSize = new Size(1440, 1080);
                    Log.w(TAG, "photo preview size fallback", e);
                }
            }
            surfaceTexture.setDefaultBufferSize(previewBufferSize.getWidth(), previewBufferSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            int template = videoMode
                    ? CameraDevice.TEMPLATE_RECORD
                    : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(template);
            builder.addTarget(previewSurface);

            java.util.ArrayList<Surface> outputs = new java.util.ArrayList<>();
            outputs.add(previewSurface);

            if (videoMode) {
                // MTK hasVideoConsumer requires GRALLOC_USAGE_HW_VIDEO_ENCODER.
                // Without it, HAL always falls into photo mode via JPEG maxImageSize.
                ensureVideoEncoderReader(resolution.getWidth(), resolution.getHeight());
                releasePhotoImageReader();
                outputs.add(videoImageReader.getSurface());
                builder.addTarget(videoImageReader.getSurface());
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                Log.d(TAG, "createPreviewSession: VIDEO pipeline encoderSurface="
                        + resolution.getWidth() + "x" + resolution.getHeight());
            } else {
                releaseVideoEncoderReader();
                // Same as MTK PhotoDevice2Controller.setPictureSize -> CaptureSurface.updatePictureInfo:
                // ImageReader is ALWAYS the selected still size (e.g. 6912x5184), never preview size.
                PhotoResolution still = PhotoResolution.normalize(
                        currentConfig.getPhotoResolution(), currentConfig.getCameraId());
                photoSize = new Size(still.getWidth(), still.getHeight());
                ensurePhotoImageReader(photoSize.getWidth(), photoSize.getHeight());
                if (imageReader == null
                        || imageReader.getWidth() != photoSize.getWidth()
                        || imageReader.getHeight() != photoSize.getHeight()) {
                    throw new IllegalStateException("JPEG ImageReader not at still size "
                            + photoSize + " actual="
                            + (imageReader == null ? null
                            : imageReader.getWidth() + "x" + imageReader.getHeight()));
                }
                outputs.add(imageReader.getSurface());
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                Log.i(TAG, "createPreviewSession: PHOTO pipeline jpegReader="
                        + imageReader.getWidth() + "x" + imageReader.getHeight()
                        + ", preview=" + previewBufferSize.getWidth() + "x" + previewBufferSize.getHeight()
                        + ", configPhoto=" + still.getDisplayName()
                        + ", outputs=" + outputs.size());
            }

            applyFpsToRequest(builder);
            applyFlashToRequest(builder);
            applyZoom(builder);
            previewRequestBuilder = builder;

            final boolean photoPipeline = !videoMode;
            final Size configuredPhotoSize = photoSize;
            final Size configuredPreviewSize = previewBufferSize;
            cameraDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        session.close();
                        return;
                    }
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(),
                                autoBrightnessCaptureCallback, backgroundHandler);
                        notifyStateChanged(CameraState.PREVIEW_STARTED);
                        notifyPreviewStarted();
                        if (photoPipeline && configuredPhotoSize != null) {
                            Log.i(TAG, "PHOTO session configured ok jpeg="
                                    + configuredPhotoSize.getWidth() + "x"
                                    + configuredPhotoSize.getHeight()
                                    + " preview=" + configuredPreviewSize.getWidth() + "x"
                                    + configuredPreviewSize.getHeight());
                        }
                    } catch (CameraAccessException | IllegalStateException e) {
                        logError("Failed to start preview repeating request", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "createPreviewSession onConfigureFailed mode="
                            + currentConfig.getCaptureMode()
                            + ", photo=" + configuredPhotoSize
                            + ", preview=" + configuredPreviewSize);
                    // High-res JPEG + large preview can be an unsupported combo on some
                    // devices; retry still pipeline once with 1440x1080 preview.
                    if (photoPipeline && configuredPreviewSize != null
                            && (configuredPreviewSize.getWidth() > 1440
                            || configuredPreviewSize.getHeight() > 1080)
                            && configuredPhotoSize != null) {
                        Log.w(TAG, "Retry PHOTO session with 1440x1080 preview + jpeg "
                                + configuredPhotoSize);
                        try {
                            closeCaptureSession();
                            surfaceTexture.setDefaultBufferSize(1440, 1080);
                            Surface retryPreview = new Surface(surfaceTexture);
                            ensurePhotoImageReader(configuredPhotoSize.getWidth(),
                                    configuredPhotoSize.getHeight());
                            CaptureRequest.Builder retryBuilder =
                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            retryBuilder.addTarget(retryPreview);
                            retryBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            applyFpsToRequest(retryBuilder);
                            applyFlashToRequest(retryBuilder);
                            applyZoom(retryBuilder);
                            previewRequestBuilder = retryBuilder;
                            java.util.ArrayList<Surface> retryOutputs = new java.util.ArrayList<>();
                            retryOutputs.add(retryPreview);
                            retryOutputs.add(imageReader.getSurface());
                            cameraDevice.createCaptureSession(retryOutputs,
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession s) {
                                            captureSession = s;
                                            try {
                                                s.setRepeatingRequest(retryBuilder.build(),
                                                        autoBrightnessCaptureCallback,
                                                        backgroundHandler);
                                                notifyStateChanged(CameraState.PREVIEW_STARTED);
                                                notifyPreviewStarted();
                                            } catch (CameraAccessException | IllegalStateException e) {
                                                logError("Retry photo preview failed", e);
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                                            notifyError("Preview configuration failed");
                                        }
                                    }, backgroundHandler);
                            return;
                        } catch (Exception retryError) {
                            logError("PHOTO session retry failed", retryError);
                        }
                    }
                    notifyError("Preview configuration failed");
                }
            }, backgroundHandler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            logError("Failed to create preview session", e);
            notifyError("Failed to create preview session: " + e.getMessage());
        }
    }

    private boolean isVideoCaptureMode() {
        return currentConfig != null
                && CaptureMode.normalize(currentConfig.getCaptureMode()).isVideo();
    }

    private void ensurePhotoImageReader(int width, int height) {
        if (imageReader != null
                && imageReader.getWidth() == width
                && imageReader.getHeight() == height) {
            Log.d(TAG, "ensurePhotoImageReader reuse " + width + "x" + height);
            return;
        }
        releasePhotoImageReader();
        // Match MTK system camera CaptureSurface: ImageReader.newInstance(pictureW, pictureH, JPEG, n)
        // maxImages=2: still capture does not need a deep queue; large stills are costly.
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        Log.i(TAG, "ensurePhotoImageReader NEW jpeg ImageReader "
                + imageReader.getWidth() + "x" + imageReader.getHeight()
                + " format=JPEG");
    }

    private void ensureVideoEncoderReader(int width, int height) {
        if (videoImageReader != null
                && videoImageReader.getWidth() == width
                && videoImageReader.getHeight() == height) {
            return;
        }
        releaseVideoEncoderReader();
        // PRIVATE + USAGE_VIDEO_ENCODE => GRALLOC_USAGE_HW_VIDEO_ENCODER in HAL.
        videoImageReader = ImageReader.newInstance(
                width,
                height,
                ImageFormat.PRIVATE,
                2,
                HardwareBuffer.USAGE_VIDEO_ENCODE);
        videoImageReader.setOnImageAvailableListener(reader -> {
            try {
                android.media.Image image = reader.acquireLatestImage();
                if (image != null) {
                    image.close();
                }
            } catch (Exception ignored) {
            }
        }, backgroundHandler);
    }

    private void releasePhotoImageReader() {
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
    }

    private void releaseVideoEncoderReader() {
        if (videoImageReader != null) {
            try {
                videoImageReader.close();
            } catch (Exception ignored) {
            }
            videoImageReader = null;
        }
    }

    private void closeCaptureSession() {
        CameraCaptureSession session = captureSession;
        captureSession = null;
        previewRequestBuilder = null;
        if (session == null) return;
        try {
            session.stopRepeating();
            session.abortCaptures();
        } catch (CameraAccessException e) {
            logError("Failed to stop capture session", e);
        } catch (IllegalStateException ignored) {
            // The framework may have already closed the session during lifecycle cleanup.
        } finally {
            session.close();
        }
    }

    private boolean setupMediaRecorder() {
        mediaRecorder = new MediaRecorder();
        boolean slowMotion = isCurrentHighSpeedVideoConfiguration();
        boolean useAudio = !slowMotion && currentConfig.isAudioEnabled()
                && ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (useAudio) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        CamcorderProfile profile = CamcorderProfile.get(Integer.parseInt(currentConfig.getCameraId()), CamcorderProfile.QUALITY_HIGH);
        mediaRecorder.setOutputFormat(profile.fileFormat);
        if (useAudio) {
            mediaRecorder.setAudioEncoder(profile.audioCodec);
        }
        mediaRecorder.setVideoEncoder(profile.videoCodec);

        Resolution resolution = currentConfig.getResolution();
        int captureFps = currentConfig.getFrameRate();
        int playbackFps = slowMotion ? SLOW_MOTION_PLAYBACK_FPS : captureFps;
        mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
        mediaRecorder.setVideoFrameRate(playbackFps);
        if (slowMotion) {
            mediaRecorder.setCaptureRate(captureFps);
        }
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        if (useAudio) {
            mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mediaRecorder.setAudioChannels(profile.audioChannels);
        }
        int orientationHint = getVideoOrientationHint(currentConfig.getCameraId());
        mediaRecorder.setOrientationHint(orientationHint);
        Log.d(TAG, "setupMediaRecorder orientationHint=" + orientationHint);

        // mediaRecorder.setMaxFileSize();
        // mediaRecorder.setMaxDuration(1 * 1000);
        mediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mr, int what, int extra) {
                Log.d(TAG, "onInfo: " + mr + " what=" + what + " extra=" + extra);
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    if (backgroundHandler != null) {
                        backgroundHandler.post(() -> stopRecordingInternal(true));
                    } else {
                        stopRecordingInternal(true);
                    }
                }
            }
        });


        File outputFile = generateCamera2VideoFile();
        recordingOutputFile = outputFile;
        Log.d(TAG, "setupMediaRecorder outputFile=" + outputFile.getAbsolutePath()
                + ", size=" + resolution.getWidth() + "x" + resolution.getHeight()
                + ", captureFps=" + captureFps
                + ", playbackFps=" + playbackFps
                + ", slowMotion=" + slowMotion
                + ", useAudio=" + useAudio);
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
        } catch (IOException | RuntimeException e) {
            logError("MediaRecorder prepare failed: " + outputFile.getAbsolutePath(), e);
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private File generateCamera2VideoFile() {
        File cameraDir = CameraUtils.createCameraDirectory(context);
        if (!cameraDir.exists() && !cameraDir.mkdirs()) {
            logError("Failed to create Camera2 video directory: " + cameraDir.getAbsolutePath(), null);
        }
        return new File(cameraDir, CameraUtils.generateUniqueFileName("mp4").replaceFirst("^CAM_", "CAM2_"));
    }

    private void applyFpsToRequest(CaptureRequest.Builder builder) {
        Range<Integer> fpsRange = getBestFpsRange();
        if (fpsRange == null) return;
        try {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        } catch (IllegalArgumentException e) {
            logError("Unsupported FPS range for current camera: " + fpsRange, e);
        }
    }

    private void applyMtkHfpsMode(CaptureRequest.Builder builder) {
        if (builder == null || currentConfig == null) {
            return;
        }
        int mode = currentConfig.getFrameRate() == 60
                && !isCurrentHighSpeedVideoConfiguration()
                ? MTK_HFPS_MODE_60FPS : 0;
        try {
            // This vendor tag is registered by the MTK HAL but is absent from
            // getAvailableCaptureRequestKeys() on this build. Constructing the
            // registered key directly keeps the 60 fps request from falling
            // back to the public [30, 30] AE range.
            CaptureRequest.Key<int[]> hfpsModeKey =
                    new CaptureRequest.Key<>(MTK_HFPS_MODE_KEY, int[].class);
            builder.set(hfpsModeKey, new int[] { mode });
            if (mode == MTK_HFPS_MODE_60FPS) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<>(60, 60));
            }
            Log.i(TAG, "applyMtkHfpsMode=" + mode
                    + ", requestedFps=" + currentConfig.getFrameRate());
        } catch (IllegalArgumentException e) {
            logError("Failed to configure MTK HFPS mode", e);
        }
    }

    private void applyFlashToRequest(CaptureRequest.Builder builder) {
        if (builder == null) return;
        if (manualExposureSupported && manualExposureEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs);
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    autoBrightnessCompensationIndex);
        }
        builder.set(CaptureRequest.FLASH_MODE,
                isFlashEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    private void loadManualExposureCapabilities(String cameraId) {
        manualExposureSupported = false;
        supportedIsoRange = null;
        supportedExposureTimeRange = null;
        autoBrightnessCompensationRange = null;
        autoBrightnessCompensationIndex = 0;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            autoBrightnessCompensationRange = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!hasManualSensorCapability(capabilities)) return;

            supportedIsoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            supportedExposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            manualExposureSupported = supportedIsoRange != null && supportedExposureTimeRange != null;
            if (manualExposureSupported) {
                manualIso = supportedIsoRange.clamp(manualIso);
                manualExposureTimeNs = supportedExposureTimeRange.clamp(manualExposureTimeNs);
            }
        } catch (CameraAccessException e) {
            logError("Failed to query manual exposure capabilities", e);
        }
    }

    private boolean hasManualSensorCapability(int[] capabilities) {
        if (capabilities == null) return false;
        for (int capability : capabilities) {
            if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                return true;
            }
        }
        return false;
    }

    private void adjustAutoBrightness(TotalCaptureResult result) {
        if (manualExposureEnabled || previewRequestBuilder == null || captureSession == null
                || autoBrightnessCompensationRange == null) {
            return;
        }
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (iso == null || exposureTimeNs == null) return;

        int brighterIndex = autoBrightnessCompensationRange.clamp(1);
        int targetIndex = autoBrightnessCompensationIndex;
        if (autoBrightnessCompensationIndex == 0
                && (iso >= 800 || exposureTimeNs >= 25_000_000L)) {
            targetIndex = brighterIndex;
        } else if (autoBrightnessCompensationIndex != 0
                && iso <= 250 && exposureTimeNs <= 5_000_000L) {
            targetIndex = 0;
        }
        if (targetIndex == autoBrightnessCompensationIndex
                || SystemClock.elapsedRealtime() - lastAutoBrightnessAdjustmentMs < 800) {
            return;
        }

        autoBrightnessCompensationIndex = targetIndex;
        lastAutoBrightnessAdjustmentMs = SystemClock.elapsedRealtime();
        applyFlashToRequest(previewRequestBuilder);
        try {
            submitRepeatingRequest(previewRequestBuilder);
            logDebug("Auto dark-scene compensation=" + targetIndex + ", ISO=" + iso
                    + ", exposureNs=" + exposureTimeNs);
        } catch (CameraAccessException e) {
            logError("Failed to apply auto dark-scene compensation", e);
        }
    }

    @Override
    public boolean isManualExposureSupported() {
        return manualExposureSupported;
    }

    @Override
    public Range<Integer> getSupportedIsoRange() {
        return supportedIsoRange;
    }

    @Override
    public Range<Long> getSupportedExposureTimeRange() {
        return supportedExposureTimeRange;
    }

    @Override
    public int getManualIso() {
        return manualIso;
    }

    @Override
    public long getManualExposureTimeNs() {
        return manualExposureTimeNs;
    }

    @Override
    public boolean isManualExposureEnabled() {
        return manualExposureEnabled;
    }

    @Override
    public void setManualExposure(int iso, long exposureTimeNs) {
        if (!manualExposureSupported) return;
        manualIso = supportedIsoRange.clamp(iso);
        manualExposureTimeNs = supportedExposureTimeRange.clamp(exposureTimeNs);
        manualExposureEnabled = true;
        submitManualExposureChange();
    }

    @Override
    public void resetAutoExposure() {
        if (!manualExposureSupported) return;
        manualIso = supportedIsoRange.clamp(100);
        manualExposureTimeNs = supportedExposureTimeRange.clamp(10_000_000L);
        manualExposureEnabled = false;
        autoBrightnessCompensationIndex = 0;
        submitManualExposureChange();
    }

    private void submitManualExposureChange() {
        Runnable applyChange = () -> {
            if (previewRequestBuilder == null || captureSession == null) return;
            applyFlashToRequest(previewRequestBuilder);
            try {
                submitRepeatingRequest(previewRequestBuilder);
            } catch (CameraAccessException e) {
                logError("Failed to apply manual exposure", e);
            }
        };
        if (backgroundHandler != null) {
            backgroundHandler.post(applyChange);
        } else {
            applyChange.run();
        }
    }

    private void submitRepeatingRequest(CaptureRequest.Builder builder) throws CameraAccessException {
        if (captureSession == null || builder == null) return;
        CaptureRequest request = builder.build();
        if (captureSession instanceof CameraConstrainedHighSpeedCaptureSession) {
            CameraConstrainedHighSpeedCaptureSession highSpeedSession =
                    (CameraConstrainedHighSpeedCaptureSession) captureSession;
            highSpeedSession.setRepeatingBurst(
                    highSpeedSession.createHighSpeedRequestList(request),
                    null,
                    backgroundHandler);
        } else {
            captureSession.setRepeatingRequest(request, autoBrightnessCaptureCallback, backgroundHandler);
        }
    }

    private Range<Integer> getBestFpsRange() {
        if (cameraDevice == null || currentConfig == null) return null;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return null;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;

            int desiredFps = currentConfig.getFrameRate();
            Range<Integer> bestRange = null;
            int bestScore = Integer.MAX_VALUE;

            for (Range<Integer> range : ranges) {
                if (range == null) continue;
                int lower = range.getLower();
                int upper = range.getUpper();
                int clamped = Math.max(lower, Math.min(desiredFps, upper));
                int distance = Math.abs(clamped - desiredFps);
                int span = upper - lower;
                int score = distance * 1000 + span;
                if (score < bestScore) {
                    bestScore = score;
                    bestRange = range;
                }
            }
            return bestRange;
        } catch (CameraAccessException e) {
            logError("Failed to query supported FPS ranges", e);
            return null;
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    @Override
    public boolean switchCamera(String cameraId) {
        zoomRatio = 1f;
        closeCamera();
        currentConfig = new CameraConfig.Builder(currentConfig).setCameraId(cameraId).build();
        openCamera(currentConfig);
        return true;
    }

    @Override
    public boolean toggleFlash() {
        if (cameraDevice == null || !isFlashAvailable()) return false;
        try {
            isFlashEnabled = !isFlashEnabled;

            if (previewRequestBuilder != null && captureSession != null) {
                applyFlashToRequest(previewRequestBuilder);
                submitRepeatingRequest(previewRequestBuilder);
            }

            logDebug("Flash toggled: " + (isFlashEnabled ? "ON" : "OFF"));
            return true;
        } catch (CameraAccessException e) {
            logError("Failed to toggle flash", e);
            isFlashEnabled = !isFlashEnabled;
            return false;
        }
    }

    @Override
    public boolean isFlashAvailable() {
        if (cameraDevice == null) return false;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return flashAvailable != null && flashAvailable;
        } catch (CameraAccessException e) {
            return false;
        }
    }

    @Override
    public boolean isFlashEnabled() {
        return isFlashEnabled;
    }

    @Override
    public boolean isCameraAvailable() {
        return cameraDevice != null;
    }

    @Override
    public List<Resolution> getSupportedResolutions() {
        if (currentConfig == null) {
            return super.getSupportedResolutions();
        }

        List<Resolution> resolutions = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return super.getSupportedResolutions();
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentConfig.getCameraId());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return super.getSupportedResolutions();
            }

            Size[] recorderSizes = map.getOutputSizes(MediaRecorder.class);
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (recorderSizes == null || recorderSizes.length == 0) {
                recorderSizes = jpegSizes;
            }

            Set<String> jpegSizeKeys = toSizeKeys(jpegSizes);
            Set<String> added = new HashSet<>();
            if (recorderSizes != null) {
                for (Size size : recorderSizes) {
                    if (size == null) continue;
                    String key = size.getWidth() + "x" + size.getHeight();
                    if (!jpegSizeKeys.isEmpty() && !jpegSizeKeys.contains(key)) continue;
                    if (added.add(key)) {
                        resolutions.add(Resolution.of(size.getWidth(), size.getHeight()));
                    }
                }
            }

            Collections.sort(resolutions, new Comparator<Resolution>() {
                @Override
                public int compare(Resolution left, Resolution right) {
                    long rightArea = (long) right.getWidth() * right.getHeight();
                    long leftArea = (long) left.getWidth() * left.getHeight();
                    int areaCompare = Long.compare(rightArea, leftArea);
                    if (areaCompare != 0) return areaCompare;
                    return Integer.compare(right.getWidth(), left.getWidth());
                }
            });
        } catch (CameraAccessException | IllegalArgumentException e) {
            logError("Failed to query Camera2 supported resolutions", e);
        }

        return resolutions.isEmpty() ? super.getSupportedResolutions() : resolutions;
    }

    private Set<String> toSizeKeys(Size[] sizes) {
        Set<String> keys = new HashSet<>();
        if (sizes == null) {
            return keys;
        }
        for (Size size : sizes) {
            if (size != null) {
                keys.add(size.getWidth() + "x" + size.getHeight());
            }
        }
        return keys;
    }

    @Override
    public boolean isFocusSupported() {
        if (cameraDevice == null) return false;
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return false;
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            return hasFocuser(characteristics);
        } catch (CameraAccessException e) {
            logError("Failed to check focus support", e);
            return false;
        }
    }

    private boolean hasFocuser(CameraCharacteristics characteristics) {
        if (characteristics == null) {
            return false;
        }
        Float minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocusDistance != null && minFocusDistance > 0) {
            return true;
        }
        int[] availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (availableAfModes == null) {
            return false;
        }
        for (int mode : availableAfModes) {
            if (mode == CameraMetadata.CONTROL_AF_MODE_AUTO ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                mode == CameraMetadata.CONTROL_AF_MODE_MACRO) {
                return true;
            }
        }
        return false;
    }

    
    private Size getPhotoCaptureSize(StreamConfigurationMap map) {
        PhotoResolution photoResolution = currentConfig != null
                ? PhotoResolution.normalize(currentConfig.getPhotoResolution(), currentConfig.getCameraId())
                : PhotoResolution.DEFAULT;
        // System camera (MTK Camera2 PhotoDevice2Controller#setPictureSize) always creates
        // ImageReader with the exact selected picture size. Never downscale to a "closest"
        // recording size such as 1920x1440 — that made HAL maxImageSize ignore still size.
        Size desired = new Size(photoResolution.getWidth(), photoResolution.getHeight());
        boolean advertised = false;
        int candidateCount = 0;
        if (map != null) {
            List<Size> jpegSizes = new ArrayList<>();
            addUniqueSizes(jpegSizes, map.getOutputSizes(ImageFormat.JPEG));
            addUniqueSizes(jpegSizes, map.getHighResolutionOutputSizes(ImageFormat.JPEG));
            candidateCount = jpegSizes.size();
            for (Size size : jpegSizes) {
                if (size != null
                        && size.getWidth() == desired.getWidth()
                        && size.getHeight() == desired.getHeight()) {
                    advertised = true;
                    break;
                }
            }
        }
        Log.i(TAG, "getPhotoCaptureSize useExact=" + desired
                + " advertised=" + advertised
                + " candidates=" + candidateCount
                + " from=" + photoResolution.getDisplayName());
        return desired;
    }

    private void addUniqueSizes(List<Size> out, Size[] sizes) {
        if (sizes == null) {
            return;
        }
        for (Size size : sizes) {
            if (size == null) {
                continue;
            }
            boolean exists = false;
            for (Size mapped : out) {
                if (mapped.getWidth() == size.getWidth() && mapped.getHeight() == size.getHeight()) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                out.add(size);
            }
        }
    }

    /**
     * Still preview should stay moderate so high-res JPEG can win maxImageSize and
     * remain a valid stream combination (system camera style).
     */
    private Size choosePhotoPreviewSize(StreamConfigurationMap map, Size photoSize) {
        Size fallback = new Size(1440, 1080);
        if (map == null) {
            return fallback;
        }
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        if (previewSizes == null || previewSizes.length == 0) {
            return fallback;
        }
        float targetAspect = photoSize.getHeight() > 0
                ? (float) photoSize.getWidth() / (float) photoSize.getHeight()
                : 4f / 3f;
        final int maxLongEdge = 1920;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size size : previewSizes) {
            if (size == null) continue;
            int longEdge = Math.max(size.getWidth(), size.getHeight());
            if (longEdge > maxLongEdge) continue;
            float aspect = size.getHeight() > 0
                    ? (float) size.getWidth() / (float) size.getHeight()
                    : targetAspect;
            long aspectPenalty = (long) (Math.abs(aspect - targetAspect) * 10000);
            long areaPenalty = Math.abs((long) size.getWidth() * size.getHeight() - 1440L * 1080L);
            long score = aspectPenalty * 1000000L + areaPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        if (best == null) {
            // Pick largest preview <= maxLongEdge regardless of aspect.
            for (Size size : previewSizes) {
                if (size == null) continue;
                if (Math.max(size.getWidth(), size.getHeight()) > maxLongEdge) continue;
                if (best == null
                        || (long) size.getWidth() * size.getHeight()
                        > (long) best.getWidth() * best.getHeight()) {
                    best = size;
                }
            }
        }
        Size chosen = best != null ? best : fallback;
        Log.d(TAG, "choosePhotoPreviewSize photo=" + photoSize + " preview=" + chosen);
        return chosen;
    }

    private Size requirePhotoCaptureSize() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentConfig.getCameraId());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return getPhotoCaptureSize(map);
        } catch (Exception e) {
            PhotoResolution photoResolution = PhotoResolution.normalize(
                    currentConfig.getPhotoResolution(), currentConfig.getCameraId());
            Log.w(TAG, "requirePhotoCaptureSize fallback to config " + photoResolution, e);
            return new Size(photoResolution.getWidth(), photoResolution.getHeight());
        }
    }

    private int getJpegOrientation() {
        int degrees = (360 - getDeviceOrientationDegrees()) % 360;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation != null) {
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return (sensorOrientation + degrees) % 360;
                }
                return (sensorOrientation - degrees + 360) % 360;
            }
        } catch (CameraAccessException e) {
            logError("Failed to get sensor orientation", e);
        }
        return 0;
    }

    @Override
    public boolean isZoomSupported() {
        return getMaxZoom() > 1f;
    }

    @Override
    public float getMaxZoom() {
        if (cameraDevice == null) return 1f;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Float maxZoom = manager.getCameraCharacteristics(cameraDevice.getId())
                    .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            return maxZoom == null ? 1f : Math.max(1f, maxZoom);
        } catch (CameraAccessException e) {
            return 1f;
        }
    }

    @Override
    public float getZoom() {
        return zoomRatio;
    }

    @Override
    public void setZoom(float requestedZoom) {
        zoomRatio = Math.max(1f, Math.min(requestedZoom, getMaxZoom()));
        if (previewRequestBuilder == null || captureSession == null) return;
        applyZoom(previewRequestBuilder);
        try {
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to apply zoom", e);
        }
    }

    private void applyZoom(CaptureRequest.Builder builder) {
        if (cameraDevice == null) return;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Rect sensor = manager.getCameraCharacteristics(cameraDevice.getId())
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor == null) return;
            if (zoomRatio <= 1f) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, sensor);
                return;
            }
            int cropWidth = Math.round(sensor.width() / zoomRatio);
            int cropHeight = Math.round(sensor.height() / zoomRatio);
            int left = sensor.centerX() - cropWidth / 2;
            int top = sensor.centerY() - cropHeight / 2;
            builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + cropWidth, top + cropHeight));
        } catch (CameraAccessException e) {
            logError("Failed to calculate zoom crop", e);
        }
    }
}
