package com.android.mycamera.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.PhotoResolution;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.model.VideoBitrate;
import com.android.mycamera.model.VideoCodec;

import java.io.File;

/**
 * Utility class for managing camera settings persistence
 */
public class SettingsManager {
    
    private static final String PREF_CAMERA_API = "camera_api";
    private static final String PREF_RESOLUTION = "resolution";
    private static final String PREF_FRAME_RATE = "frame_rate";
    private static final String PREF_QUALITY = "quality";
    private static final String PREF_VIDEO_BITRATE = "video_bitrate";
    private static final String PREF_VIDEO_CODEC = "video_codec";
    private static final String PREF_PHOTO_RESOLUTION = "photo_resolution";
    /** One-time: enforce product default still size 12M. */
    private static final String PREF_PHOTO_DEFAULT_12M_APPLIED = "photo_default_12m_applied_v1";
    private static final String PREF_AUDIO_ENABLED = "audio_enabled";
    private static final String PREF_SAVE_LOCATION = "save_location";
    private static final String PREF_CAMERA_ID = "camera_id";
    private static final String PREF_BACKGROUND_REVIEW = "background_review";
    private static final String PREF_BACKGROUND_RECORDING = "background_recording";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String PREF_APP_LANGUAGE = "app_language";
    private static final String PREF_CAMERA2_CUSTOM_RESOLUTION = "camera2_custom_resolution";
    private static final String PREF_SHOW_API_SWITCHER = "show_api_switcher";

    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_CHINESE = "zh";
    public static final String LANGUAGE_ENGLISH = "en";

    private final SharedPreferences preferences;
    private final Context context;
    
    public SettingsManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
    }
    
    /**
     * Save camera configuration
     */
    public void saveCameraConfig(CameraConfig config) {
        SharedPreferences.Editor editor = preferences.edit();
        
        editor.putString(PREF_CAMERA_API, config.getApiType().name());
        editor.putString(PREF_RESOLUTION, config.getResolution().toString());
        editor.putInt(PREF_FRAME_RATE, config.getFrameRate());
        editor.putString(PREF_QUALITY, config.getQuality().name());
        editor.putString(PREF_VIDEO_BITRATE, config.getVideoBitrate().name());
        editor.putString(PREF_VIDEO_CODEC, config.getVideoCodec().name());
        editor.putString(PREF_PHOTO_RESOLUTION, config.getPhotoResolution().name());
        // Remember still size per camera (cam0 high-res vs cam1 4M/3M/...).
        if (config.getCameraId() != null) {
            editor.putString(PREF_PHOTO_RESOLUTION + "_cam_" + config.getCameraId(),
                    config.getPhotoResolution().name());
        }
        editor.putBoolean(PREF_AUDIO_ENABLED, config.isAudioEnabled());
        editor.putString(PREF_SAVE_LOCATION, config.getSaveLocation().getAbsolutePath());
        editor.putString(PREF_CAMERA_ID, config.getCameraId());
        editor.putBoolean(PREF_BACKGROUND_REVIEW, config.isBackgroundReviewEnabled());
        editor.putBoolean(PREF_BACKGROUND_RECORDING, config.isBackgroundRecordingEnabled());

        editor.apply();
    }
    
    /**
     * Load camera configuration
     */
    public CameraConfig loadCameraConfig() {
        CameraApiType apiType = CameraApiType.valueOf(
                preferences.getString(PREF_CAMERA_API, CameraApiType.CAMERA2.name())
        );
        
        Resolution resolution = Resolution.fromString(
                preferences.getString(PREF_RESOLUTION, Resolution.FULL_HD_1080P.toString())
        );
        if (apiType != CameraApiType.CAMERA2 && !resolution.isFixed()) {
            resolution = Resolution.FULL_HD_1080P;
        }
        
        int frameRate = preferences.getInt(PREF_FRAME_RATE, 30);
        
        Quality quality = Quality.normalizeSelectable(Quality.valueOf(preferences.getString(PREF_QUALITY, Quality.DEFAULT.name())));
        VideoBitrate videoBitrate = VideoBitrate.fromName(
                preferences.getString(PREF_VIDEO_BITRATE, VideoBitrate.DEFAULT.name()));
        VideoCodec videoCodec = VideoCodec.fromName(
                preferences.getString(PREF_VIDEO_CODEC, VideoCodec.DEFAULT.name()));

        boolean audioEnabled = preferences.getBoolean(PREF_AUDIO_ENABLED, true);
        
        File defaultSaveLocation = CameraUtils.createCameraDirectory(context);
        String saveLocationPath = preferences.getString(PREF_SAVE_LOCATION, defaultSaveLocation.getAbsolutePath());
        // Migrate old app-private Android/data path to public DCIM/Camera.
        if (CameraUtils.isLegacyAppMediaPath(saveLocationPath)) {
            saveLocationPath = defaultSaveLocation.getAbsolutePath();
            preferences.edit().putString(PREF_SAVE_LOCATION, saveLocationPath).apply();
        }
        File saveLocation = new File(saveLocationPath);
        if (!saveLocation.exists()) {
            // Ensure directory exists; fall back to DCIM/Camera if missing parent perms.
            if (!saveLocation.mkdirs()) {
                saveLocation = defaultSaveLocation;
            }
        }
        
        String cameraId = preferences.getString(PREF_CAMERA_ID, "0");

        // cam0 default 12M; cam1 default 4M(16:9). Prefer per-camera saved value.
        if (!preferences.getBoolean(PREF_PHOTO_DEFAULT_12M_APPLIED, false)) {
            preferences.edit()
                    .putString(PREF_PHOTO_RESOLUTION, PhotoResolution.MP_12.name())
                    .putString(PREF_PHOTO_RESOLUTION + "_cam_0", PhotoResolution.MP_12.name())
                    .putString(PREF_PHOTO_RESOLUTION + "_cam_1", PhotoResolution.DEFAULT_CAM1.name())
                    .putBoolean(PREF_PHOTO_DEFAULT_12M_APPLIED, true)
                    .apply();
        }
        String perCamKey = PREF_PHOTO_RESOLUTION + "_cam_" + cameraId;
        String photoName = preferences.getString(perCamKey, null);
        if (photoName == null || photoName.trim().isEmpty()) {
            photoName = preferences.getString(
                    PREF_PHOTO_RESOLUTION,
                    PhotoResolution.defaultForCamera(cameraId).name());
        }
        PhotoResolution photoResolution = PhotoResolution.normalize(
                PhotoResolution.fromName(photoName), cameraId);
        
        boolean backgroundReviewEnabled = preferences.getBoolean(PREF_BACKGROUND_REVIEW, false);
        boolean backgroundRecordingEnabled = preferences.getBoolean(PREF_BACKGROUND_RECORDING, false);

        return new CameraConfig.Builder()
                .setApiType(apiType)
                .setResolution(resolution)
                .setFrameRate(frameRate)
                .setQuality(quality)
                .setVideoBitrate(videoBitrate)
                .setVideoCodec(videoCodec)
                .setPhotoResolution(photoResolution)
                .setAudioEnabled(audioEnabled)
                .setSaveLocation(saveLocation)
                .setCameraId(cameraId)
                .setBackgroundReviewEnabled(backgroundReviewEnabled)
                .setBackgroundRecordingEnabled(backgroundRecordingEnabled)
                .build();
    }
    
    /**
     * Get camera API type
     */
    public CameraApiType getCameraApiType() {
        return CameraApiType.valueOf(
                preferences.getString(PREF_CAMERA_API, CameraApiType.CAMERA2.name())
        );
    }
    
    /**
     * Set camera API type
     */
    public void setCameraApiType(CameraApiType apiType) {
        preferences.edit().putString(PREF_CAMERA_API, apiType.name()).apply();
    }
    
    /**
     * Get resolution
     */
    public Resolution getResolution() {
        return Resolution.fromString(
                preferences.getString(PREF_RESOLUTION, Resolution.FULL_HD_1080P.toString())
        );
    }
    
    /**
     * Set resolution
     */
    public void setResolution(Resolution resolution) {
        preferences.edit().putString(PREF_RESOLUTION, resolution.toString()).apply();
    }
    
    /**
     * Get frame rate
     */
    public int getFrameRate() {
        return preferences.getInt(PREF_FRAME_RATE, 30);
    }
    
    /**
     * Set frame rate
     */
    public void setFrameRate(int frameRate) {
        preferences.edit().putInt(PREF_FRAME_RATE, frameRate).apply();
    }
    
    /**
     * Get camera ID
     */
    public String getCameraId() {
        return preferences.getString(PREF_CAMERA_ID, "0");
    }
    
    /**
     * Set camera ID
     */
    public void setCameraId(String cameraId) {
        preferences.edit().putString(PREF_CAMERA_ID, cameraId).apply();
    }

    public PhotoResolution getPhotoResolutionForCamera(String cameraId) {
        String key = PREF_PHOTO_RESOLUTION + "_cam_" + (cameraId == null ? "0" : cameraId);
        String name = preferences.getString(key, null);
        if (name == null || name.trim().isEmpty()) {
            name = PhotoResolution.defaultForCamera(cameraId).name();
        }
        return PhotoResolution.normalize(PhotoResolution.fromName(name), cameraId);
    }

    public void setPhotoResolutionForCamera(String cameraId, PhotoResolution photoResolution) {
        PhotoResolution normalized = PhotoResolution.normalize(photoResolution, cameraId);
        String key = PREF_PHOTO_RESOLUTION + "_cam_" + (cameraId == null ? "0" : cameraId);
        preferences.edit()
                .putString(key, normalized.name())
                .putString(PREF_PHOTO_RESOLUTION, normalized.name())
                .apply();
    }
    
    /**
     * Check if background recording is enabled
     */
    public boolean isBackgroundRecordingEnabled() {
        return preferences.getBoolean(PREF_BACKGROUND_RECORDING, false);
    }

    public boolean isBackgroundReviewEnabled() {
        return preferences.getBoolean(PREF_BACKGROUND_REVIEW, false);
    }
    
    /**
     * Set background recording enabled
     */
    public void setBackgroundRecordingEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_BACKGROUND_RECORDING, enabled).apply();
    }

    public boolean isKeepScreenOnEnabled() {
        return preferences.getBoolean(PREF_KEEP_SCREEN_ON, false);
    }

    public void setKeepScreenOnEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_KEEP_SCREEN_ON, enabled).apply();
    }

    public boolean isCamera2CustomResolutionEnabled() {
        return preferences.getBoolean(PREF_CAMERA2_CUSTOM_RESOLUTION, false);
    }

    public void setCamera2CustomResolutionEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_CAMERA2_CUSTOM_RESOLUTION, enabled).apply();
    }

    public boolean isShowApiSwitcherEnabled() {
        return preferences.getBoolean(PREF_SHOW_API_SWITCHER, false);
    }

    public void setShowApiSwitcherEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_SHOW_API_SWITCHER, enabled).apply();
    }

    public String getAppLanguage() {
        String language = preferences.getString(PREF_APP_LANGUAGE, LANGUAGE_SYSTEM);
        if (LANGUAGE_CHINESE.equals(language) || LANGUAGE_ENGLISH.equals(language)) {
            return language;
        }
        return LANGUAGE_SYSTEM;
    }

    public void setAppLanguage(String language) {
        if (!LANGUAGE_CHINESE.equals(language) && !LANGUAGE_ENGLISH.equals(language)) {
            language = LANGUAGE_SYSTEM;
        }
        preferences.edit().putString(PREF_APP_LANGUAGE, language).apply();
    }
    
    /**
     * Clear all settings
     */
    public void clearAllSettings() {
        preferences.edit().clear().apply();
    }
}
