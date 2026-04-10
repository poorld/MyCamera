package com.android.mycamera.record;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.mycamera.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public class Camera2Fragment extends Fragment implements IRecordingFragment, SurfaceHolder.Callback {

    private static final String TAG = "Camera2Fragment";

    private SurfaceView surfaceView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private RecordingCallback recordingCallback;
    private boolean isRecording = false;
    private File outputFile;
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

    @Override
    public void setRecordingCallback(RecordingCallback callback) {
        this.recordingCallback = callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_camera2, container, false);
        surfaceView = view.findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        return view;
    }

    @Override
    public void startRecording(String resolution, int frameRate) {
        if (cameraDevice == null || isRecording) return;

        try {
            if (!setupMediaRecorder(this.resolution, this.frameRate)) return;

            SurfaceHolder holder = surfaceView.getHolder();
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(holder.getSurface());
            builder.addTarget(recorderSurface);

            cameraDevice.createCaptureSession(Arrays.asList(holder.getSurface(), recorderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        isRecording = true;
                        if (recordingCallback != null) {
                            recordingCallback.onRecordingStarted();
                        }
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "Recording start failed", e);
                        releaseMediaRecorder();
                        if (recordingCallback != null) {
                            recordingCallback.onRecordingStopped("Recording start failed");
                        }
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    if (recordingCallback != null) {
                        recordingCallback.onRecordingStopped("Recording configure failed");
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Recording session creation failed", e);
            if (recordingCallback != null) {
                recordingCallback.onRecordingStopped("Recording session creation failed");
            }
        }
    }

    @Override
    public void stopRecording() {
        if (!isRecording || mediaRecorder == null) return;

        String message;
        try {
            mediaRecorder.stop();
            message = "Video saved to: " + outputFile.getAbsolutePath();
        } catch (RuntimeException e) {
            Log.e(TAG, "MediaRecorder stop failed", e);
            message = "Recording failed!";
        }
        releaseMediaRecorder();

        isRecording = false;
        if (recordingCallback != null) {
            recordingCallback.onRecordingStopped(message);
        }
    }

    @Override
    public boolean isCurrentlyRecording() {
        return isRecording;
    }


    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void startCamera() {
        startBackgroundThread();
        CameraManager manager = (CameraManager) requireActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private boolean setupMediaRecorder(String resolution, int frameRate) {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        // Use CamcorderProfile for quality settings
        if (CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_HIGH)) {
            CamcorderProfile profile = CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH);
            mediaRecorder.setProfile(profile);
        } else {
            // Fallback to manual settings if high quality profile is not available
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        }

        if (resolution != null) {
            String[] dimensions = resolution.split("x");
            mediaRecorder.setVideoSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
        }
        mediaRecorder.setVideoFrameRate(frameRate); // Allow user to override frame rate
        outputFile = new File(requireActivity().getExternalFilesDir(null), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_C2.mp4");
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private void createPreviewSession() {
        try {
            SurfaceHolder holder = surfaceView.getHolder();
            if (holder == null || cameraDevice == null) return;
            Log.d(TAG, "createPreviewSession: resolution " + resolution);
            if (resolution.contains("x")) {
                String[] dimensions = resolution.split("x");
                // android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
                Context context = getContext();
                if (context != null) {
                    ContextCompat.getMainExecutor(context).execute(() -> holder.setFixedSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1])));
                }
            }


            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range.create(frameRate, frameRate));
            builder.addTarget(holder.getSurface());

            cameraDevice.createCaptureSession(Collections.singletonList(holder.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (!isAdded()) return;
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Preview request failed", e);
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Preview session creation failed", e);
        }
    }

    private void releaseCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        startCamera();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (isRecording) {
            stopRecording();
        }
        releaseCamera();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isRecording) {
            stopRecording();
        }
        releaseCamera();
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
                e.printStackTrace();
            }
        }
    }
}

