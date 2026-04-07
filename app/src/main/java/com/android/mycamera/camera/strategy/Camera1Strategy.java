package com.android.mycamera.camera.strategy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.util.Log;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.CameraUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Complete Camera1 implementation of CameraStrategy
 */
public class Camera1Strategy extends BaseCameraStrategy {
    
    private static final String TAG = "Camera1Strategy";
    
    private Camera camera;
    private MediaRecorder mediaRecorder;
    private TextureView textureView;
    private boolean isRecording = false;
    private boolean isPreviewing = false;
    private CameraConfig currentConfig;
    private int currentCameraId = 0;
    
    // Flash related fields
    private boolean isFlashEnabled = false;
    private boolean isFlashAvailable = false;
    
    public Camera1Strategy(Context context) {
        super(context);
    }
    
    /**
     * Get the number of available cameras using Camera1 API
     */
    public static int getCameraCount() {
        return Camera.getNumberOfCameras();
    }
    
    @Override
    protected String getTag() {
        return TAG;
    }
    
    @Override
    public void openCamera(CameraConfig config) {
        logDebug("Opening Camera1 with config: " + config);
        this.currentConfig = config;
        notifyStateChanged(CameraState.INITIALIZING);

        try {
            // Parse camera ID from config
            String cameraIdStr = config.getCameraId();
            if (cameraIdStr != null && !cameraIdStr.isEmpty()) {
                try {
                    currentCameraId = Integer.parseInt(cameraIdStr);
                } catch (NumberFormatException e) {
                    currentCameraId = 0; // Default to back camera
                }
            }

            // Check if camera ID is valid
            if (currentCameraId >= Camera.getNumberOfCameras()) {
                currentCameraId = 0;
            }

            camera = Camera.open(currentCameraId);
            if (camera != null) {
                // DO NOT set any parameters here. Let the preview handle it, or use defaults.
                notifyStateChanged(CameraState.OPENED);
                logDebug("Camera1 opened successfully, using default parameters.");
            } else {
                notifyError("Failed to open Camera1");
            }

        } catch (Exception e) {
            logError("Failed to open Camera1", e);
            notifyError("Camera1 open failed: " + e.getMessage());
        }
    }
    
    @Override
    public void startPreview(TextureView textureView, Object lifecycleOwner) {
        if (camera == null) {
            notifyError("Camera not initialized");
            return;
        }
        
        this.textureView = textureView;
        logDebug("Starting Camera1 preview");
        
        try {
            applyPreviewConfiguration();

            // Ensure preview size is set on the surface texture
            android.graphics.SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                logError("SurfaceTexture is null, cannot start preview", new Exception());
                return;
            }
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size previewSize = parameters.getPreviewSize();
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height);

            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            isPreviewing = true;
            notifyPreviewStarted();
            logDebug("Camera1 preview started");
            
        } catch (IOException e) {
            logError("Failed to start Camera1 preview", e);
            notifyError("Preview start failed: " + e.getMessage());
        }
    }

    private void applyPreviewConfiguration() {
        if (camera == null || currentConfig == null) return;
        try {
            Camera.Parameters parameters = camera.getParameters();
            Resolution resolution = currentConfig.getResolution();

            Camera.Size targetPreviewSize = chooseBestPreviewSize(parameters.getSupportedPreviewSizes(), resolution);
            if (targetPreviewSize != null) {
                parameters.setPreviewSize(targetPreviewSize.width, targetPreviewSize.height);
            }

            int[] targetFpsRange = chooseBestPreviewFpsRange(parameters.getSupportedPreviewFpsRange(), currentConfig.getFrameRate());
            if (targetFpsRange != null) {
                parameters.setPreviewFpsRange(targetFpsRange[0], targetFpsRange[1]);
            }

            camera.setParameters(parameters);
        } catch (Exception e) {
            logError("Failed to apply Camera1 preview configuration", e);
        }
    }

    private Camera.Size chooseBestPreviewSize(List<Camera.Size> supportedSizes, Resolution desiredResolution) {
        if (supportedSizes == null || supportedSizes.isEmpty() || desiredResolution == null) return null;

        Camera.Size exact = null;
        Camera.Size closest = null;
        long bestAreaDiff = Long.MAX_VALUE;
        long targetArea = (long) desiredResolution.getWidth() * desiredResolution.getHeight();

        for (Camera.Size size : supportedSizes) {
            if (size.width == desiredResolution.getWidth() && size.height == desiredResolution.getHeight()) {
                exact = size;
                break;
            }
            long area = (long) size.width * size.height;
            long areaDiff = Math.abs(area - targetArea);
            if (areaDiff < bestAreaDiff) {
                bestAreaDiff = areaDiff;
                closest = size;
            }
        }
        return exact != null ? exact : closest;
    }

    private int[] chooseBestPreviewFpsRange(List<int[]> supportedFpsRanges, int desiredFps) {
        if (supportedFpsRanges == null || supportedFpsRanges.isEmpty() || desiredFps <= 0) return null;

        int desired = desiredFps * 1000;
        int[] bestRange = null;
        int bestScore = Integer.MAX_VALUE;

        for (int[] range : supportedFpsRanges) {
            if (range == null || range.length < 2) continue;
            int lower = range[0];
            int upper = range[1];
            int clamped = Math.max(lower, Math.min(desired, upper));
            int distance = Math.abs(clamped - desired);
            int span = upper - lower;
            int score = distance * 1000 + span;
            if (score < bestScore) {
                bestScore = score;
                bestRange = range;
            }
        }

        return bestRange;
    }
    
    @Override
    public void stopPreview() {
        logDebug("Stopping Camera1 preview");
        
        if (camera != null && isPreviewing) {
            camera.stopPreview();
            isPreviewing = false;
            notifyStateChanged(CameraState.CLOSED);
        }
    }
    
    @Override
    public void startRecording() {
        if (camera == null || isRecording) {
            return;
        }
        
        logDebug("Starting Camera1 recording");
        
        try {
            // Stop preview before recording
            if (isPreviewing) {
                camera.stopPreview();
            }
            
            // Initialize media recorder
            mediaRecorder = new MediaRecorder();
            
            // Unlock camera
            camera.unlock();
            mediaRecorder.setCamera(camera);
            
            // Configure media recorder
            if (currentConfig.isAudioEnabled()) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            
            // Set output format
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            
            // Set encoders
            if (currentConfig.isAudioEnabled()) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            
            // Set video size and frame rate
            Resolution resolution = currentConfig.getResolution();
            mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
            mediaRecorder.setVideoFrameRate(currentConfig.getFrameRate());
            
            // Set output file
            File outputFile = CameraUtils.generateUniqueMediaFile("mp4");
            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
            
            // Prepare and start recording
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            notifyRecordingStarted();
            logDebug("Camera1 recording started");
            
        } catch (Exception e) {
            logError("Failed to start Camera1 recording", e);
            releaseMediaRecorder();
            if (camera != null) {
                camera.lock();
            }
            notifyError("Recording start failed: " + e.getMessage());
        }
    }
    
    @Override
    public void stopRecording() {
        logDebug("Stopping Camera1 recording");
        
        if (!isRecording || mediaRecorder == null) {
            return;
        }
        
        try {
            mediaRecorder.stop();
            String outputPath = getCurrentOutputPath();
            releaseMediaRecorder();
            isRecording = false;
            
            // Re-lock camera and restart preview
            if (camera != null) {
                camera.lock();
                if (textureView != null) {
                    startPreview(textureView, null);
                }
            }
            
            notifyRecordingStopped();
            if (!outputPath.isEmpty()) {
                notifyPhotoCaptured(outputPath);
            }
            
            logDebug("Camera1 recording stopped");
            
        } catch (Exception e) {
            logError("Failed to stop Camera1 recording", e);
            releaseMediaRecorder();
            if (camera != null) {
                camera.lock();
            }
            notifyError("Recording stop failed: " + e.getMessage());
        }
    }
    
    @Override
    public void capturePhoto() {
        if (camera == null || !isPreviewing) {
            notifyError("Camera not ready for photo capture");
            return;
        }
        
        logDebug("Capturing photo with Camera1");
        
        try {
            applyPhotoConfiguration();

            // Take picture
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    try {
                        // Save the image
                        File outputFile = CameraUtils.generateUniqueMediaFile("jpg");
                        FileOutputStream fos = new FileOutputStream(outputFile);
                        fos.write(data);
                        fos.close();
                        
                        logDebug("Camera1 photo saved: " + outputFile.getAbsolutePath());
                        notifyPhotoCaptured(outputFile.getAbsolutePath());
                        
                        // Restart preview
                        camera.startPreview();
                        
                    } catch (Exception e) {
                        logError("Failed to save Camera1 photo", e);
                        notifyError("Photo save failed: " + e.getMessage());
                        camera.startPreview();
                    }
                }
            });
            
        } catch (Exception e) {
            logError("Failed to capture Camera1 photo", e);
            notifyError("Photo capture failed: " + e.getMessage());
        }
    }

    private void applyPhotoConfiguration() {
        if (camera == null || currentConfig == null) return;
        try {
            Camera.Parameters parameters = camera.getParameters();
            Resolution resolution = currentConfig.getResolution();
            Camera.Size targetPictureSize = chooseBestPictureSize(parameters.getSupportedPictureSizes(), resolution);
            if (targetPictureSize != null) {
                parameters.setPictureSize(targetPictureSize.width, targetPictureSize.height);
                logDebug("Camera1 picture size set to: " + targetPictureSize.width + "x" + targetPictureSize.height);
                camera.setParameters(parameters);
            }
        } catch (Exception e) {
            logError("Failed to apply Camera1 photo configuration", e);
        }
    }

    private Camera.Size chooseBestPictureSize(List<Camera.Size> supportedSizes, Resolution desiredResolution) {
        if (supportedSizes == null || supportedSizes.isEmpty() || desiredResolution == null) return null;

        Camera.Size exact = null;
        Camera.Size closest = null;
        long bestAreaDiff = Long.MAX_VALUE;
        long targetArea = (long) desiredResolution.getWidth() * desiredResolution.getHeight();

        for (Camera.Size size : supportedSizes) {
            if (size.width == desiredResolution.getWidth() && size.height == desiredResolution.getHeight()) {
                exact = size;
                break;
            }
            long area = (long) size.width * size.height;
            long areaDiff = Math.abs(area - targetArea);
            if (areaDiff < bestAreaDiff) {
                bestAreaDiff = areaDiff;
                closest = size;
            }
        }
        return exact != null ? exact : closest;
    }
    
    @Override
    public void closeCamera() {
        logDebug("Closing Camera1");
        
        if (isRecording) {
            stopRecording();
        }
        
        if (camera != null) {
            try {
                camera.cancelAutoFocus();
            } catch (Exception e) {
                logError("Failed to cancel auto focus on close", e);
            }
            if (isPreviewing) {
                camera.stopPreview();
            }
            camera.release();
            camera = null;
            isPreviewing = false;
        }
        
        notifyStateChanged(CameraState.CLOSED);
    }
    
    @Override
    public boolean isCameraAvailable() {
        return camera != null;
    }
    
    @Override
    public boolean switchCamera(String cameraId) {
        logDebug("Switching Camera1 to camera ID: " + cameraId);
        
        try {
            int newCameraId = Integer.parseInt(cameraId);
            if (newCameraId == currentCameraId) {
                return true; // Already using this camera
            }
            
            if (newCameraId >= Camera.getNumberOfCameras()) {
                notifyError("Invalid camera ID: " + cameraId);
                return false;
            }
            
            // Close current camera
            closeCamera();
            
            // Update camera ID
            currentCameraId = newCameraId;
            
            // Reopen camera with new ID
            if (currentConfig != null) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setCameraId(cameraId)
                        .build();
                
                openCamera(currentConfig);
                return true;
            }
            
            return false;
            
        } catch (NumberFormatException e) {
            notifyError("Invalid camera ID format: " + cameraId);
            return false;
        }
    }
    
    
    
    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception e) {
                // Ignore release errors
            }
            mediaRecorder = null;
        }
    }
    
    private String getCurrentOutputPath() {
        // This would normally track the current output file path
        // For now, return a placeholder
        return "/sdcard/DCIM/Camera/" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_C1.mp4";
    }
    
    @Override
    public boolean toggleFlash() {
        if (camera == null) return false;
        try {
            Camera.Parameters parameters = camera.getParameters();
            if (parameters.getSupportedFlashModes() == null) {
                isFlashAvailable = false;
                return false;
            }
            isFlashAvailable = true;
            isFlashEnabled = !isFlashEnabled;
            parameters.setFlashMode(isFlashEnabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameters);
            logDebug("Flash toggled: " + (isFlashEnabled ? "ON" : "OFF"));
            return true;
        } catch (Exception e) {
            logError("Failed to toggle flash", e);
            return false;
        }
    }
    
    @Override
    public boolean isFlashAvailable() {
        if (camera == null) return false;
        return camera.getParameters().getSupportedFlashModes() != null;
    }
    
    @Override
    public boolean isFlashEnabled() {
        return isFlashEnabled;
    }

    public void setFocusPoint(float x, float y) {
        if (camera == null || textureView == null) return;

        try {
            camera.cancelAutoFocus(); // Cancel any ongoing focus
            Camera.Parameters parameters = camera.getParameters();
            if (parameters.getMaxNumFocusAreas() > 0) {
                Rect focusRect = calculateTapArea(x, y, 1.5f);
                List<Camera.Area> focusAreas = new ArrayList<>();
                focusAreas.add(new Camera.Area(focusRect, 1000));

                parameters.setFocusAreas(focusAreas);
                if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
                camera.setParameters(parameters);

                final Handler focusTimeoutHandler = new Handler(Looper.getMainLooper());
                final Runnable focusTimeoutRunnable = () -> {
                    if (camera != null) {
                        logDebug("Focus timed out, canceling.");
                        camera.cancelAutoFocus();
                        resetFocusMode(camera);
                    }
                };

                camera.autoFocus((success, camera) -> {
                    focusTimeoutHandler.removeCallbacks(focusTimeoutRunnable);
                    resetFocusMode(camera);
                });

                focusTimeoutHandler.postDelayed(focusTimeoutRunnable, 5000); // 2-second timeout
            }
        } catch (Exception e) {
            logError("Failed to set focus point", e);
        }
    }

    private void resetFocusMode(Camera camera) {
        try {
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(params);
            }
        } catch (Exception e) {
            logError("Failed to reset focus mode", e);
        }
    }

    private Rect calculateTapArea(float x, float y, float coefficient) {
        int areaSize = Float.valueOf(300 * coefficient).intValue();
        int left = clamp((int) (x - areaSize / 2), 0, textureView.getWidth() - areaSize);
        int top = clamp((int) (y - areaSize / 2), 0, textureView.getHeight() - areaSize);
        Rect rect = new Rect(left, top, left + areaSize, top + areaSize);
        return new Rect(
                rect.left * 2000 / textureView.getWidth() - 1000,
                rect.top * 2000 / textureView.getHeight() - 1000,
                rect.right * 2000 / textureView.getWidth() - 1000,
                rect.bottom * 2000 / textureView.getHeight() - 1000
        );
    }

    private int clamp(int x, int min, int max) {
        if (x > max) return max;
        if (x < min) return min;
        return x;
    }

    @Override
    public boolean isFocusSupported() {
        if (camera == null) return false;
        List<String> focusModes = camera.getParameters().getSupportedFocusModes();
        if (focusModes == null || focusModes.isEmpty()) return false;
        // If it only supports fixed focus, we don't consider it "focusable".
        if (focusModes.size() == 1 && focusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
            return false;
        }
        // If it supports any of the common AF modes, we consider it focusable.
        return focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ||
               focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ||
               focusModes.contains(Camera.Parameters.FOCUS_MODE_MACRO);
    }
}
