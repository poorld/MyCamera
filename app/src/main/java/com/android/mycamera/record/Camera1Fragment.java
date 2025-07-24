package com.android.mycamera.record;

import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.mycamera.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class Camera1Fragment extends Fragment implements IRecordingFragment, SurfaceHolder.Callback {

    private static final String TAG = "Camera1Fragment";

    private SurfaceView surfaceView;
    private Camera camera;
    private MediaRecorder mediaRecorder;
    private RecordingCallback recordingCallback;
    private boolean isRecording = false;
    private File outputFile;
    private String resolution;
    private int frameRate;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            resolution = getArguments().getString("resolution");
            frameRate = getArguments().getInt("fps");
        }
    }

    @Override
    public void setRecordingCallback(RecordingCallback callback) {
        this.recordingCallback = callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_camera1, container, false);
        surfaceView = view.findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        return view;
    }

    @Override
    public void startRecording(String resolution, int frameRate) {
        if (camera == null || isRecording) return;

        if (!setupMediaRecorder(this.resolution, this.frameRate)) {
            if (recordingCallback != null) {
                recordingCallback.onRecordingStopped("Failed to setup MediaRecorder");
            }
            return;
        }

        try {
            mediaRecorder.start();
            isRecording = true;
            if (recordingCallback != null) {
                recordingCallback.onRecordingStarted();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder start failed", e);
            releaseMediaRecorder();
            if (recordingCallback != null) {
                recordingCallback.onRecordingStopped("Recording start failed");
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

        try {
            camera.reconnect();
            camera.startPreview();
        } catch (IOException e) {
            Log.e(TAG, "Camera reconnect failed", e);
        }

        isRecording = false;
        if (recordingCallback != null) {
            recordingCallback.onRecordingStopped(message);
        }
    }

    @Override
    public boolean isCurrentlyRecording() {
        return isRecording;
    }

    private boolean setupMediaRecorder(String resolution, int frameRate) {
        mediaRecorder = new MediaRecorder();
        camera.unlock();
        mediaRecorder.setCamera(camera);

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        if (CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_HIGH)) {
            CamcorderProfile profile = CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH);
            mediaRecorder.setProfile(profile);
        } else {
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        }

        if (resolution != null) {
            String[] dimensions = resolution.split("x");
            mediaRecorder.setVideoSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
        }
        mediaRecorder.setVideoFrameRate(frameRate);
        outputFile = new File(requireActivity().getExternalFilesDir(null), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_C1.mp4");
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

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
            if (camera != null) {
                camera.lock();
            }
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            camera = Camera.open(0);
            Camera.Parameters params = camera.getParameters();
            if (resolution != null) {
                String[] dimensions = resolution.split("x");
                params.setPreviewSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
            }
            params.setPreviewFrameRate(frameRate);
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException e) {
            Log.e(TAG, "Surface creation or camera preview failed", e);
        } catch (RuntimeException e) {
            Toast.makeText(getContext(), "相机不可用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed: ");
        if (isRecording) {
            stopRecording();
        }
        releaseCamera();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause: ");
        super.onPause();
        if (isRecording) {
            stopRecording();
        }
        releaseCamera();
    }
}

