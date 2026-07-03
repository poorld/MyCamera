package com.android.mycamera.camera.strategy;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.RecordingStats;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.CameraUtils;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CameraXStrategy extends BaseCameraStrategy {
    
    private static final String TAG = "CameraXStrategy";
    private static final Size UHD_PREVIEW_SIZE = new Size(1280, 720);
    
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private VideoCapture<Recorder> videoCapture;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private Recording recording;
    private LifecycleOwner lifecycleOwner;
    private CameraConfig currentConfig;
    private TextureView cameraPreview;
    private Camera camera;
    private CameraSelector currentCameraSelector;
    private Quality appliedVideoQuality = Quality.FHD;
    private Range<Integer> appliedFpsRange;
    // private Executor executor;

    private static final long MAX_FILE_SIZE = 1024 * 1024 * 500; // 500M
    private static final long SEGMENT_DURATION_MS = 60 * 1000 * 20L; // 20m
    // private static final long SEGMENT_DURATION_MS = 5 * 1000; // 5s
    private static final boolean SPLIT_SEGMENT = false;
    private boolean isUserStopping = false;
    private boolean isSplitting = false;

    private boolean firstStart = true;
    private File outputFile;

    public CameraXStrategy(Context context) {
        super(context);
        // executor = Executors.newSingleThreadExecutor();
    }
    
    @Override
    protected String getTag() {
        return TAG;
    }
    
    @Override
    public void openCamera(CameraConfig config) {
        Log.d(TAG, "openCamera: ");
        this.currentConfig = config;
        startOrientationUpdates();
        this.videoCapture = null;
        this.previewUseCase = null;
        this.imageCapture = null;
        this.recording = null;
        this.currentCameraSelector = null;
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        notifyStateChanged(CameraState.OPENED);
    }

    @Override
    public void startPreview(TextureView textureView, Object lifecycleOwner) {
        Log.d(TAG, "startPreview: ");
        this.cameraPreview = textureView;
        this.lifecycleOwner = (LifecycleOwner) lifecycleOwner;
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = createCameraSelector(cameraProvider, currentConfig.getCameraId());
                CameraInfo cameraInfo = getCameraInfo(cameraProvider, currentConfig.getCameraId());
                if (cameraInfo == null) {
                    notifyError("Could not get CameraInfo");
                    return;
                }
                currentCameraSelector = cameraSelector;

                List<Quality> supportedQualities = QualitySelector.getSupportedQualities(cameraInfo);
                // For CameraX flow, quality is the primary setting shown in UI.
                Quality desiredQuality = convertToCameraXQuality(currentConfig.getQuality());
                if (desiredQuality == null) {
                    desiredQuality = convertResolutionToCameraXQuality(currentConfig.getResolution());
                }
                Quality finalQuality = chooseBestSupportedQuality(desiredQuality, supportedQualities);
                appliedFpsRange = chooseBestFpsRange(currentConfig.getFrameRate(), getSupportedFpsRanges());
                logDebug("CameraX supported qualities: " + supportedQualities);
                logDebug("CameraX supported fps ranges: " + getSupportedFpsRanges());
                logDebug("CameraX config quality=" + currentConfig.getQuality() + ", config resolution="
                        + currentConfig.getResolution().getWidth() + "x" + currentConfig.getResolution().getHeight()
                        + ", targetFps=" + currentConfig.getFrameRate());
                logDebug("CameraX use quality=" + finalQuality + ", targetResolution="
                        + currentConfig.getResolution().getWidth() + "x" + currentConfig.getResolution().getHeight()
                        + ", targetFps=" + currentConfig.getFrameRate());
                logDebug("CameraX applied fps range=" + appliedFpsRange);

                Resolution resolution = currentConfig.getResolution();
                int targetRotation = getCameraXTargetRotation();
                Preview.Builder previewBuilder = new Preview.Builder()
                        .setTargetRotation(targetRotation);
                if (finalQuality == Quality.UHD) {
                    // Keep UHD recording, but lower preview stream to improve stability on constrained devices.
                    previewBuilder.setTargetResolution(UHD_PREVIEW_SIZE);
                }
                previewUseCase = previewBuilder.build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(finalQuality, FallbackStrategy.lowerQualityOrHigherThan(finalQuality)))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                videoCapture.setTargetRotation(targetRotation);
                appliedVideoQuality = finalQuality;
                ImageCapture.Builder imageCaptureBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(targetRotation)
                        .setTargetResolution(new Size(resolution.getWidth(), resolution.getHeight()));
                Camera2Interop.Extender imageCaptureExtender = new Camera2Interop.Extender(imageCaptureBuilder);
                imageCaptureExtender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<>(currentConfig.getFrameRate(), currentConfig.getFrameRate())
                );
                imageCapture = imageCaptureBuilder.build();

                cameraProvider.unbindAll();

                camera = cameraProvider.bindToLifecycle(this.lifecycleOwner, cameraSelector, previewUseCase, videoCapture);
                applyFpsRangeToCamera(camera);
                attachPreviewSurfaceProvider();
                logDebug("CameraX target rotation=" + targetRotation);
                notifyStateChanged(CameraState.PREVIEW_STARTED);
                notifyPreviewStarted();
            } catch (Exception e) {
                logError("Failed to start CameraX preview", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @Override
    public void setFocusPoint(float x, float y) {
        if (camera == null || cameraPreview == null) return;
        MeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(cameraPreview.getWidth(), cameraPreview.getHeight());
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();
        ListenableFuture<androidx.camera.core.FocusMeteringResult> future = camera.getCameraControl().startFocusAndMetering(action);
        future.addListener(() -> {
            // Listener for focus completion, can be used for UI feedback
        }, ContextCompat.getMainExecutor(context));
    }

    @Override
    public boolean isFocusSupported() {
        if (camera == null) return false;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentConfig.getCameraId());
            return hasFocuser(characteristics);
        } catch (Exception e) {
            logError("Failed to get camera characteristics for focus check", e);
            return false;
        }
    }

    private boolean hasFocuser(CameraCharacteristics characteristics) {
        if (characteristics == null) return false;
        Float minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocusDistance != null && minFocusDistance > 0) {
            return true;
        }
        int[] availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (availableAfModes == null) return false;
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

    @Override
    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");


        if (cameraProviderFuture != null && cameraProviderFuture.isDone()) {
            try {
                ProcessCameraProvider processCameraProvider = cameraProviderFuture.get();
                processCameraProvider.unbindAll();
            } catch (Exception e) {
                logError("Failed to stop preview", e);
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void startRecording() {
        Log.d(TAG, "startRecording: videoCapture=" + videoCapture);
        if (videoCapture == null || previewUseCase == null || currentCameraSelector == null || lifecycleOwner == null) return;
        if (recording != null) return;
        cameraProviderFuture.addListener(() -> {
            try {
                videoCapture.setTargetRotation(getCameraXTargetRotation());
                isUserStopping = false;
                firstStart = true;

                outputFile = generateCameraXVideoFile();
                FileOutputOptions outputOptions = new FileOutputOptions.Builder(outputFile).build();
                PendingRecording pendingRecording = videoCapture.getOutput()
                        .prepareRecording(context, outputOptions);
                if (currentConfig.isAudioEnabled()
                        && ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    pendingRecording = pendingRecording.withAudioEnabled();
                }
                recording = pendingRecording.start(ContextCompat.getMainExecutor(context), this::checkEvent);
            } catch (Exception e) {
                logError("Failed to start CameraX recording", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void checkEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            if (firstStart) {
                notifyRecordingStarted();
                firstStart = false;
            }
        }

        // 20251209 add splitSegment
        if (event instanceof VideoRecordEvent.Status) {
            RecordingStats stats = event.getRecordingStats();

            long size = stats.getNumBytesRecorded();
            long duration = stats.getRecordedDurationNanos() / 1_000_000;

            // 条件 1：大小分段
            // if (!isUserStopping && size >= MAX_FILE_SIZE) {
            //     Log.d("Segment", String.format("Size reached %d, splitting...", size));
            //     splitSegment();
            //     return;
            // }

            // 条件 2：时间分段
            if (!isUserStopping && SEGMENT_DURATION_MS > 0 &&
                    duration >= SEGMENT_DURATION_MS) {
                Log.d(TAG, "Time reached, splitting...");
                splitSegment();
            }
        }

        if (event instanceof VideoRecordEvent.Finalize) {
            if (((VideoRecordEvent.Finalize) event).hasError()) {
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
                Log.d(TAG, "Recording failed: " + finalizeEvent.getError() + ", " + finalizeEvent.getCause());
                recording = null;
                firstStart = true;
                notifyError("Recording failed: " + finalizeEvent.getError());
            } else {
                recording = null;
                firstStart = true;
                logRecordedVideoInfo(outputFile);
                if (isSplitting) {
                    Log.d(TAG, "Segment finalized, starting next segment...");
                    startNextSegment();
                } else {
                    notifyRecordingStopped();
                    notifyPhotoCaptured(outputFile.getAbsolutePath());
                }
            }
        }
    }


    private void startNextSegment() {
        if (SPLIT_SEGMENT) {
            isSplitting = false;
            startRecording();
        }
    }

    private void splitSegment() {
        if (SPLIT_SEGMENT) {
            if (recording == null) return;

            isSplitting = true;
            recording.stop();
        }

    }

    @Override
    public void stopRecording() {
        if (recording != null) {
            isUserStopping = true;  // ⬅️ 用户主动停止
            isSplitting = false;    // 不再分段
            recording.stop();
            recording = null;

            firstStart = true;
        }
    }

    private void restorePhotoUseCases() {
        if (cameraProviderFuture == null || !cameraProviderFuture.isDone()
                || lifecycleOwner == null || currentCameraSelector == null
                || previewUseCase == null || videoCapture == null) {
            return;
        }
        try {
            ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, currentCameraSelector, previewUseCase, videoCapture);
            applyFpsRangeToCamera(camera);
            attachPreviewSurfaceProvider();
        } catch (Exception e) {
            logError("Failed to restore CameraX photo use cases", e);
        }
    }

    @Override
    public void capturePhoto() {
        if (recording != null || cameraProviderFuture == null || lifecycleOwner == null || currentCameraSelector == null) return;
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Resolution resolution = currentConfig.getResolution();

                ImageCapture.Builder dedicatedBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(getCameraXTargetRotation())
                        .setTargetResolution(new Size(resolution.getWidth(), resolution.getHeight()));
                Camera2Interop.Extender dedicatedExtender = new Camera2Interop.Extender(dedicatedBuilder);
                dedicatedExtender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<>(currentConfig.getFrameRate(), currentConfig.getFrameRate())
                );
                ImageCapture dedicatedCapture = dedicatedBuilder.build();

                // Temporarily bind only ImageCapture to avoid stream-combination downgrades.
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, currentCameraSelector, dedicatedCapture);

                File outputFile = CameraUtils.generateUniqueMediaFile("jpg");
                ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
                dedicatedCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(outputFile.getAbsolutePath(), options);
                        logDebug("CameraX actual photo size: " + options.outWidth + "x" + options.outHeight);
                        restorePhotoUseCases();
                        notifyPhotoCaptured(outputFile.getAbsolutePath());
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        restorePhotoUseCases();
                        notifyError("Photo capture failed: " + exc.getMessage());
                    }
                });
            } catch (Exception e) {
                logError("Failed to capture CameraX photo", e);
                restorePhotoUseCases();
                notifyError("Photo capture failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @Override
    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        stopOrientationUpdates();
        if (camera != null) {
            camera.getCameraControl().cancelFocusAndMetering();
        }

        if (cameraProviderFuture != null && cameraProviderFuture.isDone()) {
            try {
                cameraProviderFuture.get().unbindAll();
            } catch (Exception e) {
                logError("Failed to close camera", e);
            }
        }
        notifyStateChanged(CameraState.CLOSED);
    }

    @Override
    public boolean isCameraAvailable() {
        return true;
    }

    @Override
    public boolean switchCamera(String cameraId) {
        Log.d(TAG, "switchCamera: " + cameraId);
        currentConfig = new CameraConfig.Builder(currentConfig).setCameraId(cameraId).build();
        if (lifecycleOwner != null && cameraPreview != null) {
            startPreview(cameraPreview, lifecycleOwner);
        }
        return true;
    }

    @Override
    public boolean toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(!isFlashEnabled());
            return true;
        }
        return false;
    }

    @Override
    public boolean isFlashAvailable() {
        return camera != null && camera.getCameraInfo().hasFlashUnit();
    }

    @Override
    public boolean isFlashEnabled() {
        return camera != null && camera.getCameraInfo().getTorchState().getValue() == androidx.camera.core.TorchState.ON;
    }

    private CameraSelector createCameraSelector(ProcessCameraProvider cameraProvider, String cameraId) {
        return new CameraSelector.Builder().addCameraFilter(cameraInfos -> {
            List<CameraInfo> filteredList = new ArrayList<>();
            for (CameraInfo info : cameraInfos) {
                if (((CameraInfoInternal) info).getCameraId().equals(cameraId)) {
                    filteredList.add(info);
                }
            }
            return filteredList;
        }).build();
    }

    private CameraInfo getCameraInfo(ProcessCameraProvider cameraProvider, String cameraId) {
        for (CameraInfo cameraInfo : cameraProvider.getAvailableCameraInfos()) {
            if (((CameraInfoInternal) cameraInfo).getCameraId().equals(cameraId)) {
                return cameraInfo;
            }
        }
        return null;
    }

    private Quality convertToCameraXQuality(com.android.mycamera.model.Quality quality) {
        switch (quality) {
            case HD: return Quality.HD;
            case FULL_HD: return Quality.FHD;
            case UHD: return Quality.UHD;
            case SD: return Quality.SD;
            case LOWEST: return Quality.LOWEST;
            case HIGHEST: return Quality.HIGHEST;
            default: return Quality.FHD;
        }
    }

    private Quality convertResolutionToCameraXQuality(Resolution resolution) {
        if (resolution == null) return null;
        switch (resolution) {
            case UHD_4K:
                return Quality.UHD;
            case FULL_HD_1080P:
                return Quality.FHD;
            case HD_720P:
                return Quality.HD;
            case VGA_640x480:
            default:
                return Quality.SD;
        }
    }

    private Quality chooseBestSupportedQuality(Quality desiredQuality, List<Quality> supportedQualities) {
        if (supportedQualities == null || supportedQualities.isEmpty()) {
            return desiredQuality != null ? desiredQuality : Quality.FHD;
        }
        if (desiredQuality != null && supportedQualities.contains(desiredQuality)) {
            return desiredQuality;
        }
        List<Quality> preference = new ArrayList<>();
        preference.add(Quality.UHD);
        preference.add(Quality.FHD);
        preference.add(Quality.HD);
        preference.add(Quality.SD);
        preference.add(Quality.LOWEST);

        int desiredIndex = preference.indexOf(desiredQuality);
        if (desiredIndex < 0) {
            desiredIndex = preference.indexOf(Quality.FHD);
        }

        for (int i = desiredIndex; i < preference.size(); i++) {
            Quality candidate = preference.get(i);
            if (supportedQualities.contains(candidate)) {
                return candidate;
            }
        }
        for (int i = desiredIndex - 1; i >= 0; i--) {
            Quality candidate = preference.get(i);
            if (supportedQualities.contains(candidate)) {
                return candidate;
            }
        }

        return supportedQualities.get(0);
    }

    @Override
    public List<Integer> getSupportedFrameRates() {
        List<Integer> candidates = super.getSupportedFrameRates();
        if (currentConfig == null) {
            return candidates;
        }

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentConfig.getCameraId());
            Range<Integer>[] aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (aeRanges == null || aeRanges.length == 0 || map == null) {
                return candidates;
            }

            Size targetSize = resolveTargetVideoSize(map);
            long minFrameDurationNs = map.getOutputMinFrameDuration(MediaRecorder.class, targetSize);
            int maxByDuration = minFrameDurationNs > 0 ? (int) (1_000_000_000L / minFrameDurationNs) : Integer.MAX_VALUE;

            int maxByAe = 0;
            for (Range<Integer> range : aeRanges) {
                maxByAe = Math.max(maxByAe, range.getUpper());
            }
            int maxFps = Math.min(maxByDuration, maxByAe);

            List<Integer> supported = new ArrayList<>();
            for (Integer fps : candidates) {
                if (fps == null || fps > maxFps) continue;
                for (Range<Integer> range : aeRanges) {
                    if (fps >= range.getLower() && fps <= range.getUpper()) {
                        supported.add(fps);
                        break;
                    }
                }
            }

            if (!supported.isEmpty()) {
                logDebug("Supported fps for " + targetSize.getWidth() + "x" + targetSize.getHeight()
                        + " on cameraId=" + currentConfig.getCameraId() + ": " + supported);
                return supported;
            }
        } catch (Exception e) {
            logError("Failed to query CameraX supported frame rates", e);
        }

        return candidates;
    }

    private void logRecordedVideoInfo(File file) {
        if (file == null) {
            logError("Recorded file is null", null);
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            logDebug("Recorded file: " + file.getAbsolutePath());
            logDebug("Recorded meta: " + width + "x" + height
                    + ", durationMs=" + durationMs + ", bitrate=" + bitrate);
        } catch (Exception e) {
            logError("Failed to read recorded metadata: " + file.getAbsolutePath(), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Size resolveTargetVideoSize(StreamConfigurationMap map) {
        Size[] outputSizes = map.getOutputSizes(MediaRecorder.class);
        if (outputSizes == null || outputSizes.length == 0) {
            return new Size(1920, 1080);
        }

        int desiredWidth = currentConfig.getQuality().getWidth();
        int desiredHeight = currentConfig.getQuality().getHeight();
        long desiredArea = (long) desiredWidth * desiredHeight;

        Size best = outputSizes[0];
        long bestDelta = Math.abs((long) best.getWidth() * best.getHeight() - desiredArea);
        for (Size size : outputSizes) {
            long area = (long) size.getWidth() * size.getHeight();
            long delta = Math.abs(area - desiredArea);
            if (delta < bestDelta) {
                best = size;
                bestDelta = delta;
            }
        }
        return best;
    }

    private File generateCameraXVideoFile() {
        File cameraDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (cameraDir == null) {
            cameraDir = context.getFilesDir();
        }
        if (!cameraDir.exists()) {
            cameraDir.mkdirs();
        }
        return new File(cameraDir, CameraUtils.generateUniqueFileName("mp4").replaceFirst("^CAM_", "CAMX_"));
    }

    private void attachPreviewSurfaceProvider() {
        if (previewUseCase == null) return;
        previewUseCase.setSurfaceProvider(request -> {
            if (cameraPreview == null) {
                request.willNotProvideSurface();
                return;
            }
            SurfaceTexture surfaceTexture = cameraPreview.getSurfaceTexture();
            if (surfaceTexture == null) {
                request.willNotProvideSurface();
                return;
            }
            surfaceTexture.setDefaultBufferSize(request.getResolution().getWidth(), request.getResolution().getHeight());
            Surface surface = new Surface(surfaceTexture);
            request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> surface.release());
        });
    }

    private void applyFpsRangeToCamera(Camera activeCamera) {
        if (activeCamera == null || appliedFpsRange == null) return;
        try {
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, appliedFpsRange)
                    .build();
            Camera2CameraControl.from(activeCamera.getCameraControl()).setCaptureRequestOptions(options);
            logDebug("Applied AE FPS range: " + appliedFpsRange);
        } catch (Exception e) {
            logError("Failed to apply AE FPS range: " + appliedFpsRange, e);
        }
    }

    private List<Range<Integer>> getSupportedFpsRanges() {
        List<Range<Integer>> ranges = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentConfig.getCameraId());
            Range<Integer>[] available = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (available != null) {
                for (Range<Integer> range : available) {
                    ranges.add(range);
                }
            }
        } catch (Exception e) {
            logError("Failed to query supported FPS ranges", e);
        }
        return ranges;
    }

    private Range<Integer> chooseBestFpsRange(int targetFps, List<Range<Integer>> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return new Range<>(targetFps, targetFps);
        }

        Range<Integer> exact = null;
        Range<Integer> upperMatch = null;
        Range<Integer> bestUnderOrEqual = null;
        Range<Integer> highestUpper = ranges.get(0);

        for (Range<Integer> range : ranges) {
            int lower = range.getLower();
            int upper = range.getUpper();
            if (lower == targetFps && upper == targetFps) {
                exact = range;
                break;
            }
            if (upper == targetFps) {
                if (upperMatch == null || lower > upperMatch.getLower()) {
                    upperMatch = range;
                }
            }
            if (upper <= targetFps) {
                if (bestUnderOrEqual == null || upper > bestUnderOrEqual.getUpper()
                        || (upper == bestUnderOrEqual.getUpper() && lower > bestUnderOrEqual.getLower())) {
                    bestUnderOrEqual = range;
                }
            }
            if (upper > highestUpper.getUpper()
                    || (upper == highestUpper.getUpper() && lower > highestUpper.getLower())) {
                highestUpper = range;
            }
        }

        if (exact != null) return exact;
        if (upperMatch != null) return upperMatch;
        if (bestUnderOrEqual != null) return bestUnderOrEqual;
        return highestUpper;
    }

}
