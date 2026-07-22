package com.android.mycamera.camera.strategy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.os.Looper;
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
    
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private TextureView textureView;
    private CameraConfig currentConfig;
    private boolean isFlashEnabled = false;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private boolean isStoppingRecording = false;
    private File recordingOutputFile;
    private float zoomRatio = 1f;

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
        Log.d(TAG, "openCamera: ");
        if (cameraDevice != null && currentState != CameraState.ERROR && currentState != CameraState.CLOSED) {
            Log.d(TAG, "openCamera: camera already opened, state=" + currentState);
            notifyStateChanged(currentState);
            return;
        }
        this.currentConfig = config;
        startOrientationUpdates();
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            manager.openCamera(config.getCameraId(), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: ");
                    cameraDevice = camera;
                    notifyStateChanged(CameraState.OPENED);
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onDisconnected: ");
                    camera.close();
                    cameraDevice = null;
                    notifyStateChanged(CameraState.CLOSED);
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.d(TAG, "onError: ");
                    camera.close();
                    cameraDevice = null;
                    notifyError("Camera error: " + error);
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to access camera", e);
        }
    }

    @Override
    public void startPreview(TextureView textureView, Object lifecycleOwner) {
        Log.d(TAG, "startPreview: cameraDevice=" + cameraDevice);
        if (cameraDevice == null) return;
        if (captureSession != null && currentState == CameraState.PREVIEW_STARTED) {
            Log.d(TAG, "startPreview: preview already started");
            return;
        }
        this.textureView = textureView;
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            Resolution resolution = currentConfig.getResolution();
            texture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            imageReader = ImageReader.newInstance(resolution.getWidth(), resolution.getHeight(), android.graphics.ImageFormat.JPEG, 1);
            
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        applyFpsToRequest(previewRequestBuilder);
                        applyFlashToRequest(previewRequestBuilder);
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        notifyStateChanged(CameraState.PREVIEW_STARTED);
                        notifyPreviewStarted();
                    } catch (CameraAccessException e) {
                        logError("Failed to start preview", e);
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    notifyError("Preview configuration failed");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            logError("Failed to create preview session", e);
        }
    }

    @Override
    public void capturePhoto() {
        if (cameraDevice == null || captureSession == null) return;
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
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        stopBackgroundThread();
        notifyStateChanged(CameraState.CLOSED);
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
            applyFlashToRequest(builder);

            if (isCurrentHighSpeedVideoConfiguration()) {
                startConstrainedHighSpeedRecording(previewSurface, recorderSurface, builder);
                return;
            }

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface), new CameraCaptureSession.StateCallback() {
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
            }, backgroundHandler);
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
        stopRecordingInternal(true);
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
                createPreviewSession();
            }
        } finally {
            isStoppingRecording = false;
        }
    }

    private void createPreviewSession() {
        Log.d(TAG, "createPreviewSession: ");
        try {
            closeCaptureSession();
            if (textureView == null || cameraDevice == null) return;
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) return;
            Resolution resolution = currentConfig.getResolution();
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            applyFpsToRequest(builder);
            applyFlashToRequest(builder);

            if (imageReader == null
                    || imageReader.getWidth() != resolution.getWidth()
                    || imageReader.getHeight() != resolution.getHeight()) {
                if (imageReader != null) {
                    imageReader.close();
                }
                imageReader = ImageReader.newInstance(resolution.getWidth(), resolution.getHeight(), android.graphics.ImageFormat.JPEG, 1);
            }

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    previewRequestBuilder = builder;
                    try {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        notifyPreviewStarted();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCaptureSession() {
        if (captureSession == null) return;
        try {
            captureSession.stopRepeating();
            captureSession.abortCaptures();
        } catch (CameraAccessException | IllegalStateException e) {
            logError("Failed to stop capture session", e);
        }
        captureSession.close();
        captureSession = null;
        previewRequestBuilder = null;
    }

    private boolean setupMediaRecorder() {
        mediaRecorder = new MediaRecorder();
        boolean useAudio = currentConfig.isAudioEnabled()
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
        mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
        mediaRecorder.setVideoFrameRate(currentConfig.getFrameRate());
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
                + ", fps=" + currentConfig.getFrameRate()
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

    private void applyFlashToRequest(CaptureRequest.Builder builder) {
        if (builder == null) return;
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE,
                isFlashEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
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
            captureSession.setRepeatingRequest(request, null, backgroundHandler);
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
