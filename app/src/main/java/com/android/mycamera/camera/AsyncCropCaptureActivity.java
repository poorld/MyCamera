package com.android.mycamera.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/** Single-camera capture with independently selected native size and center crop output. */
public class AsyncCropCaptureActivity extends AppCompatActivity {
    private static final String TAG = "AsyncCropCaptureActivity";
    private static final int REQUEST_PERMISSIONS = 240;
    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";

    private TextureView previewView;
    private RadioGroup captureModeGroup;
    private EditText outputWidth;
    private EditText outputHeight;
    private ScrollView contentScrollView;
    private Spinner resolutionSpinner;
    private Button switchCameraButton;
    private Button captureButton;
    private TextView cameraName;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MediaRecorder mediaRecorder;
    private RealTimeCenterCropRecorder realTimeCropRecorder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread videoTranscodeThread;
    private Handler videoTranscodeHandler;
    private String cameraId;
    private Size activePreviewSize;
    private boolean isRecording;
    private boolean isVideoTranscoding;
    private boolean opening;
    private boolean invalidOutputSize;
    private Size pendingPhotoTarget;
    private Size pendingVideoCropTarget;
    private Size recordingNativeSize;
    private File activeVideoFile;
    private File activeCropVideoTrackFile;
    private File activeCroppedVideoFile;
    private volatile int cameraGeneration;
    private Size selectedNativeSize;
    private final List<Size> resolutionOptions = new ArrayList<>();
    private boolean updatingResolutionSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop_capture);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        initViews();
        startCameraThread();
        if (hasPermissions()) {
            selectFirstCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSIONS);
        }
        Log.d(TAG, "onCreate: ");
    }

    private void initViews() {
        previewView = findViewById(R.id.preview_view);
        contentScrollView = findViewById(R.id.crop_capture_scroll_view);
        captureModeGroup = findViewById(R.id.capture_mode_group);
        outputWidth = findViewById(R.id.output_width);
        outputHeight = findViewById(R.id.output_height);
        resolutionSpinner = findViewById(R.id.resolution_spinner);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        captureButton = findViewById(R.id.capture_button);
        cameraName = findViewById(R.id.camera_name);
        View.OnFocusChangeListener scrollFocusedInputAboveKeyboard = (view, hasFocus) -> {
            if (hasFocus) {
                contentScrollView.postDelayed(() -> scrollInputAboveKeyboard(view), 250L);
            }
        };
        outputWidth.setOnFocusChangeListener(scrollFocusedInputAboveKeyboard);
        outputHeight.setOnFocusChangeListener(scrollFocusedInputAboveKeyboard);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
        });
        captureModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isRecording || isVideoTranscoding) {
                captureModeGroup.check(R.id.video_mode);
                return;
            }
            updateCaptureButton();
            refreshResolutionOptions();
            restartCamera();
        });
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (updatingResolutionSpinner) return;
                Size size = resolutionOptions.get(position);
                selectedNativeSize = size;
                if (!isRecording && !isVideoTranscoding && cameraDevice != null) {
                    restartCamera();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        switchCameraButton.setOnClickListener(view -> switchCamera());
        captureButton.setOnClickListener(view -> onCaptureClicked());
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void scrollInputAboveKeyboard(View input) {
        if (contentScrollView == null || !input.hasFocus()) return;
        int marginPx = (int) (32 * getResources().getDisplayMetrics().density);
        contentScrollView.smoothScrollTo(0, Math.max(0, input.getTop() - marginPx));
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("AsyncCropCaptureCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        videoTranscodeThread = new HandlerThread("AsyncCropVideoFinalize");
        videoTranscodeThread.start();
        videoTranscodeHandler = new Handler(videoTranscodeThread.getLooper());
    }

    private void selectFirstCamera() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) {
                Toast.makeText(this, "未找到摄像头", Toast.LENGTH_LONG).show();
                return;
            }
            cameraId = ids[0];
            updateCameraName();
            refreshResolutionOptions();
            if (previewView.isAvailable()) openCamera();
        } catch (CameraAccessException exception) {
            Toast.makeText(this, "无法读取摄像头", Toast.LENGTH_LONG).show();
        }
    }

    private void openCamera() {
        if (cameraId == null || opening || cameraDevice != null || !previewView.isAvailable()) return;
        Log.d(TAG, "openCamera id=" + cameraId + ", generation=" + cameraGeneration);
        opening = true;
        final int openGeneration = cameraGeneration;
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice device) {
                    opening = false;
                    if (openGeneration != cameraGeneration) {
                        device.close();
                        return;
                    }
                    cameraDevice = device;
                    Log.d(TAG, "camera opened id=" + cameraId + ", generation=" + openGeneration);
                    createPreviewSession(device, openGeneration);
                }
                @Override public void onDisconnected(@NonNull CameraDevice device) {
                    opening = false;
                    device.close();
                    if (cameraDevice == device) cameraDevice = null;
                }
                @Override public void onError(@NonNull CameraDevice device, int error) {
                    opening = false;
                    device.close();
                    if (cameraDevice == device) cameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException exception) {
            opening = false;
            Toast.makeText(this, "打开摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void createPreviewSession(CameraDevice device, int sessionGeneration) {
        if (device != cameraDevice || sessionGeneration != cameraGeneration || !previewView.isAvailable()) return;
        try {
            StreamConfigurationMap map = getStreamMap();
            if (map == null) return;
            activePreviewSize = choosePreviewSize(map);
            Log.d(TAG, "create preview session mode=" + (isPhotoMode() ? "photo" : "video")
                    + ", nativeSize=" + activePreviewSize + ", generation=" + sessionGeneration);
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(activePreviewSize.getWidth(), activePreviewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(previewSurface);
            if (isPhotoMode()) {
                Size photoSize = choosePhotoSize(map);
                imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
                imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
                outputs.add(imageReader.getSurface());
            }
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (sessionGeneration != cameraGeneration || device != cameraDevice) {
                        session.close();
                        return;
                    }
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        Log.d(TAG, "preview repeating request started, generation=" + sessionGeneration);
                    } catch (CameraAccessException | IllegalStateException exception) {
                        session.close();
                        if (captureSession == session) captureSession = null;
                        return;
                    }
                    runOnUiThread(() -> captureButton.setEnabled(true));
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    if (sessionGeneration == cameraGeneration && device == cameraDevice) {
                        runOnUiThread(() -> Toast.makeText(AsyncCropCaptureActivity.this, "预览配置失败", Toast.LENGTH_SHORT).show());
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException exception) {
            Toast.makeText(this, "创建预览失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void onCaptureClicked() {
        Size requestedSize = getRequestedOutputSize();
        if (invalidOutputSize) return;
        if (isVideoTranscoding) return;
        if (isPhotoMode()) takePhoto(requestedSize); else if (isRecording) stopRecording(); else startRecording();
    }

    private void takePhoto(Size target) {
        if (cameraDevice == null || captureSession == null || imageReader == null) return;
        try {
            pendingPhotoTarget = target;
            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            applyCropRegion(request, target);
            captureSession.capture(request.build(), null, cameraHandler);
        } catch (CameraAccessException exception) {
            Toast.makeText(this, "拍照失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            byte[] jpeg = new byte[image.getPlanes()[0].getBuffer().remaining()];
            image.getPlanes()[0].getBuffer().get(jpeg);
            Size target = pendingPhotoTarget;
            File output = new File(getExternalFilesDir(null), "crop_photo_" + timestamp() + ".jpg");
            if (target == null) {
                try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(jpeg); }
            } else {
                saveCenterCroppedJpeg(jpeg, target, output);
            }
            runOnUiThread(() -> Toast.makeText(this, "照片已保存：" + output.getName(), Toast.LENGTH_SHORT).show());
        } catch (IOException exception) {
            runOnUiThread(() -> Toast.makeText(this, "保存照片失败", Toast.LENGTH_SHORT).show());
        } finally {
            if (image != null) image.close();
        }
    }

    private void startRecording() {
        Size cropOutputSize = getRequestedOutputSize();
        if (invalidOutputSize) return;
        try {
            final Size nativeCaptureSize = chooseVideoSize(getStreamMap());
            recordingNativeSize = nativeCaptureSize;
            Log.d(TAG, "start recording native=" + nativeCaptureSize + ", cropOutput=" + cropOutputSize);
            closeSessionOnly();
            final int recordingGeneration = cameraGeneration;
            final CameraDevice recordingDevice = cameraDevice;
            if (recordingDevice == null) return;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            activeVideoFile = new File(getExternalFilesDir(null), "async_crop_video_" + timestamp() + ".mp4");
            pendingVideoCropTarget = cropOutputSize;
            mediaRecorder.setOutputFile(activeVideoFile.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(10_000_000);
            mediaRecorder.setVideoFrameRate(30);
            // Camera2 can send this camera-provided stream size directly to MediaRecorder.
            // A 1920x1920 recorder Surface is not a supported output stream on this device,
            // so the requested crop is deliberately handled after recording rather than here.
            mediaRecorder.setVideoSize(nativeCaptureSize.getWidth(), nativeCaptureSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder prepared");
            boolean needsRealtimeCrop = cropOutputSize != null && !cropOutputSize.equals(nativeCaptureSize);
            if (needsRealtimeCrop) {
                String baseName = activeVideoFile.getName().replace(".mp4", "");
                activeCropVideoTrackFile = new File(getExternalFilesDir(null), baseName + "_center_video_track.mp4");
                activeCroppedVideoFile = new File(getExternalFilesDir(null), baseName + "_center_"
                        + cropOutputSize.getWidth() + "x" + cropOutputSize.getHeight() + ".mp4");
                realTimeCropRecorder = new RealTimeCenterCropRecorder(activeCropVideoTrackFile,
                        nativeCaptureSize, cropOutputSize);
                Log.d(TAG, "real-time crop recorder prepared output=" + activeCroppedVideoFile.getName());
            }
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(nativeCaptureSize.getWidth(), nativeCaptureSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();
            CaptureRequest.Builder request = recordingDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(previewSurface);
            request.addTarget(recorderSurface);
            List<Surface> outputs = new ArrayList<>(Arrays.asList(previewSurface, recorderSurface));
            if (realTimeCropRecorder != null) {
                Surface cropSurface = realTimeCropRecorder.getCameraInputSurface();
                request.addTarget(cropSurface);
                outputs.add(cropSurface);
            }
            Range<Integer> recordingFpsRange = chooseRecordingFpsRange();
            if (recordingFpsRange != null) {
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recordingFpsRange);
                Log.d(TAG, "recording fps range=" + recordingFpsRange);
            }
            recordingDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (recordingGeneration != cameraGeneration || recordingDevice != cameraDevice) {
                        session.close();
                        stopRealTimeCropRecorder();
                        releaseRecorder();
                        return;
                    }
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        Log.d(TAG, "record repeating request started");
                        mediaRecorder.start();
                        isRecording = true;
                        Log.d(TAG, "MediaRecorder started");
                        runOnUiThread(() -> { updateCaptureButton(); switchCameraButton.setEnabled(false); });
                    } catch (CameraAccessException | IllegalStateException exception) {
                        Log.e(TAG, "Cannot start recording request", exception);
                        session.close();
                        if (captureSession == session) captureSession = null;
                        stopRealTimeCropRecorder();
                        releaseRecorder();
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Record session configure failed");
                    stopRealTimeCropRecorder();
                    releaseRecorder();
                }
            }, cameraHandler);
        } catch (IOException | CameraAccessException exception) {
            Log.e(TAG, "Cannot start recording", exception);
            stopRealTimeCropRecorder();
            releaseRecorder();
            Toast.makeText(this, "开始录制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (CameraAccessException | IllegalStateException ignored) { }
        }
        try { mediaRecorder.stop(); } catch (RuntimeException ignored) { }
        closeSessionOnly();
        File savedVideo = activeVideoFile;
        File cropVideoTrack = activeCropVideoTrackFile;
        File croppedVideo = activeCroppedVideoFile;
        releaseRecorder();
        boolean cropRecorderStopped = stopRealTimeCropRecorder();
        isRecording = false;
        switchCameraButton.setEnabled(true);
        updateCaptureButton();
        if (savedVideo != null && croppedVideo != null && cropVideoTrack != null && cropRecorderStopped) {
            closeCamera();
            finalizeRealTimeCrop(savedVideo, cropVideoTrack, croppedVideo);
        } else if (savedVideo != null) {
            Toast.makeText(this, "视频已保存：" + savedVideo.getName(), Toast.LENGTH_SHORT).show();
            restartCamera();
        }
        activeVideoFile = null;
        pendingVideoCropTarget = null;
        recordingNativeSize = null;
        activeCropVideoTrackFile = null;
        activeCroppedVideoFile = null;
    }

    private boolean stopRealTimeCropRecorder() {
        if (realTimeCropRecorder == null) return true;
        try {
            realTimeCropRecorder.stop();
            return true;
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Cannot finalize real-time crop recorder", exception);
            return false;
        } finally {
            realTimeCropRecorder = null;
        }
    }

    private void finalizeRealTimeCrop(File nativeVideo, File cropVideoTrack, File croppedVideo) {
        if (videoTranscodeHandler == null) return;
        isVideoTranscoding = true;
        captureButton.setEnabled(false);
        switchCameraButton.setEnabled(false);
        captureButton.setText("正在封装裁剪视频");
        Toast.makeText(this, "裁剪视频已实时编码，正在封装音频", Toast.LENGTH_SHORT).show();
        videoTranscodeHandler.post(() -> {
            try {
                Log.d(TAG, "mux real-time crop video=" + cropVideoTrack.getName() + ", audio=" + nativeVideo.getName());
                Mp4TrackMuxer.muxCroppedVideoAndOriginalAudio(cropVideoTrack, nativeVideo, croppedVideo);
                if (cropVideoTrack.exists()) cropVideoTrack.delete();
                Log.d(TAG, "real-time crop finalized output=" + croppedVideo.getName());
                runOnUiThread(() -> finishVideoCenterCrop("裁剪视频已保存：" + croppedVideo.getName()));
            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "Real-time crop finalization failed; original video is kept", exception);
                if (croppedVideo.exists()) croppedVideo.delete();
                runOnUiThread(() -> finishVideoCenterCrop("视频裁剪失败，已保留原生视频"));
            }
        });
    }

    private void finishVideoCenterCrop(String message) {
        if (isFinishing() || isDestroyed()) return;
        isVideoTranscoding = false;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        switchCameraButton.setEnabled(true);
        updateCaptureButton();
        restartCamera();
    }

    private void switchCamera() {
        if (isRecording || isVideoTranscoding) return;
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length < 2) { Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show(); return; }
            int index = Arrays.asList(ids).indexOf(cameraId);
            cameraId = ids[(index + 1 + ids.length) % ids.length];
            updateCameraName();
            refreshResolutionOptions();
            restartCamera();
        } catch (CameraAccessException exception) {
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartCamera() {
        captureButton.setEnabled(false);
        closeCamera();
        if (previewView.isAvailable()) openCamera();
    }

    private void closeSessionOnly() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
    }

    private void closeCamera() {
        cameraGeneration++;
        closeSessionOnly();
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) { mediaRecorder.reset(); mediaRecorder.release(); mediaRecorder = null; }
    }

    private StreamConfigurationMap getStreamMap() throws CameraAccessException {
        return cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    }

    /** Prefer a camera-provided range capped at 30fps for predictable recorder throughput. */
    private Range<Integer> chooseRecordingFpsRange() {
        try {
            Range<Integer>[] ranges = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null) return null;
            Range<Integer> best = null;
            for (Range<Integer> range : ranges) {
                if (range.getLower() <= 30 && range.getUpper() >= 30) {
                    if (best == null || range.getUpper() < best.getUpper()) best = range;
                }
            }
            return best;
        } catch (CameraAccessException exception) {
            Log.w(TAG, "Cannot query camera FPS ranges", exception);
            return null;
        }
    }

    private Size choosePreviewSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (containsSize(sizes, selectedNativeSize)) return selectedNativeSize;
        return sizes != null && sizes.length > 0 ? sizes[0] : new Size(1280, 720);
    }

    private Size choosePhotoSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (containsSize(sizes, selectedNativeSize)) return selectedNativeSize;
        return sizes != null && sizes.length > 0 ? sizes[0] : activePreviewSize;
    }

    private Size chooseVideoSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (containsSize(sizes, selectedNativeSize)) return selectedNativeSize;
        return sizes != null && sizes.length > 0 ? sizes[0] : activePreviewSize;
    }

    private static boolean containsSize(Size[] sizes, Size expected) {
        return sizes != null && expected != null && Arrays.asList(sizes).contains(expected);
    }

    /** Refreshes native output sizes for the selected camera and current capture mode. */
    private void refreshResolutionOptions() {
        if (cameraId == null) return;
        try {
            StreamConfigurationMap map = getStreamMap();
            Size[] sizes = isPhotoMode() ? map.getOutputSizes(ImageFormat.JPEG)
                    : map.getOutputSizes(MediaRecorder.class);
            resolutionOptions.clear();
            resolutionOptions.add(null);
            if (sizes != null) {
                resolutionOptions.addAll(Arrays.asList(sizes));
            }
            resolutionOptions.subList(1, resolutionOptions.size()).sort(
                    Comparator.comparingLong((Size size) -> (long) size.getWidth() * size.getHeight()).reversed());
            List<String> labels = new ArrayList<>();
            labels.add("原生分辨率：自动选择");
            for (int index = 1; index < resolutionOptions.size(); index++) {
                Size size = resolutionOptions.get(index);
                labels.add(size.getWidth() + " x " + size.getHeight());
            }
            updatingResolutionSpinner = true;
            resolutionSpinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
            resolutionSpinner.setSelection(0, false);
            updatingResolutionSpinner = false;
            selectedNativeSize = null;
        } catch (CameraAccessException exception) {
            Toast.makeText(this, "无法查询当前摄像头分辨率", Toast.LENGTH_SHORT).show();
        }
    }

    private Size getRequestedOutputSize() {
        invalidOutputSize = false;
        String widthText = outputWidth.getText().toString().trim();
        String heightText = outputHeight.getText().toString().trim();
        if (widthText.isEmpty() && heightText.isEmpty()) return null;
        if (widthText.isEmpty() || heightText.isEmpty()) {
            invalidOutputSize = true;
            Toast.makeText(this, "请同时填写输出宽度和高度", Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            int width = Integer.parseInt(widthText);
            int height = Integer.parseInt(heightText);
            if (width > 0 && height > 0) {
                if (!isPhotoMode() && ((width & 1) != 0 || (height & 1) != 0)) {
                    invalidOutputSize = true;
                    Toast.makeText(this, "录像裁剪宽高必须为偶数", Toast.LENGTH_SHORT).show();
                    return null;
                }
                return new Size(width, height);
            }
        } catch (NumberFormatException ignored) { }
        invalidOutputSize = true;
        Toast.makeText(this, "输出尺寸必须为正整数", Toast.LENGTH_SHORT).show();
        return null;
    }

    private void applyCropRegion(CaptureRequest.Builder builder, Size target) {
        if (target == null) return;
        try {
            Rect sensor = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor == null) return;
            float targetRatio = (float) target.getWidth() / target.getHeight();
            float sensorRatio = (float) sensor.width() / sensor.height();
            int width = sensor.width();
            int height = sensor.height();
            if (sensorRatio > targetRatio) width = Math.round(height * targetRatio); else height = Math.round(width / targetRatio);
            int left = sensor.left + (sensor.width() - width) / 2;
            int top = sensor.top + (sensor.height() - height) / 2;
            builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + width, top + height));
        } catch (CameraAccessException ignored) { }
    }

    private void saveCenterCroppedJpeg(byte[] jpeg, Size target, File output) throws IOException {
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null) throw new IOException("Invalid JPEG");
        float targetRatio = (float) target.getWidth() / target.getHeight();
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();
        if ((float) cropWidth / cropHeight > targetRatio) cropWidth = Math.round(cropHeight * targetRatio);
        else cropHeight = Math.round(cropWidth / targetRatio);
        int left = (source.getWidth() - cropWidth) / 2;
        int top = (source.getHeight() - cropHeight) / 2;
        Bitmap cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        Bitmap outputBitmap = Bitmap.createScaledBitmap(cropped, target.getWidth(), target.getHeight(), true);
        try (FileOutputStream stream = new FileOutputStream(output)) { outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream); }
        source.recycle();
        if (cropped != source) cropped.recycle();
        if (outputBitmap != cropped) outputBitmap.recycle();
    }

    private boolean isPhotoMode() { return captureModeGroup.getCheckedRadioButtonId() == R.id.photo_mode; }
    private String timestamp() { return new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(new Date()); }
    private void updateCaptureButton() { captureButton.setText(isPhotoMode() ? "拍照" : isRecording ? "停止录制" : "开始录制"); }

    private void updateCameraName() {
        try {
            Integer facing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING);
            String label = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "前摄" : "后摄";
            cameraName.setText(label + "（ID: " + cameraId + "）");
        } catch (CameraAccessException ignored) { cameraName.setText("摄像头 " + cameraId); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS && hasPermissions()) selectFirstCamera();
        else if (requestCode == REQUEST_PERMISSIONS) finish();
    }

    @Override protected void onDestroy() {
        stopRealTimeCropRecorder();
        closeCamera();
        releaseRecorder();
        if (cameraThread != null) cameraThread.quitSafely();
        if (videoTranscodeThread != null) videoTranscodeThread.quitSafely();
        super.onDestroy();
    }
}

