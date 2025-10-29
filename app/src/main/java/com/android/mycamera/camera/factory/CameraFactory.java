package com.android.mycamera.camera.factory;

import com.android.mycamera.camera.strategy.CameraStrategy;
import com.android.mycamera.model.CameraApiType;

/**
 * Factory interface for creating camera strategies
 */
public interface CameraFactory {
    
    /**
     * Create camera strategy based on API type
     */
    CameraStrategy createCameraStrategy(CameraApiType apiType);
    
    /**
     * Check if camera API is supported on this device
     */
    boolean isApiSupported(CameraApiType apiType);
    
    /**
     * Get supported camera APIs for this device
     */
    CameraApiType[] getSupportedApis();
}