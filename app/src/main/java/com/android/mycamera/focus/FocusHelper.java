package com.android.mycamera.focus;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;

import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class FocusHelper {

    private static final String TAG = "FocusHelper";
    private Context context;
    private CameraControl cameraControl;
    private CameraInfo cameraInfo;
    private String currentCameraId = "0"; // 当前摄像头ID
    
    // Camera2 API 相关
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    
    // 对焦状态
    private static final int STATE_WAITING_LOCK = 1;
    private int mState;
    
    public FocusHelper(Context context) {
        this.context = context;
        startBackgroundThread();
    }

    public void setCameraControl(CameraControl cameraControl) {
        this.cameraControl = cameraControl;
    }

    public void setCameraInfo(CameraInfo cameraInfo) {
        this.cameraInfo = cameraInfo;
    }

    public void setCurrentCameraId(String cameraId) {
        this.currentCameraId = cameraId;
    }
    
    public void setCameraDevice(CameraDevice cameraDevice) {
        this.cameraDevice = cameraDevice;
    }
    
    public void setCaptureSession(CameraCaptureSession captureSession) {
        this.captureSession = captureSession;
    }
    
    public void setPreviewRequestBuilder(CaptureRequest.Builder previewRequestBuilder) {
        this.previewRequestBuilder = previewRequestBuilder;
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

    public void focusAtPoint(float x, float y, int viewWidth, int viewHeight) {
        if (cameraControl == null || cameraInfo == null) {
            Log.e(TAG, "Camera control or info not set");
            return;
        }

        try {
            // 创建测光点工厂
            MeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight);
            
            // 创建测光点
            MeteringPoint meteringPoint = factory.createPoint(x, y);
            
            // 创建聚焦动作
            FocusMeteringAction focusAction = new FocusMeteringAction.Builder(meteringPoint)
                    .setAutoCancelDuration(1000, TimeUnit.MILLISECONDS) // 1秒后自动取消
                    .build();
            
            // 执行聚焦
            cameraControl.startFocusAndMetering(focusAction);
            
            Log.d(TAG, "Focus requested at: (" + x + ", " + y + ")");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to focus: " + e.getMessage());
        }
    }

    public boolean isFocusSupported() {
        if (cameraInfo == null) return false;
        
        try {
            // 使用Camera2 API检查对焦支持
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return false;
            
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length == 0) return false;
            
            // 使用当前相机ID
            String cameraId = currentCameraId;
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            return hasFocuser(characteristics);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to check focus support: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error checking focus support: " + e.getMessage());
            return false;
        }
    }

    public static boolean hasFocuser(CameraCharacteristics characteristics) {
        if (characteristics == null) {
            Log.w(TAG, "[hasFocuser] characteristics is null");
            return false;
        }
        Float minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocusDistance != null && minFocusDistance > 0) {
            return true;
        }

        // Check available AF modes
        int[] availableAfModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

        if (availableAfModes == null) {
            return false;
        }

        // Assume that if we have an AF mode which doesn't ignore AF trigger, we have a focuser
        boolean hasFocuser = false;
        loop:
        for (int mode : availableAfModes) {
            switch (mode) {
                case CameraMetadata.CONTROL_AF_MODE_AUTO:
                case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                case CameraMetadata.CONTROL_AF_MODE_MACRO:
                    hasFocuser = true;
                    break loop;
                default:
                    break;
            }
        }
        Log.d(TAG, "[hasFocuser] hasFocuser = " + hasFocuser);
        return hasFocuser;
    }

    public void tapToFocus(float x, float y, int viewWidth, int viewHeight) {
        if (!isFocusSupported()) {
            Log.w(TAG, "Focus not supported on this device");
            return;
        }
        
        // 优先使用Camera2 API的对焦方式
        if (cameraDevice != null && captureSession != null && previewRequestBuilder != null) {
            lockFocus();
        } else {
            // 回退到CameraX对焦方式
            focusAtPoint(x, y, viewWidth, viewHeight);
        }
    }
    
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            captureSession.capture(previewRequestBuilder.build(), mCaptureCallback,
                    backgroundHandler);
            Log.d(TAG, "Focus lock triggered");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }


        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);

        }

        private void process(android.hardware.camera2.CaptureResult result) {
            switch (mState) {
                case STATE_WAITING_LOCK:
                    {
                        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                        if (afState == null) {
                            return;
                        } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                Log.d(TAG, "Focus locked successfully");
                                mState = 0; // Reset state
                            }
                        }
                        break;
                    }
            }
        }
    };

    public void resetFocus() {
        if (cameraControl == null) return;
        
        try {
            // 优先使用Camera2 API重置对焦
            if (cameraDevice != null && captureSession != null && previewRequestBuilder != null) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                Log.d(TAG, "Focus reset via Camera2 API");
            } else {
                // 回退到CameraX方式
                cameraControl.cancelFocusAndMetering();
                Log.d(TAG, "Focus reset via CameraX");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset focus: " + e.getMessage());
        }
    }
    
    public void release() {
        stopBackgroundThread();
    }

    // 获取相机对焦模式
    public void logCameraFocusModes() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) return;

        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                
                // 获取对焦模式
                int[] focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (focusModes != null) {
                    Log.d(TAG, "Camera " + cameraId + " focus modes:");
                    for (int mode : focusModes) {
                        switch (mode) {
                            case CameraCharacteristics.CONTROL_AF_MODE_AUTO:
                                Log.d(TAG, "  - AUTO");
                                break;
                            case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                                Log.d(TAG, "  - CONTINUOUS_PICTURE");
                                break;
                            case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                                Log.d(TAG, "  - CONTINUOUS_VIDEO");
                                break;
                            case CameraCharacteristics.CONTROL_AF_MODE_EDOF:
                                Log.d(TAG, "  - EDOF");
                                break;
                            case CameraCharacteristics.CONTROL_AF_MODE_MACRO:
                                Log.d(TAG, "  - MACRO");
                                break;
                            case CameraCharacteristics.CONTROL_AF_MODE_OFF:
                                Log.d(TAG, "  - OFF");
                                break;
                        }
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to access camera: " + e.getMessage());
        }
    }
}