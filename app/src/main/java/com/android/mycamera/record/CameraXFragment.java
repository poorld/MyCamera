package com.android.mycamera.record;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.mycamera.R;
import com.android.mycamera.utils.CameraUtils;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class CameraXFragment extends Fragment implements IRecordingFragment {

    public interface CameraInfoListener {
        void onCameraInfoAvailable(CameraInfo cameraInfo);
    }

    private static final String TAG = "CameraXFragment";

    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private RecordingCallback recordingCallback;
    private CameraInfoListener cameraInfoListener;
    private boolean isRecording = false;

    @Override
    public void setRecordingCallback(RecordingCallback callback) {
        this.recordingCallback = callback;
    }

    public void setCameraInfoListener(CameraInfoListener listener) {
        this.cameraInfoListener = listener;
    }

    private String resolution;
    private int frameRate;
    private String cameraId = "0";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            resolution = getArguments().getString("resolution");
            frameRate = getArguments().getInt("fps");
            cameraId = getArguments().getString("cameraId", "0");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_camerax, container, false);
        previewView = view.findViewById(R.id.cameraXPreviewView);
        startCamera();
        return view;
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder()
                        .setTargetFrameRate(new Range<>(frameRate, frameRate))
                        .build();


                Quality quality = null;

                try {
                    VideoRecordActivity activity = (VideoRecordActivity) requireActivity();
                    if (activity.getQualityMap() != null) {
                        quality = activity.getQualityMap().get(resolution);
                    }

                } catch (IllegalStateException e) {
                }

                if (quality == null) {
                    quality = Quality.SD;
                }

                Log.d(TAG, "startCamera: quality " + quality);

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(quality))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);


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

                cameraProvider.unbindAll();
                // Camera camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                if (cameraInfoListener != null) {
                    cameraInfoListener.onCameraInfoAvailable(camera.getCameraInfo());
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX start failed", e);
            } catch (IllegalArgumentException e) {
                Toast.makeText(getContext(), "相机错误❌", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    @Override
    public void startRecording(String resolution, int frameRate) {
        if (videoCapture == null) return;

        File outputFile = CameraUtils.generateUniqueMediaFile(requireContext(), "mp4");
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(outputFile).build();

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        recording = videoCapture.getOutput()
                .prepareRecording(requireContext(), outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(requireContext()), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        isRecording = true;
                        if (recordingCallback != null) {
                            recordingCallback.onRecordingStarted();
                        }
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        isRecording = false;
                        String message;
                        if (!((VideoRecordEvent.Finalize) videoRecordEvent).hasError()) {
                            message = "Video capture succeeded: " + outputFile.getAbsolutePath();
                        } else {
                            message = "Video capture ends with error: " + ((VideoRecordEvent.Finalize) videoRecordEvent).getError();
                        }
                        if (recordingCallback != null) {
                            recordingCallback.onRecordingStopped(message);
                        }
                    }
                });
    }

    @Override
    public void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        isRecording = false;
    }

    @Override
    public boolean isCurrentlyRecording() {
        return isRecording;
    }
}

