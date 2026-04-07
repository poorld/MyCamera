package com.android.mycamera.bgr_yes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
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

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class CameraXHelper extends ICameraXHelper {

    public interface CameraInfoListener {
        void onCameraInfoAvailable(CameraInfo cameraInfo);
    }

    public static final String TAG = "CameraXHelper";
    private Context context;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private VideoCapture<Recorder> videoCapture;
    private Preview previewUseCase;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector activeCameraSelector;
    private Quality appliedQuality;
    private Recording recording;
    private LifecycleOwner lifecycleOwner;
    private Quality quality;
    private int fps;
    private String cameraId = "0";
    private CameraInfoListener cameraInfoListener;

    private IPreViewListener mPreviewListener;

    public CameraXHelper(Context context) {
        this.context = context;
    }

    public void setCameraInfoListener(CameraInfoListener listener) {
        this.cameraInfoListener = listener;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }


    @Override
    public void openCamera(Quality quality, int fps) {
        this.quality = quality;
        this.fps = fps;
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
    }

    @Override
    public void startPreview(TextureView textureView, LifecycleOwner owner) {
        Log.d(TAG, "startPreview: quality " + quality);
        this.lifecycleOwner = owner;
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                this.cameraProvider = cameraProvider;
                // Do not hard-lock preview fps for CameraX recording, otherwise the whole capture
                // session may downgrade record resolution (e.g. UHD -> FHD) to satisfy fps.
                Preview preview = new Preview.Builder().build();
                this.previewUseCase = preview;

                /*final String targetCameraId = "0";
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> {
                            List<CameraInfo> filteredList = new ArrayList<>();
                            for (CameraInfo cameraInfo : cameraInfos) {
                                if (cameraInfo.getCameraId().equals(targetCameraId)) {
                                    Log.d(TAG, "Found target camera with ID: " + targetCameraId);
                                    filteredList.add(cameraInfo);
                                    break;
                                }
                            }
                            if (filteredList.isEmpty()) {
                                Log.e(TAG, "Target camera with ID " + targetCameraId + " not found!");
                            }
                            // 返回包含目标摄像头的列表（如果找到的话）
                            return filteredList;
                        })
                        .build();*/

                List<CameraInfo> availableCameras = cameraProvider.getAvailableCameraInfos();
                if (availableCameras.isEmpty()) {
                    Log.e(TAG, "No cameras available on this device.");
                    return;
                }

                CameraInfo targetCameraInfo = null;
                for (CameraInfo cameraInfo : availableCameras) {
                    if (cameraInfo instanceof CameraInfoInternal) {
                        String availableCameraId = ((CameraInfoInternal) cameraInfo).getCameraId();
                        if (cameraId.equals(availableCameraId)) {
                            targetCameraInfo = cameraInfo;
                            Log.d(TAG, "Found target camera with ID: " + cameraId);
                            break;
                        }
                    }
                }

                if (targetCameraInfo == null) {
                    targetCameraInfo = availableCameras.get(0);
                    Log.w(TAG, "Could not find camera with ID '" + cameraId + "', falling back to the first available camera.");
                }

                CameraInfo finalTargetCameraInfo = targetCameraInfo;
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameraInfos -> Collections.singletonList(finalTargetCameraInfo))
                        .build();
                this.activeCameraSelector = cameraSelector;

                List<Quality> supportedQualities = QualitySelector.getSupportedQualities(finalTargetCameraInfo);
                Quality selectedQuality = quality;
                if (!supportedQualities.contains(selectedQuality)) {
                    for (Quality candidate : Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)) {
                        if (supportedQualities.contains(candidate)) {
                            selectedQuality = candidate;
                            break;
                        }
                    }
                    Log.w(TAG, "Requested quality " + quality + " is not supported for cameraId="
                            + cameraId + ", fallback to " + selectedQuality);
                }

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(selectedQuality))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                appliedQuality = selectedQuality;


                cameraProvider.unbindAll();
                // cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture);

                preview.setSurfaceProvider(request -> {
                    SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
                    if (surfaceTexture == null) {
                        request.willNotProvideSurface();
                        return;
                    }

                    Log.d(TAG, "cameraProviderFuture: cameraInfoListener=" + cameraInfoListener);
                    if (cameraInfoListener != null) {
                        cameraInfoListener.onCameraInfoAvailable(request.getCamera().getCameraInfo());
                    }


                    surfaceTexture.setDefaultBufferSize(request.getResolution().getWidth(), request.getResolution().getHeight());
                    Surface surface = new Surface(surfaceTexture);
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> {
                        surface.release();
                    });

                    mPreviewListener.onPreviewOpen();

                });

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                Toast.makeText(context, "相机错误❌", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @Override
    public void stopPreview() {
        if (cameraProviderFuture != null) {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void startRecord() {
        if (videoCapture == null) return;

        // Some devices cannot keep UHD when preview is bound in the same session.
        if (appliedQuality == Quality.UHD && cameraProvider != null && activeCameraSelector != null) {
            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, activeCameraSelector, videoCapture);
                Log.i(TAG, "startRecord: rebinding to video-only for UHD");
            } catch (Exception e) {
                Log.w(TAG, "startRecord: failed to rebind video-only for UHD", e);
            }
        }

        File outputFile = new File(context.getExternalFilesDir(null), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_X.mp4");
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(outputFile).build();

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        recording = videoCapture.getOutput()
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        // Recording started
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                        if (finalizeEvent.hasError()) {
                            Log.e(TAG, "Recording finalize error: " + finalizeEvent.getError()
                                    + ", cause=" + finalizeEvent.getCause());
                        } else {
                            logRecordedVideoInfo(outputFile);
                        }
                    }
                });
    }

    @Override
    public void stopRecord() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }

        if (appliedQuality == Quality.UHD && cameraProvider != null && activeCameraSelector != null
                && previewUseCase != null && videoCapture != null) {
            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, activeCameraSelector, previewUseCase, videoCapture);
                Log.i(TAG, "stopRecord: restored preview+video binding");
            } catch (Exception e) {
                Log.w(TAG, "stopRecord: failed to restore preview binding", e);
            }
        }
    }

    @Override
    public void closeCamera() {
        if (cameraProviderFuture != null) {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPreviewListener(IPreViewListener previewListener) {
        mPreviewListener = previewListener;
    }

    private void logRecordedVideoInfo(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            Log.i(TAG, "Recorded file: " + file.getAbsolutePath());
            Log.i(TAG, "Recorded meta: " + width + "x" + height
                    + ", durationMs=" + durationMs + ", bitrate=" + bitrate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read recorded file metadata: " + file.getAbsolutePath(), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
