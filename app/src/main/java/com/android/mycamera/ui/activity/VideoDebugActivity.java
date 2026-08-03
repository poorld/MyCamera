package com.android.mycamera.ui.activity;

import android.Manifest;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.manager.CameraManager;
import com.android.mycamera.camera.observer.CameraStateObserver;
import com.android.mycamera.camera.strategy.RecordingStats;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.model.VideoBitrate;
import com.android.mycamera.model.VideoCodec;
import com.android.mycamera.utils.CameraUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Standalone engineering screen for exercising the shared recorder pipeline.
 */
public class VideoDebugActivity extends BaseAct implements CameraStateObserver {
    private static final int REQUEST_CODE_PERMISSIONS = 60;
    private static final long STATS_REFRESH_MS = 500L;
    private static final long RECONFIGURE_DELAY_MS = 250L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String[] requiredPermissions = CameraUtils.getRequiredPermissions();

    private CameraManager cameraManager;
    private TextureView previewView;
    private Spinner resolutionSpinner;
    private Button recordButton;
    private TextView statusText;
    private TabLayout debugTabs;
    private ViewPager2 debugPager;
    private DebugPagerAdapter debugPagerAdapter;
    private final String[] debugPageText = new String[4];

    private final List<Resolution> resolutionOptions = new ArrayList<>();
    private CameraConfig originalConfig;
    private CaptureMode originalCaptureMode = CaptureMode.PHOTO;
    private Resolution pendingResolution;

    private boolean initialized;
    private boolean leaving;
    private boolean surfaceReady;
    private boolean isRecording;
    private boolean transitioning;
    private boolean pendingStartAfterReconfigure;
    private boolean restartAfterResolutionSwitch;
    private boolean syncingResolution;
    private boolean modeRestored;
    private String lastEvent = "created";

    private String lastSamplePath;
    private long lastSampleElapsedMs;
    private long lastSampleBytes;
    private long lastSampleFrames;

    private final Runnable statsRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshDebugDetails();
            if (!leaving) {
                mainHandler.postDelayed(this, STATS_REFRESH_MS);
            }
        }
    };

    private final Runnable applyPendingResolutionRunnable = this::applyPendingResolution;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface,
                                                       int width, int height) {
                    surfaceReady = true;
                    startPreviewIfPossible();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface,
                                                        int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    surfaceReady = false;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_debug);

        previewView = findViewById(R.id.debugPreview);
        resolutionSpinner = findViewById(R.id.debugResolutionSpinner);
        recordButton = findViewById(R.id.debugRecordButton);
        statusText = findViewById(R.id.debugStatus);
        debugTabs = findViewById(R.id.debugTabs);
        debugPager = findViewById(R.id.debugPager);

        debugPagerAdapter = new DebugPagerAdapter();
        debugPager.setAdapter(debugPagerAdapter);
        debugPager.setOffscreenPageLimit(debugPageText.length);
        new TabLayoutMediator(debugTabs, debugPager,
                (tab, position) -> tab.setText(getDebugPageTitle(position))).attach();

        previewView.setSurfaceTextureListener(surfaceTextureListener);
        ((ImageButton) findViewById(R.id.debugBackButton)).setOnClickListener(v -> finish());
        recordButton.setOnClickListener(v -> toggleRecording());

        cameraManager = CameraManager.getInstance(this);
        originalConfig = cameraManager.getCurrentConfig();
        if (originalConfig != null) {
            originalCaptureMode = CaptureMode.normalize(originalConfig.getCaptureMode());
        }
        setupResolutionSpinner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        leaving = false;
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS);
            return;
        }
        initializeDebugCamera();
        mainHandler.removeCallbacks(statsRefreshRunnable);
        mainHandler.post(statsRefreshRunnable);
    }

    @Override
    protected void onPause() {
        leaving = true;
        mainHandler.removeCallbacks(statsRefreshRunnable);
        mainHandler.removeCallbacks(applyPendingResolutionRunnable);
        releaseDebugCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            return;
        }
        if (hasAllPermissions()) {
            initializeDebugCamera();
            mainHandler.post(statsRefreshRunnable);
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initializeDebugCamera() {
        if (initialized || cameraManager == null) {
            return;
        }
        if (cameraManager.getCurrentConfig() == null) {
            showStatus("Camera configuration unavailable");
            return;
        }

        // The engineering page always exercises the video pipeline. The old
        // photo/video mode is restored when the page leaves.
        cameraManager.ensureCaptureMode(CaptureMode.VIDEO, false);
        modeRestored = false;
        cameraManager.addStateObserver(this);
        initialized = true;
        showStatus("Opening camera...");
        cameraManager.initializeCamera(this);
        setupResolutionSpinner();
    }

    private void releaseDebugCamera() {
        if (cameraManager == null) {
            return;
        }
        if (isRecording) {
            cameraManager.stopRecording();
        }
        restoreOriginalCaptureMode();
        cameraManager.removeStateObserver(this);
        try {
            cameraManager.stopPreview();
            cameraManager.closeCamera();
            cameraManager.release();
        } catch (RuntimeException ignored) {
            // Camera callbacks may already be tearing down during Activity pause.
        }
        initialized = false;
        isRecording = false;
        transitioning = false;
        pendingStartAfterReconfigure = false;
        restartAfterResolutionSwitch = false;
        pendingResolution = null;
    }

    private void restoreOriginalCaptureMode() {
        if (modeRestored || originalConfig == null || originalCaptureMode == CaptureMode.VIDEO) {
            return;
        }
        CameraConfig latest = cameraManager.getCurrentConfig();
        if (latest != null) {
            cameraManager.stageConfiguration(new CameraConfig.Builder(latest)
                    .setCaptureMode(originalCaptureMode)
                    .build());
        }
        modeRestored = true;
    }

    private void setupResolutionSpinner() {
        if (resolutionSpinner == null || cameraManager == null) {
            return;
        }
        CameraConfig config = cameraManager.getCurrentConfig();
        Set<Resolution> unique = new LinkedHashSet<>();
        List<Resolution> supported = cameraManager.getCurrentStrategy() != null
                ? cameraManager.getCurrentStrategy().getSupportedResolutions()
                : Collections.emptyList();
        for (Resolution resolution : supported) {
            if (resolution != null) {
                unique.add(resolution);
            }
        }
        if (unique.isEmpty()) {
            unique.add(Resolution.QHD_2K);
            unique.add(Resolution.FULL_HD_1080P);
            unique.add(Resolution.HD_720P);
            unique.add(Resolution.UHD_4K);
        }
        if (config != null && config.getResolution() != null) {
            unique.add(config.getResolution());
        }
        resolutionOptions.clear();
        resolutionOptions.addAll(unique);
        Collections.sort(resolutionOptions, new Comparator<Resolution>() {
            @Override
            public int compare(Resolution left, Resolution right) {
                long leftArea = (long) left.getWidth() * left.getHeight();
                long rightArea = (long) right.getWidth() * right.getHeight();
                return Long.compare(rightArea, leftArea);
            }
        });

        ArrayAdapter<Resolution> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        syncingResolution = true;
        resolutionSpinner.setAdapter(adapter);
        int selected = config == null ? -1 : resolutionOptions.indexOf(config.getResolution());
        if (selected >= 0) {
            resolutionSpinner.setSelection(selected, false);
        }
        syncingResolution = false;
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncingResolution && position >= 0 && position < resolutionOptions.size()) {
                    requestResolutionChange(resolutionOptions.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void toggleRecording() {
        if (cameraManager == null || transitioning) {
            return;
        }
        if (isRecording) {
            transitioning = true;
            resolutionSpinner.setEnabled(false);
            showStatus("Stopping current segment...");
            recordButton.setEnabled(false);
            cameraManager.stopRecording();
        } else {
            transitioning = true;
            resolutionSpinner.setEnabled(false);
            showStatus("Starting recorder...");
            recordButton.setEnabled(false);
            cameraManager.startRecording();
        }
    }

    private void requestResolutionChange(Resolution resolution) {
        CameraConfig current = cameraManager != null ? cameraManager.getCurrentConfig() : null;
        if (resolution == null || current == null
                || resolution.equals(current.getResolution())) {
            return;
        }
        pendingResolution = resolution;
        resolutionSpinner.setEnabled(false);
        transitioning = true;
        if (isRecording) {
            restartAfterResolutionSwitch = true;
            showStatus("Finishing segment before resolution switch...");
            cameraManager.stopRecording();
        } else {
            restartAfterResolutionSwitch = false;
            pendingStartAfterReconfigure = false;
            applyPendingResolution();
        }
    }

    private void applyPendingResolution() {
        if (leaving || pendingResolution == null || cameraManager == null) {
            return;
        }
        Resolution target = pendingResolution;
        pendingResolution = null;
        pendingStartAfterReconfigure = restartAfterResolutionSwitch;
        restartAfterResolutionSwitch = false;
        CameraConfig current = cameraManager.getCurrentConfig();
        if (current == null) {
            finishTransition("Camera configuration unavailable");
            return;
        }

        CameraConfig updated = new CameraConfig.Builder(current)
                .setCaptureMode(CaptureMode.VIDEO)
                .setResolution(target)
                .setQuality(matchQuality(target))
                .setFrameRate(frameRateFor(target))
                .setVideoBitrate(VideoBitrate.recommendedFor(target, current.getVideoCodec()))
                .build();
        showStatus("Rebuilding " + target + " pipeline...");
        try {
            cameraManager.updateConfiguration(updated);
        } catch (RuntimeException exception) {
            pendingStartAfterReconfigure = false;
            finishTransition("Resolution switch failed: " + exception.getMessage());
        }
    }

    private void startPreviewIfPossible() {
        if (leaving || cameraManager == null || !surfaceReady || isRecording) {
            return;
        }
        if (cameraManager.isCameraAvailable()) {
            cameraManager.startPreview(previewView, this);
        }
    }

    private void finishTransition(String message) {
        transitioning = false;
        pendingStartAfterReconfigure = false;
        restartAfterResolutionSwitch = false;
        resolutionSpinner.setEnabled(true);
        recordButton.setEnabled(true);
        showStatus(message);
    }

    @Override
    public void onCameraStateChanged(CameraState newState) {
        mainHandler.post(() -> {
            lastEvent = "state=" + newState;
            if (newState == CameraState.OPENED) {
                startPreviewIfPossible();
            }
            refreshDebugDetails();
        });
    }

    @Override
    public void onCameraError(String errorMessage) {
        mainHandler.post(() -> {
            pendingResolution = null;
            pendingStartAfterReconfigure = false;
            restartAfterResolutionSwitch = false;
            transitioning = false;
            resolutionSpinner.setEnabled(true);
            recordButton.setEnabled(true);
            lastEvent = "error=" + errorMessage;
            showStatus("Error: " + errorMessage);
        });
    }

    @Override
    public void onRecordingStarted() {
        mainHandler.post(() -> {
            isRecording = true;
            transitioning = false;
            pendingStartAfterReconfigure = false;
            resolutionSpinner.setEnabled(true);
            recordButton.setEnabled(true);
            recordButton.setText(R.string.video_debug_stop);
            lastEvent = "recording started";
            showStatus("Recording");
            resetSampling();
        });
    }

    @Override
    public void onRecordingStopped() {
        mainHandler.post(() -> {
            isRecording = false;
            recordButton.setText(R.string.video_debug_start);
            lastEvent = "recording stopped";
            if (pendingResolution != null && !leaving) {
                mainHandler.removeCallbacks(applyPendingResolutionRunnable);
                mainHandler.postDelayed(applyPendingResolutionRunnable, RECONFIGURE_DELAY_MS);
                showStatus("Preparing resolution switch...");
            } else {
                finishTransition("Ready");
            }
        });
    }

    @Override
    public void onPhotoCaptured(String filePath) {
        lastEvent = "file=" + filePath;
    }

    @Override
    public void onPreviewStarted() {
        mainHandler.post(() -> {
            lastEvent = "preview started";
            if (pendingStartAfterReconfigure && !leaving) {
                pendingStartAfterReconfigure = false;
                showStatus("Restarting recorder at new resolution...");
                mainHandler.postDelayed(() -> {
                    if (!leaving && cameraManager != null) {
                        cameraManager.startRecording();
                    }
                }, 100L);
            } else if (!isRecording && !transitioning) {
                showStatus("Ready");
            }
            refreshDebugDetails();
        });
    }

    private void refreshDebugDetails() {
        if (debugPager == null || cameraManager == null) {
            return;
        }
        CameraConfig config = cameraManager.getCurrentConfig();
        RecordingStats stats = cameraManager.getRecordingStats();
        long now = SystemClock.elapsedRealtime();
        File outputFile = stats.getOutputFile();
        long fileBytes = outputFile != null && outputFile.exists() ? outputFile.length() : 0L;
        long sampleElapsed = lastSampleElapsedMs == 0L ? 0L : now - lastSampleElapsedMs;
        long deltaBytes = lastSamplePath != null && outputFile != null
                && outputFile.getAbsolutePath().equals(lastSamplePath)
                ? fileBytes - lastSampleBytes : 0L;
        long deltaFrames = lastSamplePath != null && outputFile != null
                && outputFile.getAbsolutePath().equals(lastSamplePath)
                ? stats.getFrameCount() - lastSampleFrames : 0L;
        double sampleBitrate = sampleElapsed > 0L
                ? deltaBytes * 8d * 1000d / sampleElapsed : 0d;
        double averageBitrate = stats.getRecordingStartElapsedMs() > 0L && now > stats.getRecordingStartElapsedMs()
                ? fileBytes * 8d * 1000d / (now - stats.getRecordingStartElapsedMs()) : 0d;
        double sampleFps = sampleElapsed > 0L ? deltaFrames * 1000d / sampleElapsed : 0d;
        double averageFps = stats.getRecordingStartElapsedMs() > 0L && now > stats.getRecordingStartElapsedMs()
                ? stats.getFrameCount() * 1000d / (now - stats.getRecordingStartElapsedMs()) : 0d;
        lastSampleElapsedMs = now;
        lastSamplePath = outputFile == null ? null : outputFile.getAbsolutePath();
        lastSampleBytes = fileBytes;
        lastSampleFrames = stats.getFrameCount();

        long freeBytes = CameraUtils.getAvailableStorageSpace();
        long budgetBytes = CameraUtils.getRecordingStorageBudget();
        String sessionDuration = formatDuration(
                durationSince(stats.getRecordingStartElapsedMs(), now));
        String segmentDuration = formatDuration(
                durationSince(stats.getSegmentStartElapsedMs(), now));

        StringBuilder overview = new StringBuilder(1800);
        appendLine(overview, "VIDEO ENGINEERING / SUMMARY");
        appendLine(overview, "===========================");
        appendLine(overview, "event                 : " + lastEvent);
        appendLine(overview, "camera state          : " + cameraManager.getCurrentState());
        appendLine(overview, "camera API            : " + cameraManager.getCameraApiType());
        appendLine(overview, "strategy              : " + stats.getStrategyName());
        appendLine(overview, "recording             : " + isRecording);
        appendLine(overview, "transitioning         : " + transitioning);
        appendLine(overview, "");
        appendLine(overview, "LIVE CAPTURE");
        appendLine(overview, "file growth bitrate   : " + formatBitrate(sampleBitrate));
        appendLine(overview, "average file bitrate  : " + formatBitrate(averageBitrate));
        appendLine(overview, "window FPS            : " + formatFps(sampleFps));
        appendLine(overview, "average FPS           : " + formatFps(averageFps));
        appendLine(overview, "frame count           : " + stats.getFrameCount());
        appendLine(overview, "last sensor timestamp : " + stats.getLastFrameTimestampNs() + " ns");

        StringBuilder encoder = new StringBuilder(1800);
        appendLine(encoder, "CONFIGURATION");
        appendLine(encoder, "capture mode          : "
                + value(config == null ? null : config.getCaptureMode()));
        appendLine(encoder, "video resolution      : "
                + resolutionText(config == null ? null : config.getResolution()));
        appendLine(encoder, "photo resolution      : "
                + value(config == null ? null : config.getPhotoResolution()));
        appendLine(encoder, "quality               : "
                + value(config == null ? null : config.getQuality()));
        appendLine(encoder, "capture FPS           : "
                + (config == null ? "-" : config.getFrameRate()));
        appendLine(encoder, "requested codec       : "
                + value(config == null ? null : config.getVideoCodec()));
        appendLine(encoder, "target bitrate        : " + formatBitrate(stats.getTargetBitrate()));
        appendLine(encoder, "audio requested       : "
                + (config != null && config.isAudioEnabled()));
        appendLine(encoder, "save location         : "
                + value(config == null ? null : config.getSaveLocation()));
        appendLine(encoder, "");
        appendLine(encoder, "ENCODER / MEDIARECORDER");
        appendLine(encoder, "applied codec         : " + value(stats.getAppliedCodec()));
        appendLine(encoder, "video size            : " + stats.getWidth() + "x" + stats.getHeight());
        appendLine(encoder, "capture FPS           : " + stats.getCaptureFps());
        appendLine(encoder, "playback FPS          : " + stats.getPlaybackFps());
        appendLine(encoder, "profile bitrate       : " + formatBitrate(stats.getProfileBitrate()));
        appendLine(encoder, "audio active          : " + stats.isAudioEnabled());
        appendLine(encoder, "high-speed mode       : " + (stats.getCaptureFps() > 30));
        appendLine(encoder, "segment limit         : " + formatDuration(stats.getSegmentDurationMs()));
        appendLine(encoder, "last sensor timestamp : " + stats.getLastFrameTimestampNs() + " ns");

        StringBuilder storage = new StringBuilder(1200);
        appendLine(storage, "FILE / SEGMENT");
        appendLine(storage, "session duration      : " + sessionDuration);
        appendLine(storage, "segment index         : " + stats.getSegmentIndex());
        appendLine(storage, "segment duration      : " + segmentDuration);
        appendLine(storage, "current segment file  : " + value(outputFile));
        appendLine(storage, "current file size     : " + CameraUtils.formatFileSize(fileBytes));
        appendLine(storage, "");
        appendLine(storage, "STORAGE");
        appendLine(storage, "external total       : " + formatBytes(getExternalTotalBytes()));
        appendLine(storage, "external free        : " + formatBytes(freeBytes));
        appendLine(storage, "reserved for system  : "
                + formatBytes(CameraUtils.MINIMUM_FREE_STORAGE_BYTES));
        appendLine(storage, "recording budget     : " + formatBytes(budgetBytes));
        appendLine(storage, "reserve safe         : "
                + (freeBytes > CameraUtils.MINIMUM_FREE_STORAGE_BYTES));

        StringBuilder device = new StringBuilder(1600);
        appendLine(device, "CAMERA / EVENT");
        appendLine(device, "camera id             : "
                + value(config == null ? null : config.getCameraId()));
        appendLine(device, "camera state          : " + cameraManager.getCurrentState());
        appendLine(device, "camera API            : " + cameraManager.getCameraApiType());
        appendLine(device, "strategy              : " + stats.getStrategyName());
        appendLine(device, "last error            : " + value(stats.getLastError()));
        appendLine(device, "");
        appendLine(device, "PROCESS / DEVICE");
        appendLine(device, "pid                  : " + Process.myPid());
        appendLine(device, "thread                : " + Thread.currentThread().getName());
        appendLine(device, "heap native          : " + formatBytes(Debug.getNativeHeapAllocatedSize()));
        appendLine(device, "heap Java used       : " + formatBytes(Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory()));
        appendLine(device, "device                : " + Build.MANUFACTURER + " " + Build.MODEL);
        appendLine(device, "Android               : " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        appendLine(device, "VM                    : " + System.getProperty("java.vm.name"));
        appendLine(device, "supported resolutions : " + resolutionOptions);

        debugPageText[0] = overview.toString();
        debugPageText[1] = encoder.toString();
        debugPageText[2] = storage.toString();
        debugPageText[3] = device.toString();
        if (debugPagerAdapter != null) {
            debugPagerAdapter.updatePages();
        }
    }

    private String getDebugPageTitle(int position) {
        switch (position) {
            case 0:
                return getString(R.string.video_debug_tab_info);
            case 1:
                return getString(R.string.video_debug_tab_codec);
            case 2:
                return getString(R.string.video_debug_tab_disk);
            case 3:
                return getString(R.string.video_debug_tab_device);
            default:
                return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class DebugPagerAdapter
            extends RecyclerView.Adapter<DebugPagerAdapter.PageHolder> {
        private final TextView[] pageViews = new TextView[debugPageText.length];
        private final ScrollView[] pageScrollViews = new ScrollView[debugPageText.length];

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ScrollView scrollView = new ScrollView(parent.getContext());
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            scrollView.setFillViewport(true);
            scrollView.setBackgroundColor(ContextCompat.getColor(
                    VideoDebugActivity.this, R.color.black));

            TextView pageText = new TextView(parent.getContext());
            pageText.setTextColor(ContextCompat.getColor(
                    VideoDebugActivity.this, R.color.text_secondary_light));
            pageText.setTextSize(11f);
            pageText.setTypeface(Typeface.MONOSPACE);
            pageText.setLineSpacing(dp(1), 1f);
            pageText.setTextIsSelectable(true);
            int padding = dp(8);
            pageText.setPadding(padding, padding, padding, padding);
            scrollView.addView(pageText, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new PageHolder(scrollView, pageText);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            pageViews[position] = holder.pageText;
            pageScrollViews[position] = holder.scrollView;
            holder.pageText.setText(debugPageText[position]);
        }

        @Override
        public int getItemCount() {
            return debugPageText.length;
        }

        void updatePages() {
            for (int i = 0; i < pageViews.length; i++) {
                TextView pageText = pageViews[i];
                ScrollView pageScrollView = pageScrollViews[i];
                String newText = debugPageText[i];
                if (pageText == null || pageScrollView == null || newText == null
                        || newText.contentEquals(pageText.getText())) {
                    continue;
                }
                int scrollX = pageScrollView.getScrollX();
                int scrollY = pageScrollView.getScrollY();
                pageText.setText(newText);
                pageScrollView.post(() -> pageScrollView.scrollTo(scrollX, scrollY));
            }
        }

        final class PageHolder extends RecyclerView.ViewHolder {
            private final ScrollView scrollView;
            private final TextView pageText;

            PageHolder(@NonNull ScrollView scrollView, TextView pageText) {
                super(scrollView);
                this.scrollView = scrollView;
                this.pageText = pageText;
            }
        }
    }

    private void resetSampling() {
        lastSamplePath = null;
        lastSampleElapsedMs = 0L;
        lastSampleBytes = 0L;
        lastSampleFrames = 0L;
    }

    private long durationSince(long startElapsedMs, long now) {
        return startElapsedMs > 0L && now >= startElapsedMs ? now - startElapsedMs : 0L;
    }

    private long getExternalTotalBytes() {
        try {
            StatFs statFs = new StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            return statFs.getTotalBytes();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private String formatBytes(long bytes) {
        return bytes <= 0L ? "-" : CameraUtils.formatFileSize(bytes);
    }

    private String formatBitrate(double bitsPerSecond) {
        if (bitsPerSecond <= 0d) {
            return "-";
        }
        return String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000d);
    }

    private String formatFps(double fps) {
        return fps <= 0d ? "-" : String.format(Locale.US, "%.2f", fps);
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "00:00.000";
        }
        long minutes = durationMs / 60_000L;
        long seconds = (durationMs % 60_000L) / 1_000L;
        long millis = durationMs % 1_000L;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private String resolutionText(Resolution resolution) {
        return resolution == null ? "-" : resolution.getWidth() + "x" + resolution.getHeight();
    }

    private String value(Object object) {
        return object == null ? "-" : String.valueOf(object);
    }

    private void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    private void showStatus(String status) {
        if (statusText != null) {
            statusText.setText(status);
        }
    }

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private Quality matchQuality(Resolution resolution) {
        if (resolution == null) {
            return Quality.DEFAULT;
        }
        if (resolution.getWidth() == 2560 && resolution.getHeight() == 1440) {
            return Quality.QHD;
        }
        if (resolution.getWidth() == 1920 && resolution.getHeight() == 1080) {
            return Quality.FULL_HD;
        }
        if (resolution.getWidth() == 1280 && resolution.getHeight() == 720) {
            return Quality.HD;
        }
        long area = (long) resolution.getWidth() * resolution.getHeight();
        Quality best = Quality.DEFAULT;
        long bestDelta = Long.MAX_VALUE;
        for (Quality candidate : Quality.selectableValues()) {
            long candidateArea = (long) candidate.getWidth() * candidate.getHeight();
            long delta = Math.abs(candidateArea - area);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }
        return best;
    }

    private int frameRateFor(Resolution resolution) {
        return Resolution.HD_720P.equals(resolution) ? 120 : 30;
    }
}
