package com.android.mycamera.camera.config;

import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.PhotoResolution;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.model.VideoBitrate;
import com.android.mycamera.model.VideoCodec;

import java.io.File;

/**
 * Camera configuration data class.
 * Video uses {@link #resolution}/{@link #quality}; still capture uses {@link #photoResolution}.
 * {@link #captureMode} selects the active pipeline (photo JPEG vs video encoder consumer).
 */
public class CameraConfig {
    private final String cameraId;
    private final Resolution resolution;
    private final PhotoResolution photoResolution;
    private final CaptureMode captureMode;
    private final int frameRate;
    private final Quality quality;
    private final VideoBitrate videoBitrate;
    private final VideoCodec videoCodec;
    private final boolean audioEnabled;
    private final File saveLocation;
    private final CameraApiType apiType;
    private final boolean backgroundRecordingEnabled;
    private final boolean backgroundReviewEnabled;
    private final boolean lowMemoryModeEnabled;

    private CameraConfig(Builder builder) {
        this.cameraId = builder.cameraId;
        this.resolution = builder.resolution;
        this.photoResolution = builder.photoResolution;
        this.captureMode = builder.captureMode;
        this.frameRate = builder.frameRate;
        this.quality = builder.quality;
        this.videoBitrate = builder.videoBitrate;
        this.videoCodec = builder.videoCodec;
        this.audioEnabled = builder.audioEnabled;
        this.saveLocation = builder.saveLocation;
        this.apiType = builder.apiType;
        this.backgroundRecordingEnabled = builder.backgroundRecordingEnabled;
        this.backgroundReviewEnabled = builder.backgroundReviewEnabled;
        this.lowMemoryModeEnabled = builder.lowMemoryModeEnabled;
    }

    public String getCameraId() {
        return cameraId;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public PhotoResolution getPhotoResolution() {
        return photoResolution;
    }

    public CaptureMode getCaptureMode() {
        return captureMode;
    }

    public int getFrameRate() {
        return frameRate;
    }

    public Quality getQuality() {
        return quality;
    }

    public VideoBitrate getVideoBitrate() {
        return videoBitrate;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
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

    public boolean isLowMemoryModeEnabled() {
        return lowMemoryModeEnabled;
    }

    /**
     * Builder pattern for CameraConfig
     */
    public static class Builder {
        private String cameraId = "0";
        private Resolution resolution = Resolution.FULL_HD_1080P; // Camera2 default video 1920x1080
        private PhotoResolution photoResolution = PhotoResolution.MP_12; // product default still 12M
        private CaptureMode captureMode = CaptureMode.PHOTO;
        private int frameRate = 30;
        private Quality quality = Quality.DEFAULT; // CameraX default FHD
        private VideoBitrate videoBitrate = VideoBitrate.DEFAULT;
        private VideoCodec videoCodec = VideoCodec.DEFAULT;
        private boolean audioEnabled = true;
        private File saveLocation = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera");
        private CameraApiType apiType = CameraApiType.CAMERA2; // default Camera2 (CameraX resolution switching is unreliable)
        private boolean backgroundRecordingEnabled = false;
        private boolean backgroundReviewEnabled = false;
        private boolean lowMemoryModeEnabled = true;

        public Builder() {
        }

        public Builder(CameraConfig config) {
            this.cameraId = config.cameraId;
            this.resolution = config.resolution;
            this.photoResolution = config.photoResolution;
            this.captureMode = config.captureMode;
            this.frameRate = config.frameRate;
            this.quality = config.quality;
            this.videoBitrate = config.videoBitrate;
            this.videoCodec = config.videoCodec;
            this.audioEnabled = config.audioEnabled;
            this.saveLocation = config.saveLocation;
            this.apiType = config.apiType;
            this.backgroundRecordingEnabled = config.backgroundRecordingEnabled;
            this.backgroundReviewEnabled = config.backgroundReviewEnabled;
            this.lowMemoryModeEnabled = config.lowMemoryModeEnabled;
        }

        public Builder setCameraId(String cameraId) {
            this.cameraId = cameraId;
            return this;
        }

        public Builder setResolution(Resolution resolution) {
            this.resolution = resolution;
            return this;
        }

        public Builder setPhotoResolution(PhotoResolution photoResolution) {
            this.photoResolution = photoResolution != null ? photoResolution : PhotoResolution.DEFAULT;
            return this;
        }

        public Builder setCaptureMode(CaptureMode captureMode) {
            this.captureMode = CaptureMode.normalize(captureMode);
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

        public Builder setVideoBitrate(VideoBitrate videoBitrate) {
            this.videoBitrate = videoBitrate != null ? videoBitrate : VideoBitrate.DEFAULT;
            return this;
        }

        public Builder setVideoCodec(VideoCodec videoCodec) {
            this.videoCodec = videoCodec != null ? videoCodec : VideoCodec.DEFAULT;
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

        public Builder setLowMemoryModeEnabled(boolean lowMemoryModeEnabled) {
            this.lowMemoryModeEnabled = lowMemoryModeEnabled;
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
                ", photoResolution=" + photoResolution +
                ", captureMode=" + captureMode +
                ", frameRate=" + frameRate +
                ", quality=" + quality +
                ", videoBitrate=" + videoBitrate +
                ", videoCodec=" + videoCodec +
                ", audioEnabled=" + audioEnabled +
                ", saveLocation=" + saveLocation +
                ", apiType=" + apiType +
                ", backgroundRecordingEnabled=" + backgroundRecordingEnabled +
                ", backgroundReviewEnabled=" + backgroundReviewEnabled +
                ", lowMemoryModeEnabled=" + lowMemoryModeEnabled +
                '}';
    }
}
