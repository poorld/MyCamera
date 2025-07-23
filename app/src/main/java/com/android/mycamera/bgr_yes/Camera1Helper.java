package com.android.mycamera.bgr_yes;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.TextureView;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class Camera1Helper implements ICameraHelper {

    public static final String TAG = "BgrYes_Camera1Helper";

    private Context context;
    private Camera camera;
    private MediaRecorder mediaRecorder;
    private TextureView textureView;
    private boolean isRecording = false;
    private String resolution;
    private int frameRate;

    public Camera1Helper(Context context) {
        this.context = context;
    }

    @Override
    public void openCamera(int width, int height, int fps) {
        Log.d(TAG, String.format("openCamera: %dx%d,%d", width, height, fps));
        this.resolution = width + "x" + height;
        this.frameRate = fps;
        try {
            camera = Camera.open(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void startPreview(TextureView textureView, LifecycleOwner lifecycleOwner) {
        this.textureView = textureView;
        if (camera == null || textureView.getSurfaceTexture() == null) return;
        try {
            camera.setPreviewTexture(textureView.getSurfaceTexture());
            camera.startPreview();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stopPreview() {
        if (camera != null && !isRecording) {
            camera.stopPreview();
        }
    }

    @Override
    public void startRecord() {
        if (camera == null || isRecording) return;

        if (!setupMediaRecorder()) {
            return;
        }

        try {
            mediaRecorder.start();
            isRecording = true;
        } catch (IllegalStateException e) {
            releaseMediaRecorder();
        }
    }

    @Override
    public void stopRecord() {
        if (!isRecording || mediaRecorder == null) return;

        try {
            mediaRecorder.stop();
        } catch (RuntimeException e) {
            // Handle stop error
        }
        releaseMediaRecorder();
        isRecording = false;

        if (camera != null) {
            try {
                camera.reconnect();
                camera.setPreviewTexture(textureView.getSurfaceTexture());
                camera.startPreview();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void closeCamera() {
        if (isRecording) {
            stopRecord();
        }
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    private boolean setupMediaRecorder() {
        mediaRecorder = new MediaRecorder();
        try {
            camera.unlock();
        } catch (RuntimeException e) {
            return false;
        }
        mediaRecorder.setCamera(camera);

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        CamcorderProfile profile = CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH);
        mediaRecorder.setOutputFormat(profile.fileFormat);
        mediaRecorder.setAudioEncoder(profile.audioCodec);
        mediaRecorder.setVideoEncoder(profile.videoCodec);

        String[] dimensions = resolution.split("x");
        mediaRecorder.setVideoSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
        mediaRecorder.setVideoFrameRate(frameRate);
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        // mediaRecorder.setAudioSampleRate(profile.audioSampleRate);
        mediaRecorder.setAudioChannels(profile.audioChannels);

        File outputFile = new File(context.getExternalFilesDir(null), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_C1.mp4");
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
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
                try {
                    camera.lock();
                } catch (RuntimeException e) {
                    // Could happen if the camera is already gone
                }
            }
        }
    }
}