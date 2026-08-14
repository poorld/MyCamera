package com.android.mycamera.camera;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Combines the already encoded cropped video with the audio stream from the native recording. */
final class Mp4TrackMuxer {
    private Mp4TrackMuxer() { }

    static void muxCroppedVideoAndOriginalAudio(File croppedVideoFile, File nativeVideoFile,
            File outputFile) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(croppedVideoFile.getAbsolutePath());
            audioExtractor.setDataSource(nativeVideoFile.getAbsolutePath());
            int videoSourceTrack = findTrack(videoExtractor, "video/");
            if (videoSourceTrack < 0) throw new IOException("Cropped recording has no video track");
            int audioSourceTrack = findTrack(audioExtractor, "audio/");
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTargetTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack));
            int audioTargetTrack = audioSourceTrack >= 0
                    ? muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack)) : -1;
            muxer.start();
            muxerStarted = true;
            copyTrack(videoExtractor, videoSourceTrack, muxer, videoTargetTrack);
            if (audioSourceTrack >= 0) copyTrack(audioExtractor, audioSourceTrack, muxer, audioTargetTrack);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (RuntimeException ignored) { }
                }
                muxer.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return index;
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor extractor, int sourceTrack, MediaMuxer muxer,
            int targetTrack) {
        MediaFormat format = extractor.getTrackFormat(sourceTrack);
        int maximum = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maximum, 256 * 1024));
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        extractor.selectTrack(sourceTrack);
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) return;
            info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
            muxer.writeSampleData(targetTrack, buffer, info);
            extractor.advance();
        }
    }
}
