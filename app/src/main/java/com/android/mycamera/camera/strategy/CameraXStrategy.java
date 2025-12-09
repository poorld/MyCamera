package com.android.mycamera.camera.strategy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.util.Log;
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
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.utils.CameraUtils;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CameraXStrategy extends BaseCameraStrategy {
    
    private static final String TAG = "CameraXStrategy";
    
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private VideoCapture<Recorder> videoCapture;
    private ImageCapture imageCapture;
    private Recording recording;
    private LifecycleOwner lifecycleOwner;
    private CameraConfig currentConfig;
    private TextureView cameraPreview;
    private Camera camera;
    // private Executor executor;

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

                List<Quality> supportedQualities = QualitySelector.getSupportedQualities(cameraInfo);
                Quality desiredQuality = convertToCameraXQuality(currentConfig.getQuality());
                Quality finalQuality = supportedQualities.contains(desiredQuality) ? desiredQuality : supportedQualities.get(supportedQualities.size() / 2);

                Preview preview = new Preview.Builder().build();
                Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(finalQuality)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();

                cameraProvider.unbindAll();

                camera = cameraProvider.bindToLifecycle(this.lifecycleOwner, cameraSelector, preview, videoCapture, imageCapture);
                
                preview.setSurfaceProvider(request -> {
                    if (cameraPreview != null) {
                        SurfaceTexture surfaceTexture = cameraPreview.getSurfaceTexture();
                        if (surfaceTexture != null) {
                            request.provideSurface(new Surface(surfaceTexture), ContextCompat.getMainExecutor(context), result -> {
                                // surfaceTexture.release();
                            });
                        }
                    }
                });
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
        if (videoCapture == null) return;
        File outputFile = CameraUtils.generateUniqueMediaFile("mp4");
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(outputFile).build();
        recording = videoCapture.getOutput()
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context), event -> {
                    if (event instanceof VideoRecordEvent.Start) {
                        notifyRecordingStarted();

                    } else if (event instanceof VideoRecordEvent.Finalize) {
                        if (((VideoRecordEvent.Finalize) event).hasError()) {
                            Log.d(TAG, "Recording failed: ");
                            notifyError("Recording failed");
                        } else {
                            notifyPhotoCaptured(outputFile.getAbsolutePath());
                        }
                        notifyRecordingStopped();
                    }
                });
    }

    @Override
    public void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
    }

    @Override
    public void capturePhoto() {
        if (imageCapture == null) return;
        File outputFile = CameraUtils.generateUniqueMediaFile("jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                notifyPhotoCaptured(outputFile.getAbsolutePath());
            }
            @Override
            public void onError(@NonNull ImageCaptureException exc) {
                notifyError("Photo capture failed: " + exc.getMessage());
            }
        });
    }

    @Override
    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
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
}