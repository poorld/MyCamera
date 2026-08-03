package com.android.mycamera.camera.strategy;

import com.android.mycamera.model.VideoCodec;

import java.io.File;

/**
 * A point-in-time snapshot of the active video recorder.
 *
 * The values are deliberately strategy-neutral so the debug screen can show
 * useful information even when the selected camera API does not expose every
 * low-level counter.
 */
public final class RecordingStats {
    private final boolean recording;
    private final boolean stopping;
    private final boolean rotatingSegment;
    private final String strategyName;
    private final VideoCodec requestedCodec;
    private final VideoCodec appliedCodec;
    private final long targetBitrate;
    private final long profileBitrate;
    private final int captureFps;
    private final int playbackFps;
    private final int width;
    private final int height;
    private final boolean audioEnabled;
    private final long recordingStartElapsedMs;
    private final long segmentStartElapsedMs;
    private final int segmentIndex;
    private final long frameCount;
    private final long lastFrameTimestampNs;
    private final long segmentDurationMs;
    private final File outputFile;
    private final String lastError;
    private final boolean videoFocusSupported;
    private final boolean videoManualFocusEnabled;
    private boolean videoTapFocusActive;
    private final float videoFocusDistance;
    private final float videoMinimumFocusDistance;
    private final int requestedVideoAfMode;
    private final int reportedVideoAfMode;
    private final float reportedVideoFocusDistance;

    public RecordingStats(boolean recording,
                          boolean stopping,
                          boolean rotatingSegment,
                          String strategyName,
                          VideoCodec requestedCodec,
                          VideoCodec appliedCodec,
                          long targetBitrate,
                          long profileBitrate,
                          int captureFps,
                          int playbackFps,
                          int width,
                          int height,
                          boolean audioEnabled,
                          long recordingStartElapsedMs,
                          long segmentStartElapsedMs,
                          int segmentIndex,
                          long frameCount,
                          long lastFrameTimestampNs,
                          long segmentDurationMs,
                          File outputFile,
                          String lastError) {
        this(recording, stopping, rotatingSegment, strategyName, requestedCodec, appliedCodec,
                targetBitrate, profileBitrate, captureFps, playbackFps, width, height,
                audioEnabled, recordingStartElapsedMs, segmentStartElapsedMs, segmentIndex,
                frameCount, lastFrameTimestampNs, segmentDurationMs, outputFile, lastError,
                false, false, 0f, 0f, -1, -1, Float.NaN);
    }

    public RecordingStats(boolean recording,
                          boolean stopping,
                          boolean rotatingSegment,
                          String strategyName,
                          VideoCodec requestedCodec,
                          VideoCodec appliedCodec,
                          long targetBitrate,
                          long profileBitrate,
                          int captureFps,
                          int playbackFps,
                          int width,
                          int height,
                          boolean audioEnabled,
                          long recordingStartElapsedMs,
                          long segmentStartElapsedMs,
                          int segmentIndex,
                          long frameCount,
                          long lastFrameTimestampNs,
                          long segmentDurationMs,
                          File outputFile,
                          String lastError,
                          boolean videoFocusSupported,
                          boolean videoManualFocusEnabled,
                          float videoFocusDistance,
                          float videoMinimumFocusDistance,
                          int requestedVideoAfMode,
                          int reportedVideoAfMode,
                          float reportedVideoFocusDistance) {
        this.recording = recording;
        this.stopping = stopping;
        this.rotatingSegment = rotatingSegment;
        this.strategyName = strategyName;
        this.requestedCodec = requestedCodec;
        this.appliedCodec = appliedCodec;
        this.targetBitrate = targetBitrate;
        this.profileBitrate = profileBitrate;
        this.captureFps = captureFps;
        this.playbackFps = playbackFps;
        this.width = width;
        this.height = height;
        this.audioEnabled = audioEnabled;
        this.recordingStartElapsedMs = recordingStartElapsedMs;
        this.segmentStartElapsedMs = segmentStartElapsedMs;
        this.segmentIndex = segmentIndex;
        this.frameCount = frameCount;
        this.lastFrameTimestampNs = lastFrameTimestampNs;
        this.segmentDurationMs = segmentDurationMs;
        this.outputFile = outputFile;
        this.lastError = lastError;
        this.videoFocusSupported = videoFocusSupported;
        this.videoManualFocusEnabled = videoManualFocusEnabled;
        this.videoTapFocusActive = false;
        this.videoFocusDistance = videoFocusDistance;
        this.videoMinimumFocusDistance = videoMinimumFocusDistance;
        this.requestedVideoAfMode = requestedVideoAfMode;
        this.reportedVideoAfMode = reportedVideoAfMode;
        this.reportedVideoFocusDistance = reportedVideoFocusDistance;
    }

    public RecordingStats(boolean recording,
                          boolean stopping,
                          boolean rotatingSegment,
                          String strategyName,
                          VideoCodec requestedCodec,
                          VideoCodec appliedCodec,
                          long targetBitrate,
                          long profileBitrate,
                          int captureFps,
                          int playbackFps,
                          int width,
                          int height,
                          boolean audioEnabled,
                          long recordingStartElapsedMs,
                          long segmentStartElapsedMs,
                          int segmentIndex,
                          long frameCount,
                          long lastFrameTimestampNs,
                          long segmentDurationMs,
                          File outputFile,
                          String lastError,
                          boolean videoFocusSupported,
                          boolean videoManualFocusEnabled,
                          float videoFocusDistance,
                          float videoMinimumFocusDistance,
                          int requestedVideoAfMode,
                          int reportedVideoAfMode,
                          float reportedVideoFocusDistance,
                          boolean videoTapFocusActive) {
        this(recording, stopping, rotatingSegment, strategyName, requestedCodec, appliedCodec,
                targetBitrate, profileBitrate, captureFps, playbackFps, width, height,
                audioEnabled, recordingStartElapsedMs, segmentStartElapsedMs, segmentIndex,
                frameCount, lastFrameTimestampNs, segmentDurationMs, outputFile, lastError,
                videoFocusSupported, videoManualFocusEnabled, videoFocusDistance,
                videoMinimumFocusDistance, requestedVideoAfMode, reportedVideoAfMode,
                reportedVideoFocusDistance);
        this.videoTapFocusActive = videoTapFocusActive;
    }

    public static RecordingStats idle(String strategyName) {
        return new RecordingStats(false, false, false, strategyName,
                VideoCodec.DEFAULT, VideoCodec.DEFAULT, 0L, 0L, 0, 0,
                0, 0, false, 0L, 0L, 0, 0L, 0L,
                10 * 60 * 1000L, null, "");
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isStopping() {
        return stopping;
    }

    public boolean isRotatingSegment() {
        return rotatingSegment;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public VideoCodec getRequestedCodec() {
        return requestedCodec;
    }

    public VideoCodec getAppliedCodec() {
        return appliedCodec;
    }

    public long getTargetBitrate() {
        return targetBitrate;
    }

    public long getProfileBitrate() {
        return profileBitrate;
    }

    public int getCaptureFps() {
        return captureFps;
    }

    public int getPlaybackFps() {
        return playbackFps;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public long getRecordingStartElapsedMs() {
        return recordingStartElapsedMs;
    }

    public long getSegmentStartElapsedMs() {
        return segmentStartElapsedMs;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public long getFrameCount() {
        return frameCount;
    }

    public long getLastFrameTimestampNs() {
        return lastFrameTimestampNs;
    }

    public long getSegmentDurationMs() {
        return segmentDurationMs;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isVideoFocusSupported() {
        return videoFocusSupported;
    }

    public boolean isVideoManualFocusEnabled() {
        return videoManualFocusEnabled;
    }

    public boolean isVideoTapFocusActive() {
        return videoTapFocusActive;
    }

    public float getVideoFocusDistance() {
        return videoFocusDistance;
    }

    public float getVideoMinimumFocusDistance() {
        return videoMinimumFocusDistance;
    }

    public int getRequestedVideoAfMode() {
        return requestedVideoAfMode;
    }

    public int getReportedVideoAfMode() {
        return reportedVideoAfMode;
    }

    public float getReportedVideoFocusDistance() {
        return reportedVideoFocusDistance;
    }
}
