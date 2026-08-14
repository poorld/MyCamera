package com.android.mycamera.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Transcodes one MP4 video track with a center crop and copies its audio track unchanged. */
final class VideoCenterCropTranscoder {
    private static final String VIDEO_MIME = "video/avc";
    private static final int TIMEOUT_US = 10_000;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private VideoCenterCropTranscoder() { }

    static void transcode(File inputFile, File outputFile, Size outputSize) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        CropRenderer renderer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(inputFile.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            if (videoTrack < 0) throw new IOException("Input file has no video track");
            MediaFormat sourceFormat = videoExtractor.getTrackFormat(videoTrack);
            int sourceWidth = sourceFormat.getInteger(MediaFormat.KEY_WIDTH);
            int sourceHeight = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT);
            videoExtractor.selectTrack(videoTrack);

            MediaFormat encoderFormat = MediaFormat.createVideoFormat(VIDEO_MIME,
                    outputSize.getWidth(), outputSize.getHeight());
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, chooseBitRate(outputSize));
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encoderInput = encoder.createInputSurface();
            encoder.start();
            renderer = new CropRenderer(encoderInput, new Size(sourceWidth, sourceHeight), outputSize);

            decoder = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(sourceFormat, renderer.getDecoderSurface(), null, 0);
            decoder.start();
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int audioTrack = findTrack(videoExtractor, "audio/");
            MediaFormat audioFormat = audioTrack >= 0 ? videoExtractor.getTrackFormat(audioTrack) : null;
            int muxerAudioTrack = audioFormat == null ? -1 : muxer.addTrack(audioFormat);
            int muxerVideoTrack = -1;
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (!decoderDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                    videoExtractor.getSampleTime(), 0);
                            videoExtractor.advance();
                        }
                    }
                }

                int decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (decoderStatus >= 0) {
                    boolean render = bufferInfo.size != 0;
                    long presentationTimeUs = bufferInfo.presentationTimeUs;
                    decoder.releaseOutputBuffer(decoderStatus, render);
                    if (render) {
                        renderer.awaitFrame();
                        renderer.drawFrame(presentationTimeUs * 1_000L);
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderDone = true;
                    }
                }

                DrainResult result = drainEncoder(encoder, muxer, bufferInfo, muxerVideoTrack,
                        muxerStarted, muxerAudioTrack, inputFile);
                muxerVideoTrack = result.videoTrack;
                muxerStarted = result.muxerStarted;
                encoderDone = result.encoderDone;
            }

            encoder.signalEndOfInputStream();
            while (!encoderDone) {
                DrainResult result = drainEncoder(encoder, muxer, bufferInfo, muxerVideoTrack,
                        muxerStarted, muxerAudioTrack, inputFile);
                muxerVideoTrack = result.videoTrack;
                muxerStarted = result.muxerStarted;
                encoderDone = result.encoderDone;
            }
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                decoder.release();
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                encoder.release();
            }
            if (renderer != null) renderer.release();
            videoExtractor.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (RuntimeException ignored) { }
                }
                muxer.release();
            }
        }
    }

    private static DrainResult drainEncoder(MediaCodec encoder, MediaMuxer muxer,
            MediaCodec.BufferInfo info, int videoTrack, boolean muxerStarted, int audioTrack,
            File inputFile) throws IOException {
        boolean done = false;
        while (true) {
            int status = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrack >= 0) throw new IOException("Encoder format changed twice");
                videoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                if (audioTrack >= 0) copyAudioTrack(inputFile, muxer, audioTrack);
            } else if (status >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(status);
                if (encoded == null) throw new IOException("Encoder output buffer is null");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!muxerStarted) throw new IOException("Encoder emitted data before output format");
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    muxer.writeSampleData(videoTrack, encoded, info);
                }
                done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(status, false);
                if (done) break;
            }
        }
        return new DrainResult(videoTrack, muxerStarted, done);
    }

    private static void copyAudioTrack(File inputFile, MediaMuxer muxer, int muxerAudioTrack)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            int audioTrack = findTrack(extractor, "audio/");
            if (audioTrack < 0) return;
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            int maxInputSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maxInputSize, 256 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            extractor.selectTrack(audioTrack);
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) return;
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(muxerAudioTrack, buffer, info);
                extractor.advance();
            }
        } finally {
            extractor.release();
        }
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return index;
        }
        return -1;
    }

    private static int chooseBitRate(Size size) {
        long rate = (long) size.getWidth() * size.getHeight() * 5L;
        return (int) Math.max(2_000_000L, Math.min(rate, 16_000_000L));
    }

    private static final class DrainResult {
        final int videoTrack;
        final boolean muxerStarted;
        final boolean encoderDone;

        DrainResult(int videoTrack, boolean muxerStarted, boolean encoderDone) {
            this.videoTrack = videoTrack;
            this.muxerStarted = muxerStarted;
            this.encoderDone = encoderDone;
        }
    }

    private static final class CropRenderer implements SurfaceTexture.OnFrameAvailableListener {
        private static final String VERTEX = "attribute vec4 p; attribute vec2 t; varying vec2 v; "
                + "uniform mat4 m; void main(){gl_Position=p;v=(m*vec4(t,0.0,1.0)).xy;}";
        private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; varying vec2 v; uniform samplerExternalOES s; uniform vec4 c; "
                + "void main(){gl_FragColor=texture2D(s,c.xy+v*c.zw);}";

        private final Size sourceSize;
        private final Size outputSize;
        private final Surface encoderSurface;
        private final Object frameLock = new Object();
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private SurfaceTexture decoderTexture;
        private Surface decoderSurface;
        private HandlerThread frameCallbackThread;
        private int textureId;
        private int program;
        private boolean frameAvailable;

        CropRenderer(Surface encoderSurface, Size sourceSize, Size outputSize) throws IOException {
            this.sourceSize = sourceSize;
            this.outputSize = outputSize;
            this.encoderSurface = encoderSurface;
            try {
                initEgl(encoderSurface);
                textureId = createExternalTexture();
                decoderTexture = new SurfaceTexture(textureId);
                decoderTexture.setDefaultBufferSize(sourceSize.getWidth(), sourceSize.getHeight());
                frameCallbackThread = new HandlerThread("VideoCropFrameCallback");
                frameCallbackThread.start();
                decoderTexture.setOnFrameAvailableListener(this,
                        new Handler(frameCallbackThread.getLooper()));
                decoderSurface = new Surface(decoderTexture);
                program = createProgram(VERTEX, FRAGMENT);
            } catch (RuntimeException exception) {
                release();
                throw new IOException("Cannot initialize video crop renderer", exception);
            }
        }

        Surface getDecoderSurface() { return decoderSurface; }

        @Override public void onFrameAvailable(SurfaceTexture ignored) {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        }

        void awaitFrame() throws IOException {
            synchronized (frameLock) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (!frameAvailable) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) throw new IOException("Timed out waiting for decoded video frame");
                    try { frameLock.wait(remaining); }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for decoded video frame", exception);
                    }
                }
                frameAvailable = false;
            }
        }

        void drawFrame(long presentationTimeNs) throws IOException {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IOException("Cannot make encoder EGL surface current: " + EGL14.eglGetError());
            }
            decoderTexture.updateTexImage();
            float[] matrix = new float[16];
            decoderTexture.getTransformMatrix(matrix);
            GLES20.glViewport(0, 0, outputSize.getWidth(), outputSize.getHeight());
            GLES20.glUseProgram(program);
            FloatBuffer positions = buffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            FloatBuffer coordinates = buffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
            int position = GLES20.glGetAttribLocation(program, "p");
            int coordinate = GLES20.glGetAttribLocation(program, "t");
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions);
            GLES20.glEnableVertexAttribArray(coordinate);
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, matrix, 0);
            float sourceRatio = (float) sourceSize.getWidth() / sourceSize.getHeight();
            float targetRatio = (float) outputSize.getWidth() / outputSize.getHeight();
            float cropX = 0f, cropY = 0f, cropWidth = 1f, cropHeight = 1f;
            if (sourceRatio > targetRatio) {
                cropWidth = targetRatio / sourceRatio;
                cropX = (1f - cropWidth) / 2f;
            } else if (sourceRatio < targetRatio) {
                cropHeight = sourceRatio / targetRatio;
                cropY = (1f - cropHeight) / 2f;
            }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "c"), cropX, cropY, cropWidth, cropHeight);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IOException("OpenGL crop draw failed");
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs);
            if (!EGL14.eglSwapBuffers(display, surface)) {
                throw new IOException("Cannot swap cropped encoder frame: " + EGL14.eglGetError());
            }
        }

        void release() {
            if (decoderSurface != null) decoderSurface.release();
            if (decoderTexture != null) decoderTexture.release();
            if (frameCallbackThread != null) frameCallbackThread.quitSafely();
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            encoderSurface.release();
        }

        private void initEgl(Surface encoderSurface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("eglInitialize failed");
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw new IllegalStateException("No recordable EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed: " + EGL14.eglGetError());
            }
        }

        private static int createExternalTexture() {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return ids[0];
        }

        private static FloatBuffer buffer(float[] values) {
            FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            result.put(values).position(0);
            return result;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertex);
            GLES20.glAttachShader(result, fragment);
            GLES20.glLinkProgram(result);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException("Cannot link crop shader: " + GLES20.glGetProgramInfoLog(result));
            return result;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) throw new IllegalStateException("Cannot compile crop shader: " + GLES20.glGetShaderInfoLog(shader));
            return shader;
        }
    }
}
