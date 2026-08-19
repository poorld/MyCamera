package com.android.mycamera.camera.manager;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.factory.CameraFactory;
import com.android.mycamera.camera.factory.CameraHelperFactory;
import com.android.mycamera.camera.helper.BackgroundRecordingHelper;
import com.android.mycamera.camera.observer.CameraStateObserver;
import com.android.mycamera.camera.observer.CameraStateManager;
import com.android.mycamera.camera.strategy.BackgroundRecordingService;
import com.android.mycamera.camera.strategy.CameraStrategy;
import com.android.mycamera.camera.strategy.RecordingStats;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.model.VideoCodec;
import com.android.mycamera.utils.MemoryManager;
import com.android.mycamera.utils.PerformanceUtils;
import com.android.mycamera.utils.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
        if (currentConfig.getApiType() != CameraApiType.CAMERA1) {
            currentConfig = normalizePhotoResolutionForActiveCamera(currentConfig);
        }
        currentConfig = normalizeVideoResolutionForActiveMode(currentConfig);
        currentConfig = normalizeFrameRateForActiveMode(currentConfig);
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
            if (newApiType != CameraApiType.CAMERA1) {
                currentConfig = normalizePhotoResolutionForActiveCamera(currentConfig);
            }
            currentConfig = normalizeVideoResolutionForActiveMode(currentConfig);
            currentConfig = normalizeFrameRateForActiveMode(currentConfig);
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
        // Build target config BEFORE reopen so the new camera does not briefly use
        // the previous camera's still size.
        Resolution photoForCam = settingsManager.getPhotoResolutionForCamera(cameraId);
        currentConfig = applyVideoCapabilityConstraints(new CameraConfig.Builder(currentConfig)
                .setCameraId(cameraId)
                .setPhotoResolution(photoForCam)
                .build());
        if (currentConfig.getApiType() != CameraApiType.CAMERA1) {
            currentConfig = normalizePhotoResolutionForActiveCamera(currentConfig);
        }
        currentConfig = normalizeVideoResolutionForActiveMode(currentConfig);
        currentConfig = normalizeFrameRateForActiveMode(currentConfig);
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

    public boolean isVideoFocusSupported() {
        return currentStrategy != null && currentStrategy.isVideoFocusSupported();
    }

    public float getVideoMinimumFocusDistance() {
        return currentStrategy != null ? currentStrategy.getVideoMinimumFocusDistance() : 0f;
    }

    public float getVideoFocusDistance() {
        return currentStrategy != null ? currentStrategy.getVideoFocusDistance() : 0f;
    }

    public boolean isVideoManualFocusEnabled() {
        return currentStrategy != null && currentStrategy.isVideoManualFocusEnabled();
    }

    public boolean isVideoTapFocusActive() {
        return currentStrategy != null && currentStrategy.isVideoTapFocusActive();
    }

    public void setVideoFocusDistance(float focusDistance) {
        if (currentStrategy != null) {
            currentStrategy.setVideoFocusDistance(focusDistance);
        }
    }

    public void resetVideoFocus() {
        if (currentStrategy != null) {
            currentStrategy.resetVideoFocus();
        }
    }

    public boolean isFocusSupported() {
        boolean supported = currentStrategy != null && currentStrategy.isFocusSupported();
        Log.d(TAG, "isFocusSupported: " + supported);
        return supported;
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

    /**
     * Get a point-in-time snapshot of the active recorder for diagnostics UI.
     */
    public RecordingStats getRecordingStats() {
        return currentStrategy != null
                ? currentStrategy.getRecordingStats()
                : RecordingStats.idle("none");
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
        VideoCodec videoCodec = config.getVideoCodec() != null
                ? config.getVideoCodec()
                : VideoCodec.DEFAULT;
        boolean photoHdrEnabled = config.isPhotoHdrEnabled()
                && apiType == CameraApiType.CAMERA2
                && "0".equals(config.getCameraId())
                && isHdrPhotoResolution(config.getCameraId(), config.getPhotoResolution());

        if (apiType == CameraApiType.CAMERAX) {
            // CameraX 1.4.2 uses the system Recorder codec and cannot select HEVC.
            videoCodec = VideoCodec.H264;
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
                && videoResolution.equals(config.getResolution())
                && videoCodec == config.getVideoCodec()
                && photoHdrEnabled == config.isPhotoHdrEnabled()) {
            return config;
        }

        Log.d(TAG, "applyVideoCapabilityConstraints api=" + apiType
                + ", quality=" + quality
                + ", resolution=" + videoResolution);
        return new CameraConfig.Builder(config)
                .setApiType(apiType)
                .setQuality(quality)
                .setResolution(videoResolution)
                .setVideoCodec(videoCodec)
                .setPhotoHdrEnabled(photoHdrEnabled)
                .build();
    }

    private Quality qualityFromResolution(Resolution resolution) {
        if (resolution == null) {
            return Quality.DEFAULT;
        }
        int width = resolution.getWidth();
        int height = resolution.getHeight();
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
        this.currentConfig = normalizePhotoResolutionForActiveCamera(this.currentConfig);
        this.currentConfig = normalizeVideoResolutionForActiveMode(this.currentConfig);
        this.currentConfig = normalizeFrameRateForActiveMode(this.currentConfig);
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
        this.currentConfig = normalizePhotoResolutionForActiveCamera(this.currentConfig);
        this.currentConfig = normalizeVideoResolutionForActiveMode(this.currentConfig);
        this.currentConfig = normalizeFrameRateForActiveMode(this.currentConfig);
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
        if (currentConfig != null && currentConfig.getApiType() == CameraApiType.CAMERA1
                && currentStrategy != null) {
            List<Resolution> camera1Resolutions = currentStrategy.getSupportedResolutions();
            if (camera1Resolutions != null && !camera1Resolutions.isEmpty()) {
                return camera1Resolutions;
            }
        }
        List<Resolution> queried = queryCamera2Resolutions();
        if (!queried.isEmpty()) {
            return queried;
        }
        if (currentStrategy != null) {
            List<Resolution> strategyResolutions = currentStrategy.getSupportedResolutions();
            if (strategyResolutions != null && !strategyResolutions.isEmpty()) {
                return strategyResolutions;
            }
        }
        if (currentConfig != null && currentConfig.getResolution() != null) {
            return Collections.singletonList(currentConfig.getResolution());
        }
        return Collections.emptyList();
    }

    /**
     * Return still-photo sizes reported by the active camera's JPEG capability.
     * Unlike video resolution, still sizes are not restricted to the video's
     * selectable presets.
     */
    public List<Resolution> getSupportedPhotoResolutions() {
        if (currentConfig == null) {
            return Collections.emptyList();
        }
        if (currentConfig.getApiType() == CameraApiType.CAMERA1) {
            if (currentStrategy != null) {
                List<Resolution> camera1Resolutions = currentStrategy.getSupportedPhotoResolutions();
                if (camera1Resolutions != null && !camera1Resolutions.isEmpty()) {
                    return camera1Resolutions;
                }
            }
            return currentConfig.getPhotoResolution() != null
                    ? Collections.singletonList(currentConfig.getPhotoResolution())
                    : Collections.emptyList();
        }

        List<Resolution> queried = queryCamera2PhotoResolutions();
        if (!queried.isEmpty()) {
            return queried;
        }
        if (currentStrategy != null) {
            List<Resolution> strategyResolutions = currentStrategy.getSupportedPhotoResolutions();
            if (strategyResolutions != null && !strategyResolutions.isEmpty()) {
                return strategyResolutions;
            }
        }
        if (currentConfig.getPhotoResolution() != null) {
            return Collections.singletonList(currentConfig.getPhotoResolution());
        }
        return Collections.emptyList();
    }
    
    /**
     * Get supported frame rates
     */
    public List<Integer> getSupportedFrameRates() {
        return getSupportedFrameRates(currentConfig != null ? currentConfig.getResolution() : null);
    }

    /**
     * Query frame rates for a specific video size. The settings screen uses
     * this overload while the user is changing resolution, before the new
     * configuration has been staged.
     */
    public List<Integer> getSupportedFrameRates(Resolution resolution) {
        if (currentConfig != null && currentConfig.getApiType() == CameraApiType.CAMERA1
                && currentStrategy != null) {
            List<Integer> camera1Rates = normalizeFrameRates(currentStrategy.getSupportedFrameRates());
            if (!camera1Rates.isEmpty()) {
                return camera1Rates;
            }
        }
        List<Integer> cameraRates = queryCameraFrameRates(resolution);
        if (!cameraRates.isEmpty()) {
            return cameraRates;
        }
        if (currentStrategy != null) {
            List<Integer> strategyRates = normalizeFrameRates(currentStrategy.getSupportedFrameRates());
            if (!strategyRates.isEmpty()) {
                return strategyRates;
            }
        }
        int profileRate = queryCamcorderProfileFrameRate();
        if (profileRate > 0) {
            return Collections.singletonList(profileRate);
        }
        return Collections.emptyList();
    }

    /**
     * Select the currently saved frame rate when it is supported. For a new
     * configuration with no saved rate, use the highest value reported by the
     * camera for that resolution.
     */
    public int getPreferredFrameRate(Resolution resolution) {
        return getPreferredFrameRate(resolution,
                currentConfig != null ? currentConfig.getFrameRate() : 0);
    }

    private int getPreferredFrameRate(Resolution resolution, int requestedRate) {
        List<Integer> supportedRates = getSupportedFrameRates(resolution);
        if (supportedRates.isEmpty()) {
            return requestedRate > 0 ? requestedRate : 0;
        }
        if (requestedRate <= 0) {
            int profileRate = queryCamcorderProfileFrameRate();
            if (supportedRates.contains(profileRate)) {
                return profileRate;
            }
            return supportedRates.get(supportedRates.size() - 1);
        }

        int bestRate = supportedRates.get(0);
        int bestDistance = Math.abs(bestRate - requestedRate);

        for (Integer supportedRate : supportedRates) {
            if (supportedRate == null || supportedRate <= 0) {
                continue;
            }
            int distance = Math.abs(supportedRate - requestedRate);
            if (distance < bestDistance
                    || (distance == bestDistance && supportedRate > bestRate)) {
                bestDistance = distance;
                bestRate = supportedRate;
            }
        }
        return bestRate;
    }

    /** Return whether the selected size/rate pair is a Camera2 high-speed mode. */
    public boolean isHighSpeedVideoSupported(Resolution resolution, int frameRate) {
        if (resolution == null || frameRate <= 0 || currentConfig == null
                || currentConfig.getCameraId() == null) {
            return false;
        }
        try {
            android.hardware.camera2.CameraManager systemCameraManager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (systemCameraManager == null) {
                return false;
            }
            CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(
                    currentConfig.getCameraId());
            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null) {
                return false;
            }
            Size targetSize = new Size(resolution.getWidth(), resolution.getHeight());
            if (!containsSize(streamMap.getHighSpeedVideoSizes(), targetSize)) {
                return false;
            }
            Range<Integer>[] ranges = streamMap.getHighSpeedVideoFpsRangesFor(targetSize);
            if (ranges == null) {
                return false;
            }
            for (Range<Integer> range : ranges) {
                if (range != null && range.getUpper() == frameRate) {
                    return true;
                }
            }
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Failed to query high-speed video capability", e);
        }
        return false;
    }

    private List<Integer> queryCameraFrameRates(Resolution resolution) {
        if (currentConfig == null || currentConfig.getCameraId() == null) {
            return Collections.emptyList();
        }
        try {
            android.hardware.camera2.CameraManager systemCameraManager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (systemCameraManager == null) {
                return Collections.emptyList();
            }
            CameraCharacteristics characteristics =
                    systemCameraManager.getCameraCharacteristics(currentConfig.getCameraId());
            Range<Integer>[] ranges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            Set<Integer> rates = new TreeSet<>();
            Set<Integer> regularRates = new TreeSet<>();
            addFrameRatesFromRanges(regularRates, ranges);

            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size targetSize = resolution == null ? null
                    : new Size(resolution.getWidth(), resolution.getHeight());
            int maxByFrameDuration = Integer.MAX_VALUE;
            if (streamMap != null && targetSize != null
                    && containsSize(streamMap.getOutputSizes(MediaRecorder.class), targetSize)) {
                long minFrameDurationNs = streamMap.getOutputMinFrameDuration(
                        MediaRecorder.class, targetSize);
                if (minFrameDurationNs > 0) {
                    long maxByDuration = 1_000_000_000L / minFrameDurationNs;
                    maxByFrameDuration = (int) Math.min(Integer.MAX_VALUE, maxByDuration);
                }
            }
            if (maxByFrameDuration != Integer.MAX_VALUE) {
                for (Integer rate : new ArrayList<>(regularRates)) {
                    if (rate == null || rate > maxByFrameDuration) {
                        regularRates.remove(rate);
                    }
                }
            }
            rates.addAll(regularRates);

            // High-speed rates are exposed through the high-speed stream map
            // on some Camera2 HALs instead of the regular AE ranges.
            if (streamMap != null && targetSize != null
                    && containsSize(streamMap.getHighSpeedVideoSizes(), targetSize)) {
                addFrameRatesFromRanges(rates,
                        streamMap.getHighSpeedVideoFpsRangesFor(targetSize));
            }
            List<Integer> result = new ArrayList<>(rates);
            Log.d(TAG, "Camera " + currentConfig.getCameraId()
                    + " reported FPS ranges=" + java.util.Arrays.toString(ranges)
                    + ", supportedRates=" + result);
            return result;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to query Camera2 frame rates", e);
            return Collections.emptyList();
        }
    }

    private void addFrameRatesFromRanges(Set<Integer> rates, Range<Integer>[] ranges) {
        if (ranges == null) {
            return;
        }
        for (Range<Integer> range : ranges) {
            if (range == null || range.getLower() == null || range.getUpper() == null) {
                continue;
            }
            int lower = range.getLower();
            int upper = range.getUpper();
            if (lower > 0) {
                rates.add(lower);
            }
            if (upper > 0) {
                rates.add(upper);
            }
        }
    }

    private List<Integer> normalizeFrameRates(List<Integer> rates) {
        Set<Integer> normalized = new TreeSet<>();
        if (rates != null) {
            for (Integer rate : rates) {
                if (rate != null && rate > 0) {
                    normalized.add(rate);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private int queryCamcorderProfileFrameRate() {
        if (currentConfig == null || currentConfig.getCameraId() == null) {
            return 0;
        }
        try {
            int cameraIndex = Integer.parseInt(currentConfig.getCameraId());
            if (!CamcorderProfile.hasProfile(cameraIndex, CamcorderProfile.QUALITY_HIGH)) {
                return 0;
            }
            CamcorderProfile profile = CamcorderProfile.get(
                    cameraIndex, CamcorderProfile.QUALITY_HIGH);
            return profile != null && profile.videoFrameRate > 0 ? profile.videoFrameRate : 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to query CamcorderProfile frame rate", e);
            return 0;
        }
    }

    private List<Resolution> queryCamera2Resolutions() {
        if (currentConfig == null || currentConfig.getCameraId() == null) {
            return Collections.emptyList();
        }
        try {
            android.hardware.camera2.CameraManager systemCameraManager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (systemCameraManager == null) {
                return Collections.emptyList();
            }
            CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(
                    currentConfig.getCameraId());
            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null) {
                return Collections.emptyList();
            }

            Size[] recorderSizes = streamMap.getOutputSizes(MediaRecorder.class);
            Size[] jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG);
            if (recorderSizes == null || recorderSizes.length == 0) {
                recorderSizes = jpegSizes;
            }
            Set<String> jpegSizeKeys = toSizeKeys(jpegSizes);
            Set<String> added = new HashSet<>();
            List<Resolution> resolutions = new ArrayList<>();
            if (recorderSizes != null) {
                for (Size size : recorderSizes) {
                    if (size == null) {
                        continue;
                    }
                    String key = size.getWidth() + "x" + size.getHeight();
                    if (!jpegSizeKeys.isEmpty() && !jpegSizeKeys.contains(key)) {
                        continue;
                    }
                    if (added.add(key)) {
                        resolutions.add(Resolution.of(size.getWidth(), size.getHeight()));
                    }
                }
            }
            Collections.sort(resolutions, new Comparator<Resolution>() {
                @Override
                public int compare(Resolution left, Resolution right) {
                    long rightArea = (long) right.getWidth() * right.getHeight();
                    long leftArea = (long) left.getWidth() * left.getHeight();
                    int areaCompare = Long.compare(rightArea, leftArea);
                    if (areaCompare != 0) {
                        return areaCompare;
                    }
                    return Integer.compare(right.getWidth(), left.getWidth());
                }
            });
            return resolutions;
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Failed to query Camera2 supported resolutions", e);
            return Collections.emptyList();
        }
    }

    private List<Resolution> queryCamera2PhotoResolutions() {
        return currentConfig == null
                ? Collections.emptyList()
                : queryCamera2PhotoResolutions(currentConfig.getCameraId());
    }

    private List<Resolution> queryCamera2PhotoResolutions(String cameraId) {
        if (cameraId == null) {
            return Collections.emptyList();
        }
        try {
            android.hardware.camera2.CameraManager systemCameraManager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (systemCameraManager == null) {
                return Collections.emptyList();
            }
            CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(
                    cameraId);
            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null) {
                return Collections.emptyList();
            }

            List<Resolution> resolutions = new ArrayList<>();
            addPhotoResolutions(resolutions, streamMap.getOutputSizes(ImageFormat.JPEG));
            addPhotoResolutions(resolutions, streamMap.getHighResolutionOutputSizes(ImageFormat.JPEG));
            sortResolutionsByArea(resolutions);
            return resolutions;
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Failed to query Camera2 supported photo resolutions", e);
            return Collections.emptyList();
        }
    }

    private void addPhotoResolutions(List<Resolution> output, Size[] sizes) {
        if (sizes == null) {
            return;
        }
        for (Size size : sizes) {
            if (size == null) {
                continue;
            }
            Resolution resolution = Resolution.of(size.getWidth(), size.getHeight());
            if (!output.contains(resolution)) {
                output.add(resolution);
            }
        }
    }

    private void sortResolutionsByArea(List<Resolution> resolutions) {
        Collections.sort(resolutions, new Comparator<Resolution>() {
            @Override
            public int compare(Resolution left, Resolution right) {
                long rightArea = (long) right.getWidth() * right.getHeight();
                long leftArea = (long) left.getWidth() * left.getHeight();
                int areaCompare = Long.compare(rightArea, leftArea);
                if (areaCompare != 0) {
                    return areaCompare;
                }
                return Integer.compare(right.getWidth(), left.getWidth());
            }
        });
    }

    private Set<String> toSizeKeys(Size[] sizes) {
        Set<String> keys = new HashSet<>();
        if (sizes == null) {
            return keys;
        }
        for (Size size : sizes) {
            if (size != null) {
                keys.add(size.getWidth() + "x" + size.getHeight());
            }
        }
        return keys;
    }

    private boolean containsSize(Size[] sizes, Size target) {
        if (sizes == null || target == null) {
            return false;
        }
        for (Size size : sizes) {
            if (target.equals(size)) {
                return true;
            }
        }
        return false;
    }

    private CameraConfig normalizePhotoResolutionForActiveCamera(CameraConfig config) {
        if (config == null) {
            return null;
        }

        List<Resolution> supported = getSupportedPhotoResolutions();
        if (supported.isEmpty()) {
            return config;
        }

        Resolution current = config.getPhotoResolution();
        if (current != null && supported.contains(current)) {
            return config;
        }

        Resolution selected = supported.get(0);
        if (current != null) {
            long targetArea = (long) current.getWidth() * current.getHeight();
            long bestDistance = Long.MAX_VALUE;
            for (Resolution candidate : supported) {
                long area = (long) candidate.getWidth() * candidate.getHeight();
                long distance = Math.abs(area - targetArea);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    selected = candidate;
                }
            }
        }

        Log.d(TAG, "Normalize photo resolution " + current + " -> " + selected);
        return new CameraConfig.Builder(config)
                .setPhotoResolution(selected)
                .build();
    }

    public Resolution getHdrPhotoResolution() {
        if (currentConfig == null || currentConfig.getApiType() != CameraApiType.CAMERA2
                || !"0".equals(currentConfig.getCameraId())) {
            return null;
        }
        return queryHdrPhotoResolution(currentConfig.getCameraId());
    }

    private boolean isHdrPhotoResolution(String cameraId, Resolution resolution) {
        Resolution hdrResolution = queryHdrPhotoResolution(cameraId);
        return resolution != null && hdrResolution != null && hdrResolution.equals(resolution);
    }

    private Resolution queryHdrPhotoResolution(String cameraId) {
        if (cameraId == null) {
            return null;
        }
        try {
            android.hardware.camera2.CameraManager systemCameraManager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (systemCameraManager == null) {
                return null;
            }
            CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(cameraId);
            Size sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (sensorSize == null) {
                return null;
            }
            Resolution sensorResolution = Resolution.of(sensorSize.getWidth(), sensorSize.getHeight());
            return queryCamera2PhotoResolutions(cameraId).contains(sensorResolution)
                    ? sensorResolution : null;
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Failed to query HDR photo resolution", e);
            return null;
        }
    }

    private CameraConfig normalizeVideoResolutionForActiveMode(CameraConfig config) {
        if (config == null || config.getApiType() == CameraApiType.CAMERAX
                || !CaptureMode.normalize(config.getCaptureMode()).isVideo()) {
            return config;
        }

        List<Resolution> supported = getSupportedResolutions();
        if (supported.isEmpty()) {
            return config;
        }

        Resolution current = config.getResolution();
        if (current != null && supported.contains(current)) {
            return config;
        }

        long targetArea = current == null
                ? 0L : (long) current.getWidth() * current.getHeight();
        Resolution selected = supported.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (Resolution candidate : supported) {
            long area = (long) candidate.getWidth() * candidate.getHeight();
            long distance = Math.abs(area - targetArea);
            if (distance < bestDistance) {
                bestDistance = distance;
                selected = candidate;
            }
        }

        Log.d(TAG, "Normalize video resolution " + current + " -> " + selected);
        return new CameraConfig.Builder(config)
                .setResolution(selected)
                .setQuality(qualityFromResolution(selected))
                .build();
    }

    private CameraConfig normalizeFrameRateForActiveMode(CameraConfig config) {
        if (config == null) {
            return null;
        }
        Resolution targetResolution = CaptureMode.normalize(config.getCaptureMode()).isVideo()
                ? config.getResolution() : null;
        int preferredRate = getPreferredFrameRate(targetResolution, config.getFrameRate());
        if (config.getFrameRate() == preferredRate) {
            return config;
        }
        Log.d(TAG, "Normalize frame rate " + config.getFrameRate()
                + " -> " + preferredRate + ", mode=" + config.getCaptureMode()
                + ", resolution=" + config.getResolution());
        return new CameraConfig.Builder(config)
                .setFrameRate(preferredRate)
                .build();
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
        if (state == CameraState.OPENED && currentConfig != null) {
            CameraConfig normalized = normalizePhotoResolutionForActiveCamera(currentConfig);
            if (normalized != currentConfig) {
                currentConfig = normalized;
                settingsManager.saveCameraConfig(currentConfig);
                if (currentStrategy != null) {
                    currentStrategy.bindConfiguration(currentConfig);
                }
            }
        }
        CameraState previousState = stateManager.getCurrentState();
        // Camera2 uses a repeated OPENED event to signal that its capture
        // pipeline was rebuilt. Preserve that event for the UI so it can
        // recreate the preview and finish a mode switch.
        if (state == CameraState.OPENED && previousState == CameraState.OPENED) {
            stateManager.forceNotifyState(state);
            return;
        }
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
