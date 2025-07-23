package com.android.mycamera.bgr_yes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
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
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class CameraXHelper implements ICameraHelper {

    private Context context;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private LifecycleOwner lifecycleOwner;

    public CameraXHelper(Context context) {
        this.context = context;
    }

    @Override
    public void openCamera(int width, int height, int fps) {
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
    }

    @Override
    public void startPreview(TextureView textureView, LifecycleOwner owner) {
        this.lifecycleOwner = owner;
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);

                preview.setSurfaceProvider(request -> {
                    SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
                    if (surfaceTexture == null) {
                        request.willNotProvideSurface();
                        return;
                    }
                    surfaceTexture.setDefaultBufferSize(request.getResolution().getWidth(), request.getResolution().getHeight());
                    Surface surface = new Surface(surfaceTexture);
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> {
                        // DO NOT RELEASE THE SURFACE TEXTURE HERE!
                        // It is owned by the TextureView.
                        surface.release();
                    });
                });

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
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
                        // Recording finished
                    }
                });
    }

    @Override
    public void stopRecord() {
        if (recording != null) {
            recording.stop();
            recording = null;
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
}
