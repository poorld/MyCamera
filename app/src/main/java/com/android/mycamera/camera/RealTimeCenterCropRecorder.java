package com.android.mycamera.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
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
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Encodes center-cropped frames while Camera2 is recording. The result contains a video track only;
 * the caller copies the audio track from its simultaneously recorded native MP4 during finalization.
 */
final class RealTimeCenterCropRecorder implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "RealTimeCropRecorder";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final String VERTEX = "attribute vec4 p; attribute vec2 t; varying vec2 v; "
            + "uniform mat4 m; void main(){gl_Position=p;v=(m*vec4(t,0.0,1.0)).xy;}";
    private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; varying vec2 v; uniform samplerExternalOES s; uniform vec4 c; "
            + "void main(){gl_FragColor=texture2D(s,c.xy+v*c.zw);}";

    private final File videoTrackFile;
    private final Size nativeSize;
    private final Size outputSize;
    private final HandlerThread renderThread = new HandlerThread("RealTimeCropRender");
    private Handler renderHandler;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int muxerVideoTrack = -1;
    private boolean muxerStarted;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;
    private Surface encoderSurface;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private int textureId;
    private int program;
    private final float[] textureMatrix = new float[16];
    private long firstCameraTimestampNs = -1L;
    private int renderedFrameCount;
    private volatile RuntimeException failure;
    private boolean stopped;

    RealTimeCenterCropRecorder(File videoTrackFile, Size nativeSize, Size outputSize) throws IOException {
        this.videoTrackFile = videoTrackFile;
        this.nativeSize = nativeSize;
        this.outputSize = outputSize;
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        runSync(this::startOnRenderThread);
    }

    Surface getCameraInputSurface() {
        return cameraSurface;
    }

    void stop() throws IOException {
        runSync(this::stopOnRenderThread);
    }

    private void startOnRenderThread() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    outputSize.getWidth(), outputSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, chooseBitRate(outputSize));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(videoTrackFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            initEgl();
            textureId = createExternalTexture();
            cameraTexture = new SurfaceTexture(textureId);
            cameraTexture.setDefaultBufferSize(nativeSize.getWidth(), nativeSize.getHeight());
            cameraTexture.setOnFrameAvailableListener(this, renderHandler);
            cameraSurface = new Surface(cameraTexture);
            program = createProgram(VERTEX, FRAGMENT);
            Log.d(TAG, "started native=" + nativeSize + ", output=" + outputSize);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start real-time crop encoder", exception);
        }
    }

    @Override public void onFrameAvailable(SurfaceTexture ignored) {
        if (stopped || failure != null) return;
        try {
            drawFrame();
            drainEncoder(false);
        } catch (IOException | RuntimeException exception) {
            failure = exception instanceof RuntimeException ? (RuntimeException) exception
                    : new IllegalStateException("Real-time crop rendering failed", exception);
            Log.e(TAG, "Real-time crop rendering failed", exception);
        }
    }

    private void drawFrame() throws IOException {
        if (!EGL14.eglMakeCurrent(display, encoderEglSurface, encoderEglSurface, context)) {
            throw new IOException("Cannot make crop encoder EGL surface current: " + EGL14.eglGetError());
        }
        cameraTexture.updateTexImage();
        cameraTexture.getTransformMatrix(textureMatrix);
        long cameraTimestampNs = cameraTexture.getTimestamp();
        if (firstCameraTimestampNs < 0L) firstCameraTimestampNs = cameraTimestampNs;
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
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, textureMatrix, 0);
        float sourceRatio = (float) nativeSize.getWidth() / nativeSize.getHeight();
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
        EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface,
                Math.max(0L, cameraTimestampNs - firstCameraTimestampNs));
        if (!EGL14.eglSwapBuffers(display, encoderEglSurface)) {
            throw new IOException("Cannot swap crop encoder buffer: " + EGL14.eglGetError());
        }
        renderedFrameCount++;
        if (renderedFrameCount == 1) {
            Log.d(TAG, "encoded first real-time crop frame");
        }
    }

    private void stopOnRenderThread() {
        if (stopped) return;
        stopped = true;
        try {
            if (failure == null) {
                encoder.signalEndOfInputStream();
                long deadline = System.currentTimeMillis() + 8_000L;
                while (!drainEncoder(true)) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw new IOException("Timed out finalizing real-time crop encoder");
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            failure = exception instanceof RuntimeException ? (RuntimeException) exception
                    : new IllegalStateException("Cannot finalize real-time crop encoder", exception);
        } finally {
            releaseOnRenderThread();
        }
        if (failure != null) throw failure;
    }

    /** @return true when the encoder emitted end of stream. */
    private boolean drainEncoder(boolean endOfStream) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int status = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerVideoTrack >= 0) throw new IOException("Encoder output format changed twice");
                muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (status < 0) continue;
            ByteBuffer output = encoder.getOutputBuffer(status);
            if (output == null) throw new IOException("Encoder output buffer is null");
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0) {
                if (!muxerStarted) throw new IOException("Encoder emitted data before output format");
                output.position(info.offset);
                output.limit(info.offset + info.size);
                muxer.writeSampleData(muxerVideoTrack, output, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(status, false);
            if (eos) return true;
            if (!endOfStream) continue;
        }
    }

    private void runSync(Runnable runnable) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        final RuntimeException[] error = new RuntimeException[1];
        renderHandler.post(() -> {
            try { runnable.run(); } catch (RuntimeException exception) { error[0] = exception; }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for crop renderer");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for crop renderer", exception);
        }
        if (error[0] != null) throw new IOException("Crop renderer failed", error[0]);
    }

    private void releaseOnRenderThread() {
        if (cameraSurface != null) cameraSurface.release();
        if (cameraTexture != null) cameraTexture.release();
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
        if (encoderSurface != null) encoderSurface.release();
        if (encoder != null) {
            try { encoder.stop(); } catch (RuntimeException ignored) { }
            encoder.release();
        }
        if (muxer != null) {
            if (muxerStarted) {
                try { muxer.stop(); } catch (RuntimeException ignored) { }
            }
            muxer.release();
        }
        renderThread.quitSafely();
    }

    private void initEgl() {
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
        encoderEglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, encoderEglSurface, encoderEglSurface, context)) {
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

    private static int chooseBitRate(Size size) {
        long rate = (long) size.getWidth() * size.getHeight() * 5L;
        return (int) Math.max(2_000_000L, Math.min(rate, 16_000_000L));
    }
}
