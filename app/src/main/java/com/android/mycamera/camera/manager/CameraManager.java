package com.android.mycamera.camera.manager;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;
import android.util.Range;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.factory.CameraFactory;
import com.android.mycamera.camera.factory.CameraHelperFactory;
import com.android.mycamera.camera.helper.BackgroundRecordingHelper;
import com.android.mycamera.camera.observer.CameraStateObserver;
import com.android.mycamera.camera.observer.CameraStateManager;
import com.android.mycamera.camera.strategy.BackgroundRecordingService;
import com.android.mycamera.camera.strategy.CameraStrategy;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.MemoryManager;
import com.android.mycamera.utils.PerformanceUtils;
import com.android.mycamera.utils.SettingsManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton camera manager that manages camera operations using strategy pattern
 */
public class CameraManager implements CameraStrategy.CameraStateListener {
    
    private static final String TAG = "CameraManager";
    
    private static volatile CameraManager instance;
    
    private final Context context;
    private final CameraStateManager stateManager;
    private final SettingsManager settingsManager;
    private final MemoryManager memoryManager;
    
    private CameraStrategy currentStrategy;
    private CameraConfig currentConfig;
    private final Map<String, CameraStrategy> cameraCache = new HashMap<>();
    private boolean isPerformanceOptimized = false;
    
    private BackgroundRecordingHelper backgroundRecordingHelper;
    private CameraFactory cameraFactory;

    private CameraManager(Context context) {
        this.context = context.getApplicationContext();
        this.stateManager = new CameraStateManager();
        this.settingsManager = new SettingsManager(context);
        this.memoryManager = MemoryManager.getInstance();
        
        // Load saved configuration
        this.currentConfig = settingsManager.loadCameraConfig();
        backgroundRecordingHelper = new BackgroundRecordingHelper(context, this);

        
        // Initialize performance monitoring
        PerformanceUtils.logMemoryUsage("CameraManager_Init");
    }
    
    /**
     * Get singleton instance
     */
    public static CameraManager getInstance(Context context) {
        if (instance == null) {
            synchronized (CameraManager.class) {
                if (instance == null) {
                    instance = new CameraManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize camera with current configuration
     */
    public void initializeCamera(CameraStateObserver observer) {
        Log.d(TAG, "initializeCamera: ");
        stateManager.addObserver(observer);
        currentStrategy = getCameraStrategy(currentConfig.getApiType());
        currentStrategy.addStateListener(this);


        if (isBackgroundRecordingEnabled()) {
            backgroundRecordingHelper.initialize(new BackgroundRecordingHelper.Callback() {
                @Override
                public void onServiceConn() {
                    backgroundRecordingHelper.setCurrentStrategy(currentStrategy);
                    backgroundRecordingHelper.openCamera(currentConfig);
                }

                @Override
                public void onServiceDisconn() {

                }
            });
        } else {
            currentStrategy.openCamera(currentConfig);
        }


    }
    
    /**
     * Switch camera API
     */
    public boolean switchCameraApi(CameraApiType newApiType) {
        Log.d(TAG, "switchCameraApi: ");

        if (!cameraFactory.isApiSupported(newApiType)) {
            Log.e(TAG, "Camera API not supported: " + newApiType);
            return false;
        }



        Log.d(TAG, "switchCameraApi currentStrategy: " + currentStrategy);
        // if (currentStrategy != null) {
        //     currentStrategy.stopPreview();
        //     currentStrategy.closeCamera();
        // }
        stopPreview();
        closeCamera();



        try {
            currentStrategy = getCameraStrategy(newApiType);
            Log.d(TAG, "switchCameraApi newStrategy: " + currentStrategy);
            currentStrategy.addStateListener(this);
            CameraConfig.Builder configBuilder = new CameraConfig.Builder(currentConfig)
                    .setApiType(newApiType);
            if (newApiType != CameraApiType.CAMERA2 && !currentConfig.getResolution().isFixed()) {
                configBuilder.setResolution(Resolution.FULL_HD_1080P);
            }
            currentConfig = configBuilder.build();
            
            // Save new configuration
            settingsManager.saveCameraConfig(currentConfig);

            if (isBackgroundRecordingEnabled() && isBackgroundRecordingServiceReady()) {
                backgroundRecordingHelper.setCurrentStrategy(currentStrategy);
                new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                    backgroundRecordingHelper.openCamera(currentConfig);
                }, 500);
                return true;
            }

            new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                currentStrategy.openCamera(currentConfig);
                }, 300);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to switch camera API", e);
            return false;
        }
    }
    
    /**
     * Switch camera ID
     */
    public boolean switchCamera(String cameraId) {
        if (currentStrategy == null) {
            return false;
        }
        
        boolean success = currentStrategy.switchCamera(cameraId);
        if (success) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setCameraId(cameraId)
                    .build();
            
            settingsManager.setCameraId(cameraId);
        }
        
        return success;
    }
    
    /**
     * Start camera preview
     */
    public void startPreview(Object textureView, Object lifecycleOwner) {
        if (isBackgroundRecordingEnabled() && isBackgroundRecordingServiceReady()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                backgroundRecordingHelper.startPreview(textureView);
            }, 500);

        } else if (currentStrategy != null) {
            currentStrategy.startPreview((android.view.TextureView) textureView, lifecycleOwner);
        }
    }
    
    /**
     * Stop camera preview
     */
    public void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (isBackgroundRecordingServiceReady()) {
            backgroundRecordingHelper.stopPreview();
        } else if (currentStrategy != null) {
            currentStrategy.stopPreview();
        }
    }
    
    /**
     * Start recording
     */
    public void startRecording() {
        Log.d(TAG, "startRecording: ");
        if (isBackgroundRecordingEnabled() && isBackgroundRecordingServiceReady()) {
            backgroundRecordingHelper.startRecording();
        } else if (currentStrategy != null) {
            currentStrategy.startRecording();
        }
    }
    
    /**
     * Stop recording
     */
    public void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (isBackgroundRecordingEnabled() && isBackgroundRecordingActive()) {
            backgroundRecordingHelper.stopRecording();
        } else if (currentStrategy != null) {
            currentStrategy.stopRecording();
        }
    }


    /**
     * Close camera
     */
    public void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        if (isBackgroundRecordingEnabled() && isBackgroundRecordingServiceReady()) {
            backgroundRecordingHelper.closeCamera();
        } else if (currentStrategy != null) {
            currentStrategy.closeCamera();
        }
    }

    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "release: ");
        PerformanceUtils.startMeasurement("CameraManager_release");

        if (isBackgroundRecordingEnabled()) {
            backgroundRecordingHelper.release();
        }
        currentStrategy = null;

        cameraCache.clear();
        stateManager.removeAllObservers();

        // Optimize memory
        memoryManager.optimizeMemory();

        PerformanceUtils.endMeasurement("CameraManager_release");
    }


    /**
     * Capture photo
     */
    public void capturePhoto() {
        if (currentStrategy != null) {
            currentStrategy.capturePhoto();
        }
    }

    public void setFocusPoint(float x, float y) {
        if (currentStrategy != null) {
            currentStrategy.setFocusPoint(x, y);
        }
    }

    public void toggleFlash() {
        if (currentStrategy != null) {
            currentStrategy.toggleFlash();
        }
    }

    public boolean isFlashAvailable() {
        return currentStrategy != null && currentStrategy.isFlashAvailable();
    }

    public boolean isFlashEnabled() {
        return currentStrategy != null && currentStrategy.isFlashEnabled();
    }

    public boolean isZoomSupported() {
        return currentStrategy != null && currentStrategy.isZoomSupported();
    }

    public float getMinZoom() {
        return currentStrategy != null ? currentStrategy.getMinZoom() : 1f;
    }

    public float getMaxZoom() {
        return currentStrategy != null ? currentStrategy.getMaxZoom() : 1f;
    }

    public float getZoom() {
        return currentStrategy != null ? currentStrategy.getZoom() : 1f;
    }

    public void setZoom(float zoomRatio) {
        if (currentStrategy != null) {
            currentStrategy.setZoom(zoomRatio);
        }
    }

    public boolean isManualExposureSupported() {
        return currentStrategy != null && currentStrategy.isManualExposureSupported();
    }

    public Range<Integer> getSupportedIsoRange() {
        return currentStrategy != null ? currentStrategy.getSupportedIsoRange() : null;
    }

    public Range<Long> getSupportedExposureTimeRange() {
        return currentStrategy != null ? currentStrategy.getSupportedExposureTimeRange() : null;
    }

    public int getManualIso() {
        return currentStrategy != null ? currentStrategy.getManualIso() : 100;
    }

    public long getManualExposureTimeNs() {
        return currentStrategy != null ? currentStrategy.getManualExposureTimeNs() : 10_000_000L;
    }

    public boolean isManualExposureEnabled() {
        return currentStrategy != null && currentStrategy.isManualExposureEnabled();
    }

    public void setManualExposure(int iso, long exposureTimeNs) {
        if (currentStrategy != null) {
            currentStrategy.setManualExposure(iso, exposureTimeNs);
        }
    }

    public void resetAutoExposure() {
        if (currentStrategy != null) {
            currentStrategy.resetAutoExposure();
        }
    }

    public boolean isFocusSupported() {
        Log.d(TAG, "isFocusSupported: " + currentStrategy.isFocusSupported());
        return currentStrategy != null && currentStrategy.isFocusSupported();
    }

    /**
     * Get current camera state
     */
    public CameraState getCurrentState() {
        return stateManager.getCurrentState();
    }
    
    /**
     * Get current configuration
     */
    public CameraConfig getCurrentConfig() {
        return currentConfig;
    }

    public CameraApiType getCameraApiType() {
        return settingsManager.getCameraApiType();
    }
    
    /**
     * Update configuration
     */
    public void updateConfiguration(CameraConfig newConfig) {
        Log.d(TAG, "updateConfiguration: ");
        this.currentConfig = newConfig;
        settingsManager.saveCameraConfig(newConfig);

        if (isBackgroundRecordingServiceReady()) {
            updateBackgroundRecordingConfig(newConfig);
            return;
        }

        
        if (currentStrategy != null) {
            // Reopen camera with new configuration
            currentStrategy.closeCamera();
            currentStrategy.openCamera(newConfig);
        }
    }
    
    /**
     * Get supported resolutions
     */
    public List<Resolution> getSupportedResolutions() {
        if (currentStrategy != null) {
            return currentStrategy.getSupportedResolutions();
        }
        return java.util.Collections.emptyList();
    }
    
    /**
     * Get supported frame rates
     */
    public List<Integer> getSupportedFrameRates() {
        if (currentStrategy != null) {
            return currentStrategy.getSupportedFrameRates();
        }
        return java.util.Collections.emptyList();
    }
    
    /**
     * Get supported camera APIs
     */
    public CameraApiType[] getSupportedApis() {
        return cameraFactory.getSupportedApis();
    }
    
    /**
     * Check if camera is available
     */
    public boolean isCameraAvailable() {
        return currentStrategy != null && currentStrategy.isCameraAvailable();
    }
    
    /**
     * Add state observer
     */
    public void addStateObserver(CameraStateObserver observer) {
        stateManager.addObserver(observer);
    }
    
    /**
     * Remove state observer
     */
    public void removeStateObserver(CameraStateObserver observer) {
        stateManager.removeObserver(observer);
    }
    

    
    /**
     * Enable performance optimizations
     */
    public void enablePerformanceOptimizations() {
        if (!isPerformanceOptimized) {
            PerformanceUtils.startMeasurement("CameraManager_enablePerformanceOptimizations");
            
            // Enable camera caching
            isPerformanceOptimized = true;
            
            // Optimize memory
            memoryManager.optimizeMemory();
            
            // Log performance status
            PerformanceUtils.logMemoryUsage("CameraManager_Optimized");
            
            PerformanceUtils.endMeasurement("CameraManager_enablePerformanceOptimizations");
        }
    }
    
    /**
     * Get performance statistics
     */
    public String getPerformanceStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("CameraManager Performance Stats:\n");
        stats.append("Performance optimized: ").append(isPerformanceOptimized).append("\n");
        stats.append("Camera cache size: ").append(cameraCache.size()).append("\n");
        stats.append("Current state: ").append(getCurrentState()).append("\n");
        stats.append("Memory stats: ").append(memoryManager.getMemoryStats()).append("\n");
        stats.append(PerformanceUtils.getPerformanceSummary());
        
        return stats.toString();
    }
    
    /**
     * Pre-initialize camera for faster startup
     */
    public void preInitializeCamera() {
        if (!isPerformanceOptimized) {
            enablePerformanceOptimizations();
        }
        
        PerformanceUtils.startMeasurement("CameraManager_preInitialize");

        cameraFactory = new CameraHelperFactory(context);

        try {
            // Cache camera instances for faster switching
            for (CameraApiType apiType : getSupportedApis()) {
                String cacheKey = "preinit_" + apiType.name();
                if (!cameraCache.containsKey(cacheKey)) {
                    CameraStrategy strategy = cameraFactory.createCameraStrategy(apiType);
                    cameraCache.put(cacheKey, strategy);
                }
            }
            
            Log.d(TAG, "Camera pre-initialization completed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to pre-initialize camera", e);
        }
        
        PerformanceUtils.endMeasurement("CameraManager_preInitialize");
    }

    public CameraStrategy getCameraStrategy(CameraApiType apiType) {
        String cacheKey = "preinit_" + apiType.name();
        return cameraCache.get(cacheKey);
    }

    
    /**
     * Optimize memory usage
     */
    public void optimizeMemory() {
        memoryManager.optimizeMemory();
        PerformanceUtils.logMemoryUsage("CameraManager_OptimizeMemory");
    }
    
    // ===== BACKGROUND RECORDING METHODS =====
    
    /**
     * Check if background recording is enabled in settings
     */
    public boolean isBackgroundRecordingEnabled() {
        return settingsManager.isBackgroundRecordingEnabled();
    }

    /**
     * Check if camera preview should remain active while the activity is backgrounded.
     */
    public boolean isBackgroundReviewEnabled() {
        return settingsManager.isBackgroundReviewEnabled();
    }


    /**
     * Check if background recording service is ready
     */
    public boolean isBackgroundRecordingServiceReady() {
        return backgroundRecordingHelper.isBackgroundRecordingServiceReady();
    }

    /**
     * Check if background recording is currently active
     */
    public boolean isBackgroundRecordingActive() {
        return backgroundRecordingHelper.isBackgroundRecordingActive();
    }

    /**
     * Update background recording configuration
     */
    public void updateBackgroundRecordingConfig(CameraConfig config) {
        backgroundRecordingHelper.updateBackgroundRecordingConfig(config);
    }
    

    public CameraStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    @Override
    public void onStateChanged(CameraState state) {
        Log.d(TAG, "onStateChanged: " + state);
        stateManager.setState(state);
    }

    @Override
    public void onError(String errorMessage) {
        stateManager.notifyError(errorMessage);
    }

    @Override
    public void onRecordingStarted() {
        stateManager.notifyRecordingStarted();
    }

    @Override
    public void onRecordingStopped() {
        stateManager.notifyRecordingStopped();
    }

    @Override
    public void onPhotoCaptured(String filePath) {
        stateManager.notifyPhotoCaptured(filePath);
    }

    @Override
    public void onPreviewStarted() {
        stateManager.notifyPreviewStarted();
    }

    

    
}
