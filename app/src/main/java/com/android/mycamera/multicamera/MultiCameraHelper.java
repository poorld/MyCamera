package com.android.mycamera.multicamera;

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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiresApi(api = Build.VERSION_CODES.P)
public class MultiCameraHelper {
    private static final String TAG = "MultiCameraHelper";
    
    private Context context;
    private CameraManager cameraManager;
    private MultiCameraManager multiCameraManager;
    
    private Map<String, CameraDevice> cameraDevices = new ConcurrentHashMap<>();
    private Map<String, CameraCaptureSession> captureSessions = new ConcurrentHashMap<>();
    private Map<String, CaptureRequest.Builder> previewRequestBuilders = new ConcurrentHashMap<>();
    private Map<String, MediaRecorder> mediaRecorders = new ConcurrentHashMap<>();
    
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    
    private Map<String, TextureView> textureViews = new ConcurrentHashMap<>();
    private Map<String, Size> videoSizes = new ConcurrentHashMap<>();
    private Map<String, String> videoPaths = new ConcurrentHashMap<>();
    
    private boolean isRecording = false;

    public MultiCameraHelper(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.multiCameraManager = new MultiCameraManager(context);
    }
    
    public void startBackgroundThread() {
        backgroundThread = new HandlerThread("MultiCameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    
    public void stopBackgroundThread() {
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
    
    public boolean isMultiCameraSupported() {
        return multiCameraManager.isMultiCameraSupported();
    }
    
    public List<MultiCameraManager.LogicalCameraInfo> getAvailableLogicalCameras() {
        return multiCameraManager.getLogicalCameras();
    }
    
    public void addTextureView(String physicalCameraId, TextureView textureView) {
        textureViews.put(physicalCameraId, textureView);
    }
    
    @SuppressLint("MissingPermission")
    public void openSingleCamera(String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            videoSizes.put(cameraId, videoSize);
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevices.put(cameraId, camera);
                    Log.d(TAG, "Single camera opened: " + cameraId);
                    createCameraPreviewSession(cameraId);
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevices.remove(cameraId);
                    Log.d(TAG, "Single camera disconnected: " + cameraId);
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevices.remove(cameraId);
                    Log.e(TAG, "Single camera error: " + cameraId + ", error: " + error);
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening single camera: " + cameraId, e);
        }
    }

    private void createCameraPreviewSession(String physicalCameraId) {
        CameraDevice cameraDevice = cameraDevices.get(physicalCameraId);
        TextureView textureView = textureViews.get(physicalCameraId);
        Size videoSize = videoSizes.get(physicalCameraId);
        
        if (cameraDevice == null || textureView == null || videoSize == null) {
            Log.e(TAG, "Cannot create preview session for camera: " + physicalCameraId);
            return;
        }
        
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                Log.e(TAG, "SurfaceTexture is null for camera: " + physicalCameraId);
                return;
            }
            
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            
            CaptureRequest.Builder previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            
            // 设置物理相机特定的参数
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                previewBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, (long) (1000000000.0 / 30.0));
            }
            
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSessions.put(physicalCameraId, session);
                    previewRequestBuilders.put(physicalCameraId, previewBuilder);
                    
                    try {
                        session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                        Log.d(TAG, "Preview session created for camera: " + physicalCameraId);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error starting preview for camera: " + physicalCameraId, e);
                    }
                }
                
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Preview session configuration failed for camera: " + physicalCameraId);
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session for camera: " + physicalCameraId, e);
        }
    }
    
    public void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }
        
        isRecording = true;
        
        for (String physicalCameraId : cameraDevices.keySet()) {
            startRecordingForCamera(physicalCameraId);
        }
    }
    
    private void startRecordingForCamera(String physicalCameraId) {
        CameraDevice cameraDevice = cameraDevices.get(physicalCameraId);
        TextureView textureView = textureViews.get(physicalCameraId);
        Size videoSize = videoSizes.get(physicalCameraId);
        
        if (cameraDevice == null || textureView == null || videoSize == null) {
            Log.e(TAG, "Cannot start recording for camera: " + physicalCameraId);
            return;
        }
        
        try {
            // 关闭预览会话
            CameraCaptureSession previewSession = captureSessions.get(physicalCameraId);
            if (previewSession != null) {
                previewSession.close();
                captureSessions.remove(physicalCameraId);
            }
            
            // 设置MediaRecorder
            MediaRecorder mediaRecorder = new MediaRecorder();
            mediaRecorders.put(physicalCameraId, mediaRecorder);
            
            setupMediaRecorder(physicalCameraId, mediaRecorder, videoSize);
            
            // 创建新的录制会话
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = mediaRecorder.getSurface();
            
            List<Surface> surfaces = Arrays.asList(previewSurface, recordSurface);
            
            CaptureRequest.Builder recordBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            recordBuilder.addTarget(previewSurface);
            recordBuilder.addTarget(recordSurface);
            
            // 设置物理相机特定的录制参数
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                recordBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, (long) (1000000000.0 / 30.0));
            }
            
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSessions.put(physicalCameraId, session);
                    try {
                        session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        Log.d(TAG, "Recording started for camera: " + physicalCameraId);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error starting recording for camera: " + physicalCameraId, e);
                    }
                }
                
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Recording session configuration failed for camera: " + physicalCameraId);
                }
            }, backgroundHandler);
            
        } catch (IOException | CameraAccessException e) {
            Log.e(TAG, "Error starting recording for camera: " + physicalCameraId, e);
        }
    }
    
    private void setupMediaRecorder(String physicalCameraId, MediaRecorder mediaRecorder, Size videoSize) throws IOException {
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        
        String videoPath = getVideoFilePath(physicalCameraId);
        videoPaths.put(physicalCameraId, videoPath);
        mediaRecorder.setOutputFile(videoPath);
        
        mediaRecorder.setVideoEncodingBitRate(10000000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        
        mediaRecorder.prepare();
    }
    
    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        
        isRecording = false;
        
        for (String physicalCameraId : cameraDevices.keySet()) {
            stopRecordingForCamera(physicalCameraId);
        }
    }
    
    private void stopRecordingForCamera(String physicalCameraId) {
        MediaRecorder mediaRecorder = mediaRecorders.get(physicalCameraId);
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.reset();
                Log.d(TAG, "Recording stopped for camera: " + physicalCameraId);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording for camera: " + physicalCameraId, e);
            }
            mediaRecorders.remove(physicalCameraId);
        }
        
        // 重新创建预览会话
        createCameraPreviewSession(physicalCameraId);
    }
    
    public void closeAllCameras() {
        stopRecording();
        
        for (String physicalCameraId : cameraDevices.keySet()) {
            CameraCaptureSession session = captureSessions.get(physicalCameraId);
            if (session != null) {
                session.close();
            }
            
            CameraDevice device = cameraDevices.get(physicalCameraId);
            if (device != null) {
                device.close();
            }
            
            MediaRecorder recorder = mediaRecorders.get(physicalCameraId);
            if (recorder != null) {
                recorder.release();
            }
        }
        
        cameraDevices.clear();
        captureSessions.clear();
        previewRequestBuilders.clear();
        mediaRecorders.clear();
        videoSizes.clear();
        videoPaths.clear();
        textureViews.clear();
    }
    
    private String getVideoFilePath(String physicalCameraId) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
        return context.getExternalFilesDir(null) + "/" + "camera_" + physicalCameraId + "_" + timeStamp + ".mp4";
    }
    
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        return choices.length > 0 ? choices[choices.length - 1] : new Size(1280, 720);
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    public Map<String, String> getRecordedVideoPaths() {
        return new ConcurrentHashMap<>(videoPaths);
    }
}