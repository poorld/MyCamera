package com.android.mycamera.bgr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;


public class Camera2Helper {
    public static final String TAG = "Camera2Helper";
    private Context context;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private MediaRecorder mediaRecorder;
    private Size videoSize;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private TextureView textureView;
    private String nextVideoAbsolutePath;

    public Camera2Helper(Context context, TextureView textureView) {
        this.context = context;
        this.textureView = textureView;
    }

    public void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread: ");
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread: ");
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    public void openCamera() {
        Log.d(TAG, "openCamera: ");
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            Log.d(TAG, "onOpened: cameraDevice=" + cameraDevice);
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            Log.d(TAG, "onDisconnected: ");
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            Log.d(TAG, "onError: ");
            cameraDevice = null;
        }
    };

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession: ");
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        previewRequest = previewRequestBuilder.build();
                        captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void startRecording() {
        Log.d(TAG, "startRecording: ");
        if (null == cameraDevice || !textureView.isAvailable() || null == videoSize) {
            Log.d(TAG, "cameraDevice=" + cameraDevice);
            Log.d(TAG, "textureView=" + textureView.isAvailable());
            Log.d(TAG, "videoSize=" + videoSize);
            Log.e(TAG, "startRecording: return");
            return;
        }
        try {
            closePreviewSession();
            setUpMediaRecorder();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = mediaRecorder.getSurface();
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recordSurface);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.addTarget(recordSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured: ");
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                    }
                    catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    mediaRecorder.start();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, backgroundHandler);
        }
        catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        mediaRecorder.stop();
        mediaRecorder.reset();
        createCameraPreviewSession();
    }

    private void setUpMediaRecorder() throws IOException {
        Log.d(TAG, "setUpMediaRecorder: ");
        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (nextVideoAbsolutePath == null || nextVideoAbsolutePath.isEmpty()) {
            nextVideoAbsolutePath = getVideoFilePath(context);
        }
        mediaRecorder.setOutputFile(nextVideoAbsolutePath);
        mediaRecorder.setVideoEncodingBitRate(10000000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.prepare();
    }

    private String getVideoFilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()) + ".mp4";
    }

    private void closePreviewSession() {
        Log.d(TAG, "closePreviewSession: ");
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }

    public void closeCamera() {
        Log.d(TAG, "closeCamera: +");
        closePreviewSession();
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != mediaRecorder) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    public void startPreview() {
        if (cameraDevice != null) {
            createCameraPreviewSession();
        }
    }

    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        return choices[choices.length - 1];
    }
}
