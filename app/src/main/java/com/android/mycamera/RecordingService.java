package com.android.mycamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Size;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingService extends LifecycleService {

    private final IBinder binder = new LocalBinder();
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService cameraExecutor;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Preview preview;

    public class LocalBinder extends Binder {
        RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cameraExecutor = Executors.newSingleThreadExecutor();
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        startForeground(1, createNotification());
        return START_STICKY;
    }

    private Notification createNotification() {
        String channelId = "recording_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Recording Service", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Recording Service")
                .setContentText("Recording video in the background.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    public void startPreview(Preview.SurfaceProvider surfaceProvider) {
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(surfaceProvider);
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    public void startRecording(Size resolution, int frameRate, RecordingCallback callback) {
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                cameraProvider.unbind(preview);
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);

                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
                File outputFile = new File(getExternalFilesDir(null), "video_" + timeStamp + ".mp4");

                activeRecording = videoCapture.getOutput()
                        .prepareRecording(this, new MediaStoreOutputOptions.Builder(getContentResolver(),
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                                .setContentValues(new android.content.ContentValues())
                                .build())
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(this), event -> {
                            if (event instanceof VideoRecordEvent.Finalize) {
                                if (((VideoRecordEvent.Finalize) event).hasError()) {
                                    callback.onRecordingFinished(null);
                                } else {
                                    callback.onRecordingFinished(outputFile.getAbsolutePath());
                                }
                            }
                        });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    public void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    public interface RecordingCallback {
        void onRecordingFinished(String filePath);
    }
}
