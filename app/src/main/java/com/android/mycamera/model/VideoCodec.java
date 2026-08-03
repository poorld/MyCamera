package com.android.mycamera.model;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;

/** Video codec options supported by the MediaRecorder-based pipelines. */
public enum VideoCodec {
    H264("H.264", "video/avc", MediaRecorder.VideoEncoder.H264),
    H265("H.265", "video/hevc", MediaRecorder.VideoEncoder.HEVC);

    public static final VideoCodec DEFAULT = H264;

    private final String displayName;
    private final String mimeType;
    private final int mediaRecorderEncoder;

    VideoCodec(String displayName, String mimeType, int mediaRecorderEncoder) {
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.mediaRecorderEncoder = mediaRecorderEncoder;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getMediaRecorderEncoder() {
        return mediaRecorderEncoder;
    }

    /**
     * Checks whether the device advertises an encoder for this MIME type.
     * MediaRecorder still performs the final validation for a camera profile.
     */
    public boolean isSupportedOnDevice() {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (!codecInfo.isEncoder()) {
                    continue;
                }
                for (String supportedType : codecInfo.getSupportedTypes()) {
                    if (mimeType.equalsIgnoreCase(supportedType)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Keep H.264 as the safe fallback when codec enumeration is unavailable.
        }
        return false;
    }

    public static VideoCodec resolveForMediaRecorder(VideoCodec requested) {
        VideoCodec candidate = requested != null ? requested : DEFAULT;
        if (candidate == H265 && !candidate.isSupportedOnDevice()) {
            return H264;
        }
        return candidate;
    }

    public static VideoCodec fromName(String name) {
        if (name == null) {
            return DEFAULT;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
