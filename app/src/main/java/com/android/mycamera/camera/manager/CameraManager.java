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
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.PhotoResolution;
import com.android.mycamera.model.Quality;
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
    /** True when settings staged a config that is not yet applied to the live pipeline. */
    private boolean configurationDirty = false;
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
        currentConfig = applyVideoCapabilityConstraints(currentConfig);
        settingsManager.saveCameraConfig(currentConfig);
        configurationDirty = false;
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
            CameraConfig.Builder configBuilder = new CameraConfig.Builder(currentConfig)
                    .setApiType(newApiType);
            if (newApiType == CameraApiType.CAMERAX) {
                // CameraX only supports HD/FHD among our selectable set (no 2K preset).
                // If current video is 2K, demote to FHD so user can actually switch back to CameraX.
                Quality quality = Quality.normalizeSelectable(currentConfig.getQuality());
                if (quality == Quality.QHD) {
                    Log.w(TAG, "Switching to CameraX: 2K is unsupported by CameraX, fallback video quality to FHD");
                    quality = Quality.FULL_HD;
                }
                configBuilder.setQuality(quality)
                        .setResolution(Resolution.of(quality.getWidth(), quality.getHeight()));
            } else if (newApiType == CameraApiType.CAMERA2) {
                // Prefer resolution matched to selected video quality (HD/FHD/2K).
                Quality quality = Quality.normalizeSelectable(currentConfig.getQuality());
                Resolution resolution = Resolution.of(quality.getWidth(), quality.getHeight());
                if (resolution == null) {
                    resolution = Resolution.FULL_HD_1080P;
                }
                configBuilder.setQuality(quality).setResolution(resolution);
            } else {
                // Camera1 only supports fixed resolutions; fallback to 1920x1080.
                Quality quality = Quality.normalizeSelectable(currentConfig.getQuality());
                Resolution resolution = Resolution.of(quality.getWidth(), quality.getHeight());
                if (resolution == null || !resolution.isFixed()) {
                    resolution = Resolution.FULL_HD_1080P;
                }
                configBuilder.setQuality(quality).setResolution(resolution);
            }
            currentConfig = applyVideoCapabilityConstraints(configBuilder.build());
            // Strategy must follow constrained API (e.g. CameraX+2K -> Camera2).
            currentStrategy = getCameraStrategy(currentConfig.getApiType());
            Log.d(TAG, "switchCameraApi requested=" + newApiType
                    + ", effective=" + currentConfig.getApiType()
                    + ", quality=" + currentConfig.getQuality()
                    + ", resolution=" + currentConfig.getResolution()
                    + ", strategy=" + currentStrategy);
            currentStrategy.addStateListener(this);
            
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
        if (currentStrategy == null || currentConfig == null) {
            return false;
        }
        // Build target config BEFORE reopen so cam1 does not briefly use cam0 12M/36M still size.
        PhotoResolution photoForCam = settingsManager.getPhotoResolutionForCamera(cameraId);
        currentConfig = new CameraConfig.Builder(currentConfig)
                .setCameraId(cameraId)
                .setPhotoResolution(photoForCam)
                .build();
        settingsManager.setCameraId(cameraId);
        settingsManager.saveCameraConfig(currentConfig);
        Log.d(TAG, "switchCamera id=" + cameraId + ", photo=" + photoForCam);

        currentStrategy.closeCamera();
        currentStrategy.openCamera(currentConfig);
        return true;
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
        // Long-press record can start from photo UI; pin CaptureMode.VIDEO so
        // post-record preview stays on the video pipeline (not JPEG photo mode).
        ensureCaptureMode(CaptureMode.VIDEO, false);
        if (isBackgroundRecordingEnabled() && isBackgroundRecordingServiceReady()) {
            backgroundRecordingHelper.startRecording();
        } else if (currentStrategy != null) {
            currentStrategy.startRecording();
        }
    }

    /**
     * Update capture mode on the shared config (and live strategy).
     * @param reapplyPipeline when true, rebuild session now; when false, only bind config
     */
    public void ensureCaptureMode(CaptureMode mode, boolean reapplyPipeline) {
        if (currentConfig == null) {
            return;
        }
        CaptureMode normalized = CaptureMode.normalize(mode);
        CaptureMode existing = CaptureMode.normalize(currentConfig.getCaptureMode());
        if (existing != normalized) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setCaptureMode(normalized)
                    .build();
            settingsManager.saveCameraConfig(currentConfig);
            Log.d(TAG, "ensureCaptureMode " + existing + " -> " + normalized
                    + ", reapply=" + reapplyPipeline);
        }
        if (currentStrategy != null) {
            currentStrategy.bindConfiguration(currentConfig);
            if (reapplyPipeline) {
                currentStrategy.openCamera(currentConfig);
            }
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
        if (currentConfig != null && currentConfig.getApiType() != null) {
            return currentConfig.getApiType();
        }
        return settingsManager.getCameraApiType();
    }
    
    /**
     * Update configuration
     */

    /**
     * Normalize video config per API:
     * - CameraX is quality-driven (and has no 2K preset).
     * - Camera1/Camera2 are resolution-driven; never overwrite user resolution with quality size.
     */
    private CameraConfig applyVideoCapabilityConstraints(CameraConfig config) {
        if (config == null) {
            return null;
        }
        CameraApiType apiType = config.getApiType();
        Quality quality = Quality.normalizeSelectable(config.getQuality());
        Resolution videoResolution = config.getResolution() != null
                ? config.getResolution()
                : Resolution.FULL_HD_1080P;

        if (apiType == CameraApiType.CAMERAX) {
            if (quality == Quality.QHD) {
                Log.w(TAG, "2K (2560x1440) is not a CameraX quality preset; using Camera2 so target size can apply");
                apiType = CameraApiType.CAMERA2;
                videoResolution = Resolution.of(quality.getWidth(), quality.getHeight());
            } else {
                // CameraX: keep resolution metadata aligned with quality.
                videoResolution = Resolution.of(quality.getWidth(), quality.getHeight());
            }
        } else {
            // Camera1/2: honor selected resolution, sync quality label to nearest match.
            if (videoResolution == null) {
                videoResolution = Resolution.FULL_HD_1080P;
            }
            quality = qualityFromResolution(videoResolution);
        }

        if (apiType == config.getApiType()
                && quality == config.getQuality()
                && videoResolution.equals(config.getResolution())) {
            return config;
        }

        Log.d(TAG, "applyVideoCapabilityConstraints api=" + apiType
                + ", quality=" + quality
                + ", resolution=" + videoResolution);
        return new CameraConfig.Builder(config)
                .setApiType(apiType)
                .setQuality(quality)
                .setResolution(videoResolution)
                .build();
    }

    private Quality qualityFromResolution(Resolution resolution) {
        if (resolution == null) {
            return Quality.DEFAULT;
        }
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        if (width == 2560 && height == 1440) {
            return Quality.QHD;
        }
        if (width == 1920 && height == 1080) {
            return Quality.FULL_HD;
        }
        if (width == 1280 && height == 720) {
            return Quality.HD;
        }
        long area = (long) width * height;
        long bestDelta = Long.MAX_VALUE;
        Quality best = Quality.DEFAULT;
        for (Quality candidate : Quality.selectableValues()) {
            long candidateArea = (long) candidate.getWidth() * candidate.getHeight();
            long delta = Math.abs(candidateArea - area);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Persist config only. Do not reopen/rebuild camera pipeline.
     * Used by Settings so spinner changes feel instant; MainActivity applies on resume.
     */
    public void stageConfiguration(CameraConfig newConfig) {
        Log.d(TAG, "stageConfiguration: ");
        this.currentConfig = applyVideoCapabilityConstraints(newConfig);
        settingsManager.saveCameraConfig(this.currentConfig);
        configurationDirty = true;
        Log.d(TAG, "stageConfiguration staged api=" + currentConfig.getApiType()
                + ", mode=" + currentConfig.getCaptureMode()
                + ", videoRes=" + currentConfig.getResolution()
                + ", photoRes=" + currentConfig.getPhotoResolution());
    }

    /**
     * Apply staged settings to the live camera if it is currently open.
     * @return true if a live reconfigure was performed
     */
    public boolean applyStagedConfigurationIfNeeded() {
        if (!configurationDirty || currentConfig == null) {
            return false;
        }
        configurationDirty = false;
        if (currentStrategy != null && isCameraAvailable()) {
            Log.d(TAG, "applyStagedConfigurationIfNeeded: applying live");
            updateConfiguration(currentConfig);
            return true;
        }
        Log.d(TAG, "applyStagedConfigurationIfNeeded: deferred until next open");
        return false;
    }

    public boolean isConfigurationDirty() {
        return configurationDirty;
    }

    public void updateConfiguration(CameraConfig newConfig) {
        Log.d(TAG, "updateConfiguration: ");
        CameraApiType previousApi = currentConfig != null ? currentConfig.getApiType() : null;
        this.currentConfig = applyVideoCapabilityConstraints(newConfig);
        settingsManager.saveCameraConfig(this.currentConfig);
        configurationDirty = false;
        Log.d(TAG, "updateConfiguration effective api=" + currentConfig.getApiType()
                + ", mode=" + currentConfig.getCaptureMode()
                + ", videoRes=" + currentConfig.getResolution()
                + ", photoRes=" + currentConfig.getPhotoResolution());

        if (isBackgroundRecordingServiceReady()) {
            updateBackgroundRecordingConfig(this.currentConfig);
            return;
        }

        // Only fully close when API strategy must change. Same-API pipeline switches
        // (photo/video, resolution) rebuild the capture session without device reopen.
        boolean apiChanged = previousApi != currentConfig.getApiType();
        if (apiChanged && currentStrategy != null) {
            currentStrategy.closeCamera();
            currentStrategy = null;
        }
        if (currentStrategy == null || apiChanged) {
            currentStrategy = getCameraStrategy(currentConfig.getApiType());
            currentStrategy.addStateListener(this);
        }
        currentStrategy.openCamera(currentConfig);
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
            CameraApiType initialApi = currentConfig.getApiType();
            String cacheKey = "preinit_" + initialApi.name();
            if (!cameraCache.containsKey(cacheKey)) {
                cameraCache.put(cacheKey, cameraFactory.createCameraStrategy(initialApi));
            }
            
            Log.d(TAG, "Camera pre-initialization completed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to pre-initialize camera", e);
        }
        
        PerformanceUtils.endMeasurement("CameraManager_preInitialize");
    }

    public CameraStrategy getCameraStrategy(CameraApiType apiType) {
        String cacheKey = "preinit_" + apiType.name();
        CameraStrategy strategy = cameraCache.get(cacheKey);
        if (strategy != null) {
            return strategy;
        }
        if (cameraFactory == null) {
            cameraFactory = new CameraHelperFactory(context);
        }
        strategy = cameraFactory.createCameraStrategy(apiType);
        cameraCache.put(cacheKey, strategy);
        return strategy;
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
