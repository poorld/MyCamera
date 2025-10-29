package com.android.mycamera.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;

import java.io.File;

/**
 * Utility class for managing camera settings persistence
 */
public class SettingsManager {
    
    private static final String PREF_CAMERA_API = "camera_api";
    private static final String PREF_RESOLUTION = "resolution";
    private static final String PREF_FRAME_RATE = "frame_rate";
    private static final String PREF_QUALITY = "quality";
    private static final String PREF_AUDIO_ENABLED = "audio_enabled";
    private static final String PREF_SAVE_LOCATION = "save_location";
    private static final String PREF_CAMERA_ID = "camera_id";
    private static final String PREF_BACKGROUND_RECORDING = "background_recording";
    
    private final SharedPreferences preferences;
    
    public SettingsManager(Context context) {
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
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
        editor.putBoolean(PREF_AUDIO_ENABLED, config.isAudioEnabled());
        editor.putString(PREF_SAVE_LOCATION, config.getSaveLocation().getAbsolutePath());
        editor.putString(PREF_CAMERA_ID, config.getCameraId());
        editor.putBoolean(PREF_BACKGROUND_RECORDING, config.isBackgroundRecordingEnabled());
        
        editor.apply();
    }
    
    /**
     * Load camera configuration
     */
    public CameraConfig loadCameraConfig() {
        CameraApiType apiType = CameraApiType.valueOf(
                preferences.getString(PREF_CAMERA_API, CameraApiType.CAMERAX.name())
        );
        
        Resolution resolution = Resolution.fromString(
                preferences.getString(PREF_RESOLUTION, Resolution.FULL_HD_1080P.toString())
        );
        
        int frameRate = preferences.getInt(PREF_FRAME_RATE, 30);
        
        Quality quality = Quality.valueOf(
                preferences.getString(PREF_QUALITY, Quality.FULL_HD.name())
        );
        
        boolean audioEnabled = preferences.getBoolean(PREF_AUDIO_ENABLED, true);
        
        String saveLocationPath = preferences.getString(PREF_SAVE_LOCATION, 
                new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/Camera").getAbsolutePath());
        File saveLocation = new File(saveLocationPath);
        
        String cameraId = preferences.getString(PREF_CAMERA_ID, "0");
        
        boolean backgroundRecordingEnabled = preferences.getBoolean(PREF_BACKGROUND_RECORDING, false);
        
        return new CameraConfig.Builder()
                .setApiType(apiType)
                .setResolution(resolution)
                .setFrameRate(frameRate)
                .setQuality(quality)
                .setAudioEnabled(audioEnabled)
                .setSaveLocation(saveLocation)
                .setCameraId(cameraId)
                .setBackgroundRecordingEnabled(backgroundRecordingEnabled)
                .build();
    }
    
    /**
     * Get camera API type
     */
    public CameraApiType getCameraApiType() {
        return CameraApiType.valueOf(
                preferences.getString(PREF_CAMERA_API, CameraApiType.CAMERAX.name())
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
    
    /**
     * Check if background recording is enabled
     */
    public boolean isBackgroundRecordingEnabled() {
        return preferences.getBoolean(PREF_BACKGROUND_RECORDING, false);
    }
    
    /**
     * Set background recording enabled
     */
    public void setBackgroundRecordingEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_BACKGROUND_RECORDING, enabled).apply();
    }
    
    /**
     * Clear all settings
     */
    public void clearAllSettings() {
        preferences.edit().clear().apply();
    }
}