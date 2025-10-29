package com.android.mycamera.camera.factory;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;

import com.android.mycamera.camera.strategy.Camera1Strategy;
import com.android.mycamera.camera.strategy.Camera2Strategy;
import com.android.mycamera.camera.strategy.CameraStrategy;
import com.android.mycamera.camera.strategy.CameraXStrategy;
import com.android.mycamera.model.CameraApiType;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced implementation of CameraFactory for creating camera strategies
 * with comprehensive error handling and API support validation
 */
public class CameraHelperFactory implements CameraFactory {
    
    private static final String TAG = "CameraHelperFactory";
    
    private final Context context;
    
    public CameraHelperFactory(Context context) {
        this.context = context.getApplicationContext();
    }
    
    @Override
    public CameraStrategy createCameraStrategy(CameraApiType apiType) {
        if (!isApiSupported(apiType)) {
            throw new UnsupportedOperationException("Camera API " + apiType + " is not supported on this device");
        }
        Log.d(TAG, "createCameraStrategy: " + apiType.getDisplayName());
        
        try {
            switch (apiType) {
                case CAMERA1:
                    Log.d(TAG, "Creating Camera1 strategy");
                    return new Camera1Strategy(context);
                case CAMERA2:
                    Log.d(TAG, "Creating Camera2 strategy");
                    return new Camera2Strategy(context);
                case CAMERAX:
                    Log.d(TAG, "Creating CameraX strategy");
                    return new CameraXStrategy(context);
                default:
                    String errorMsg = "Unsupported camera API: " + apiType;
                    Log.e(TAG, errorMsg);
                    throw new IllegalArgumentException(errorMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create camera strategy for API: " + apiType, e);
            throw new RuntimeException("Failed to initialize camera: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isApiSupported(CameraApiType apiType) {
        try {
            switch (apiType) {
                case CAMERA1:
                    return isCamera1Supported();
                case CAMERA2:
                    return isCamera2Supported();
                case CAMERAX:
                    return isCameraXSupported();
                default:
                    return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking API support for: " + apiType, e);
            return false;
        }
    }
    
    @Override
    public CameraApiType[] getSupportedApis() {
        List<CameraApiType> supportedApis = new ArrayList<>();
        
        for (CameraApiType apiType : CameraApiType.values()) {
            if (isApiSupported(apiType)) {
                supportedApis.add(apiType);
                Log.d(TAG, "API supported: " + apiType);
            } else {
                Log.d(TAG, "API not supported: " + apiType);
            }
        }
        
        return supportedApis.toArray(new CameraApiType[0]);
    }
    
    /**
     * Get the recommended camera API for this device
     */
    public CameraApiType getRecommendedApi() {
        // Prefer CameraX if available, then Camera2, then Camera1
        if (isApiSupported(CameraApiType.CAMERAX)) {
            return CameraApiType.CAMERAX;
        } else if (isApiSupported(CameraApiType.CAMERA2)) {
            return CameraApiType.CAMERA2;
        } else if (isApiSupported(CameraApiType.CAMERA1)) {
            return CameraApiType.CAMERA1;
        } else {
            throw new IllegalStateException("No camera APIs are supported on this device");
        }
    }
    
    /**
     * Get detailed information about API support
     */
    public String getApiSupportInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Camera API Support:\n");
        
        for (CameraApiType apiType : CameraApiType.values()) {
            boolean supported = isApiSupported(apiType);
            info.append("  ").append(apiType).append(": ").append(supported ? "SUPPORTED" : "NOT_SUPPORTED");
            
            if (!supported) {
                info.append(" (").append(getUnsupportedReason(apiType)).append(")");
            }
            info.append("\n");
        }
        
        return info.toString();
    }
    
    /**
     * Get the number of available cameras
     */
    public int getAvailableCameraCount() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    return manager.getCameraIdList().length;
                }
            }
            // For older versions, use legacy method
            return Camera1Strategy.getCameraCount();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get camera count", e);
            return 0;
        }
    }
    
    /**
     * Check if device has camera hardware
     */
    public boolean hasCameraHardware() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
               context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }
    
    private boolean isCamera1Supported() {
        // Camera1 is supported on all Android versions that have camera hardware
        return hasCameraHardware();
    }
    
    private boolean isCamera2Supported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            return manager != null && manager.getCameraIdList().length > 0;
        } catch (Exception e) {
            Log.w(TAG, "Camera2 support check failed", e);
            return false;
        }
    }
    
    private boolean isCameraXSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        
        try {
            // Check if CameraX dependencies are available
            Class.forName("androidx.camera.core.CameraSelector");
            Class.forName("androidx.camera.lifecycle.ProcessCameraProvider");
            
            // Additional check for CameraX compatibility
            return isCamera2Supported(); // CameraX requires Camera2
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "CameraX dependencies not found");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "CameraX support check failed", e);
            return false;
        }
    }
    
    private String getUnsupportedReason(CameraApiType apiType) {
        switch (apiType) {
            case CAMERA1:
                return "No camera hardware found";
            case CAMERA2:
                return "Android " + Build.VERSION_CODES.LOLLIPOP + " or higher required";
            case CAMERAX:
                return "CameraX dependencies not available or Android " + Build.VERSION_CODES.LOLLIPOP + " or higher required";
            default:
                return "Unknown reason";
        }
    }
}