package com.android.mycamera.bgr_yes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public class Camera2Helper implements ICameraHelper {
    public static final String TAG = "BgrYes_Camera2Helper";
    private Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private TextureView textureView;
    private boolean isRecording = false;
    private String resolution;
    private int frameRate;

    public Camera2Helper(Context context) {
        this.context = context;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void openCamera(int width, int height, int fps) {
        Log.d(TAG, String.format("openCamera: %dx%d,%d", width, height, fps));
        this.resolution = width + "x" + height;
        this.frameRate = fps;
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            manager.openCamera("0", new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: ");
                    cameraDevice = camera;
                    if (textureView != null) {
                        createPreviewSession();
                    }
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onDisconnected: ");
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.d(TAG, "onError: ");
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void startPreview(TextureView textureView, LifecycleOwner lifecycleOwner) {
        Log.d(TAG, "startPreview: ");
        this.textureView = textureView;
        if (cameraDevice != null) {
            createPreviewSession();
        }
    }

    @Override
    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        closePreviewSession();
    }

    private void closePreviewSession() {
        Log.d(TAG, "closePreviewSession: ");
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }

    @Override
    public void startRecord() {
        Log.d(TAG, "startRecord: resolution=" + resolution);
        if (cameraDevice == null || isRecording) return;

        try {
            if (!setupMediaRecorder()) return;

            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) return;
            String[] dimensions = resolution.split("x");
            surfaceTexture.setDefaultBufferSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        isRecording = true;
                    } catch (CameraAccessException | IllegalStateException e) {
                        releaseMediaRecorder();
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

    @Override
    public void stopRecord() {
        Log.d(TAG, "stopRecord: ");
        if (!isRecording || mediaRecorder == null) return;

        try {
            captureSession.stopRepeating();
            captureSession.abortCaptures();
            mediaRecorder.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        releaseMediaRecorder();
        isRecording = false;
        createPreviewSession();
    }

    @Override
    public void closeCamera() {
        if (isRecording) {
            stopRecord();
        }
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

    private boolean setupMediaRecorder() {
        Log.d(TAG, "setupMediaRecorder: ");
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        CamcorderProfile profile = CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH);
        mediaRecorder.setOutputFormat(profile.fileFormat);
        mediaRecorder.setAudioEncoder(profile.audioCodec);
        mediaRecorder.setVideoEncoder(profile.videoCodec);

        // Always respect user's choice
        String[] dimensions = resolution.split("x");
        mediaRecorder.setVideoSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
        mediaRecorder.setVideoFrameRate(frameRate);
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        // mediaRecorder.setAudioSampleRate(profile.audioSampleRate);
        mediaRecorder.setAudioChannels(profile.audioChannels);

        File outputFile = new File(context.getExternalFilesDir(null), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_C2.mp4");
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private void createPreviewSession() {
        Log.d(TAG, "createPreviewSession: ");
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null || cameraDevice == null) return;
            String[] dimensions = resolution.split("x");
            surfaceTexture.setDefaultBufferSize(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
            Surface previewSurface = new Surface(surfaceTexture);

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured: cameraDevice=" + cameraDevice);
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "onConfigureFailed: " + session);
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void releaseMediaRecorder() {
        Log.d(TAG, "releaseMediaRecorder: ");
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread: ");
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread: ");
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
