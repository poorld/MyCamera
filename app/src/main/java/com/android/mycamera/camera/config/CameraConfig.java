package com.android.mycamera.camera.config;

import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;

import java.io.File;

/**
 * Camera configuration data class
 */
public class CameraConfig {
    private final String cameraId;
    private final Resolution resolution;
    private final int frameRate;
    private final Quality quality;
    private final boolean audioEnabled;
    private final File saveLocation;
    private final CameraApiType apiType;
    private final boolean backgroundRecordingEnabled;
    private final boolean backgroundReviewEnabled;

    private CameraConfig(Builder builder) {
        this.cameraId = builder.cameraId;
        this.resolution = builder.resolution;
        this.frameRate = builder.frameRate;
        this.quality = builder.quality;
        this.audioEnabled = builder.audioEnabled;
        this.saveLocation = builder.saveLocation;
        this.apiType = builder.apiType;
        this.backgroundRecordingEnabled = builder.backgroundRecordingEnabled;
        this.backgroundReviewEnabled = builder.backgroundReviewEnabled;
    }
    
    public String getCameraId() {
        return cameraId;
    }
    
    public Resolution getResolution() {
        return resolution;
    }
    
    public int getFrameRate() {
        return frameRate;
    }
    
    public Quality getQuality() {
        return quality;
    }
    
    public boolean isAudioEnabled() {
        return audioEnabled;
    }
    
    public File getSaveLocation() {
        return saveLocation;
    }
    
    public CameraApiType getApiType() {
        return apiType;
    }
    
    public boolean isBackgroundRecordingEnabled() {
        return backgroundRecordingEnabled;
    }
    public boolean isBackgroundReviewEnabled() {
        return backgroundReviewEnabled;
    }
    
    /**
     * Builder pattern for CameraConfig
     */
    public static class Builder {
        private String cameraId = "0";
        private Resolution resolution = Resolution.FULL_HD_1080P;
        private int frameRate = 30;
        private Quality quality = Quality.FULL_HD;
        private boolean audioEnabled = true;
        private File saveLocation = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/Camera");
        private CameraApiType apiType = CameraApiType.CAMERAX;
        private boolean backgroundRecordingEnabled = false;
        private boolean backgroundReviewEnabled = false;

        /**
         * Default constructor for Builder
         */
        public Builder() {
            // Default values are already set above
        }
        
        /**
         * Copy constructor for Builder
         */
        public Builder(CameraConfig config) {
            this.cameraId = config.cameraId;
            this.resolution = config.resolution;
            this.frameRate = config.frameRate;
            this.quality = config.quality;
            this.audioEnabled = config.audioEnabled;
            this.saveLocation = config.saveLocation;
            this.apiType = config.apiType;
            this.backgroundRecordingEnabled = config.backgroundRecordingEnabled;
            this.backgroundReviewEnabled = config.backgroundReviewEnabled;
        }
        
        public Builder setCameraId(String cameraId) {
            this.cameraId = cameraId;
            return this;
        }
        
        public Builder setResolution(Resolution resolution) {
            this.resolution = resolution;
            return this;
        }
        
        public Builder setFrameRate(int frameRate) {
            this.frameRate = frameRate;
            return this;
        }
        
        public Builder setQuality(Quality quality) {
            this.quality = quality;
            return this;
        }
        
        public Builder setAudioEnabled(boolean audioEnabled) {
            this.audioEnabled = audioEnabled;
            return this;
        }
        
        public Builder setSaveLocation(File saveLocation) {
            this.saveLocation = saveLocation;
            return this;
        }
        
        public Builder setApiType(CameraApiType apiType) {
            this.apiType = apiType;
            return this;
        }
        
        public Builder setBackgroundRecordingEnabled(boolean backgroundRecordingEnabled) {
            this.backgroundRecordingEnabled = backgroundRecordingEnabled;
            return this;
        }
        public Builder setBackgroundReviewEnabled(boolean backgroundReviewEnabled) {
            this.backgroundReviewEnabled = backgroundReviewEnabled;
            return this;
        }

        public CameraConfig build() {
            return new CameraConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "CameraConfig{" +
                "cameraId='" + cameraId + '\'' +
                ", resolution=" + resolution +
                ", frameRate=" + frameRate +
                ", quality=" + quality +
                ", audioEnabled=" + audioEnabled +
                ", saveLocation=" + saveLocation +
                ", apiType=" + apiType +
                ", backgroundRecordingEnabled=" + backgroundRecordingEnabled +
                ", backgroundReviewEnabled=" + backgroundReviewEnabled +
                '}';
    }
}
