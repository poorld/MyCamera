package com.android.mycamera.ui.activity;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.hardware.camera2.CameraAccessException;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.camera.helper.BackgroundRecordingHelper;
import com.android.mycamera.camera.manager.CameraManager;
import com.android.mycamera.camera.observer.CameraStateObserver;
import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.focus.GeminiFocusView;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.model.CaptureMode;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.ui.view.ZoomDialView;
import com.android.mycamera.utils.CameraUtils;
import com.android.mycamera.utils.SettingsManager;
import com.android.mycamera.utils.SystemPropertyUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends BaseAct implements CameraStateObserver {
    
    private static final String[] REQUIRED_PERMISSIONS = CameraUtils.getRequiredPermissions();
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final int Y1_RECORD_KEY = KeyEvent.KEYCODE_F10;
    private static final int EXPOSURE_SLIDER_STEPS = 1000;
    private static final String PROP_YUV_DUMP_ENABLE = "vendor.debug.p2f.dump.enable";
    private static final String PROP_YUV_DUMP_CONTINUE = "vendor.debug.camera.continue.dump";
    // This product's camera HAL advertises flash support, but no torch is wired.
    private static final boolean FLASH_UI_ENABLED = false;
    private static final String OIS_AF_NODE_PATH = "/sys/class/sensordrv/kd_camera_hw/imx563_ois_vldo28";
    private static final String DENOISE_NODE_PATH = "/sys/class/leds/alermled/denoise";

    public static final String TAG = "MainActivity";
    private MyBr myBr;

    private class MyBr extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            switchCamera();
        }
    }
    
    private CameraManager mCameraManager;
    private TextureView cameraPreview;
    private GeminiFocusView focusView;
    private ImageButton captureButton;
    private ImageButton settingsButton;
    private ImageButton switchCameraButton;
    private ImageButton focusAfButton;
    private ImageButton exposureButton;
    private ImageButton flashButton;
    private ImageButton galleryButton;
    private ImageButton denoiseButton;
    private ImageButton photoModeButton;
    private ImageButton videoModeButton;
    private View captureFlashOverlay;
    private ProgressBar loadingIndicator;
    private TextView recordingTime;
    private TextView statusText;
    private View apiSwitcherPanel;
    private ImageButton apiSwitcherButton;
    private RadioGroup apiRadioGroup;
    private RadioButton apiCamera1;
    private RadioButton apiCamera2;
    private RadioButton apiCameraX;
    private TextView settingsResolutionText;
    private TextView settingsFramerateText;
    private View zoomQuickBar;
    private ZoomDialView zoomDial;
    private TextView zoomMinText;
    private TextView zoomCurrentText;
    private TextView zoomThirdText;
    private TextView zoomMaxText;
    private View exposureControlsPanel;
    private Button exposureAutoButton;
    private Button sceneSnapshotButton;
    private Button sceneHandheldNightButton;
    private Button sceneTripodLongExposureButton;
    private Button sceneSunnyOutdoorButton;
    private SeekBar isoSeekBar;
    private SeekBar shutterSeekBar;
    private TextView isoValueText;
    private TextView shutterValueText;

    private boolean isVideoMode = false;
    private boolean isRecording = false;
    private boolean isFocusAfEnabled = false;
    private boolean isDenoiseEnabled = false;
    private boolean isSyncingApiSelection = false;
    private SettingsManager settingsManager;
    private boolean isSyncingExposureControls = false;
    private Handler recordingTimerHandler;
    private final Handler hardwareKeyHandler = new Handler(Looper.getMainLooper());
    private long recordingStartTime = 0;
    private boolean recordKeyLongPressHandled;
    private boolean powerKeyLongPressHandled;
    private Range<Integer> supportedIsoRange;
    private Range<Long> supportedExposureTimeRange;
    private final Runnable recordKeyLongPressRunnable = () -> {
        recordKeyLongPressHandled = true;
        toggleRecordingFromHardwareKey();
    };
    private final Runnable powerKeyLongPressRunnable = () -> {
        powerKeyLongPressHandled = true;
        switchCamera();
    };
    private final Runnable hideExposureControlsRunnable = () -> {
        if (exposureControlsPanel != null) {
            exposureControlsPanel.setVisibility(View.GONE);
        }
    };
    private final Runnable hideZoomDialRunnable = () -> {
        if (zoomDial != null) zoomDial.setVisibility(View.GONE);
    };
    private float[] zoomStops = new float[]{1f};
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_camera);
        
        initializeViews();
        setupClickListeners();
        
        recordingTimerHandler = new Handler(Looper.getMainLooper());

        CameraUtils.createCameraDirectory(this);

        myBr = new MyBr();
        registerReceiver(myBr, new IntentFilter("android.intent.action.POWEROFF"));


    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "onNewIntent: reuse existing MainActivity");
    }
    
    private void initializeViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        focusView = findViewById(R.id.focusView);
        captureButton = findViewById(R.id.captureButton);
        settingsButton = findViewById(R.id.settingsButton);
        focusAfButton = findViewById(R.id.focusAfButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        exposureButton = findViewById(R.id.exposureButton);
        flashButton = findViewById(R.id.flashButton);
        galleryButton = findViewById(R.id.galleryButton);
        denoiseButton = findViewById(R.id.denoiseButton);
        photoModeButton = findViewById(R.id.photoModeButton);
        videoModeButton = findViewById(R.id.videoModeButton);
        captureFlashOverlay = findViewById(R.id.captureFlashOverlay);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        recordingTime = findViewById(R.id.recordingTime);
        statusText = findViewById(R.id.statusText);
        apiSwitcherPanel = findViewById(R.id.apiSwitcherPanel);
        apiSwitcherButton = findViewById(R.id.apiSwitcherButton);
        apiRadioGroup = findViewById(R.id.apiRadioGroup);
        apiCamera1 = findViewById(R.id.apiCamera1);
        apiCamera2 = findViewById(R.id.apiCamera2);
        apiCameraX = findViewById(R.id.apiCameraX);
        settingsResolutionText = findViewById(R.id.settings_resolution_text);
        settingsFramerateText = findViewById(R.id.settings_framerate_text);
        zoomQuickBar = findViewById(R.id.zoomQuickBar);
        zoomDial = findViewById(R.id.zoomDial);
        zoomMinText = findViewById(R.id.zoomMinText);
        zoomCurrentText = findViewById(R.id.zoomCurrentText);
        zoomThirdText = findViewById(R.id.zoomThirdText);
        zoomMaxText = findViewById(R.id.zoomMaxText);
        exposureControlsPanel = findViewById(R.id.exposureControlsPanel);
        exposureAutoButton = findViewById(R.id.exposureAutoButton);
        sceneSnapshotButton = findViewById(R.id.sceneSnapshotButton);
        sceneHandheldNightButton = findViewById(R.id.sceneHandheldNightButton);
        sceneTripodLongExposureButton = findViewById(R.id.sceneTripodLongExposureButton);
        sceneSunnyOutdoorButton = findViewById(R.id.sceneSunnyOutdoorButton);
        isoSeekBar = findViewById(R.id.isoSeekBar);
        shutterSeekBar = findViewById(R.id.shutterSeekBar);
        isoValueText = findViewById(R.id.isoValueText);
        shutterValueText = findViewById(R.id.shutterValueText);

        settingsManager = new SettingsManager(this);
        applyTopControlsSafeArea();
        updateModeButtons();
        updateApiSwitcherVisibility();
        refreshFocusAfStateFromNode();
        setDenoiseEnabled(false, false);
    }

    private void applyTopControlsSafeArea() {
        View topControls = findViewById(R.id.topControls);
        if (topControls == null) return;

        int baseLeft = topControls.getPaddingLeft();
        int baseTop = topControls.getPaddingTop();
        int baseRight = topControls.getPaddingRight();
        int baseBottom = topControls.getPaddingBottom();
        topControls.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(baseLeft + safeInsets.left,
                    baseTop,
                    baseRight + safeInsets.right,
                    baseBottom);
            return windowInsets;
        });
        topControls.requestApplyInsets();
    }

    private void initializeCameraManager() {
        Log.d(TAG, "initializeCameraManager: ");
        mCameraManager = CameraManager.getInstance(this);
        mCameraManager.addStateObserver(this);
        mCameraManager.preInitializeCamera();
    }
    
    private void setupClickListeners() {
        captureButton.setOnClickListener(v -> handleCaptureAction());
        settingsButton.setOnClickListener(v -> openSettings());
        settingsButton.setOnLongClickListener(v -> {
            requestYuvDumpFrame();
            return true;
        });
        focusAfButton.setOnClickListener(v -> toggleFocusAf());
        switchCameraButton.setOnClickListener(v -> switchCamera());
        flashButton.setOnClickListener(v -> toggleFlash());
        galleryButton.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        denoiseButton.setOnClickListener(v -> toggleDenoise());
        photoModeButton.setOnClickListener(v -> setPhotoMode());
        videoModeButton.setOnClickListener(v -> setVideoMode());
        apiSwitcherButton.setOnClickListener(v -> {
            if (apiSwitcherPanel.getVisibility() == View.VISIBLE) {
                apiSwitcherPanel.setVisibility(View.GONE);
            } else {
                exposureControlsPanel.setVisibility(View.GONE);
                apiSwitcherPanel.setVisibility(View.VISIBLE);
            }
        });
        exposureButton.setOnClickListener(v -> {
            if (exposureControlsPanel.getVisibility() == View.VISIBLE) {
                hardwareKeyHandler.removeCallbacks(hideExposureControlsRunnable);
                exposureControlsPanel.setVisibility(View.GONE);
            } else {
                apiSwitcherPanel.setVisibility(View.GONE);
                exposureControlsPanel.setVisibility(View.VISIBLE);
                scheduleExposureControlsHide();
            }
        });
        exposureAutoButton.setOnClickListener(v -> {
            if (mCameraManager != null) {
                mCameraManager.resetAutoExposure();
                updateManualExposureUi();
                scheduleExposureControlsHide();
            }
        });
        sceneSnapshotButton.setOnClickListener(v -> applyExposureScene(200, 4_000_000L));
        sceneHandheldNightButton.setOnClickListener(v -> applyExposureScene(800, 33_333_333L));
        sceneTripodLongExposureButton.setOnClickListener(v -> applyExposureScene(800, 1_000_000_000L));
        sceneSunnyOutdoorButton.setOnClickListener(v -> applyExposureScene(100, 2_000_000L));
        
        cameraPreview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mCameraManager != null && mCameraManager.isFocusSupported()) {
                    focusView.showFocusRing(event.getX(), event.getY());
                    mCameraManager.setFocusPoint(event.getX(), event.getY());
                }
                return true;
            }
            return false;
        });

        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isSyncingApiSelection) {
                return;
            }
            CameraApiType selectedApi = CameraApiType.CAMERAX;
            if (checkedId == R.id.apiCamera1) {
                selectedApi = CameraApiType.CAMERA1;
            } else if (checkedId == R.id.apiCamera2) {
                selectedApi = CameraApiType.CAMERA2;
            }
            switchCameraApi(selectedApi);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    apiSwitcherPanel.setVisibility(View.GONE);
                }
            }, 1000);
        });

        zoomQuickBar.setOnTouchListener((view, event) -> handleZoomTouch(event));
        setupManualExposureControls();


    }

    private void setupManualExposureControls() {
        isoSeekBar.setMax(EXPOSURE_SLIDER_STEPS);
        shutterSeekBar.setMax(EXPOSURE_SLIDER_STEPS);

        isoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || isSyncingExposureControls) return;
                applyManualExposureFromControls();
                scheduleExposureControlsHide();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                hardwareKeyHandler.removeCallbacks(hideExposureControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleExposureControlsHide();
            }
        });
        shutterSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || isSyncingExposureControls) return;
                applyManualExposureFromControls();
                scheduleExposureControlsHide();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                hardwareKeyHandler.removeCallbacks(hideExposureControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleExposureControlsHide();
            }
        });
    }

    private void scheduleExposureControlsHide() {
        hardwareKeyHandler.removeCallbacks(hideExposureControlsRunnable);
        hardwareKeyHandler.postDelayed(hideExposureControlsRunnable, 3_000);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
            return;
        }
        // Settings only stages config; apply once when returning to preview.
        if (mCameraManager != null && mCameraManager.isConfigurationDirty()) {
            if (shouldKeepCameraInBackground() && mCameraManager.isCameraAvailable()) {
                loadingIndicator.setVisibility(View.VISIBLE);
                statusText.setText("Applying settings...");
                mCameraManager.applyStagedConfigurationIfNeeded();
                syncApiRadioFromConfig();
                updateSettingsDisplay();
            } else {
                // Full re-init path below will open with staged currentConfig.
                mCameraManager.applyStagedConfigurationIfNeeded();
            }
        }
        startCameraSessionIfNeeded();
    }
    
    @Override
    protected void onPause() {
        hardwareKeyHandler.removeCallbacks(recordKeyLongPressRunnable);
        hardwareKeyHandler.removeCallbacks(powerKeyLongPressRunnable);
        hardwareKeyHandler.removeCallbacks(hideExposureControlsRunnable);
        super.onPause();
        Log.d(TAG, "onPause: ");

        if (shouldKeepCameraInBackground()) {
            return;
        }

        release();
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(myBr);
    }

    private boolean shouldKeepCameraInBackground() {
        return mCameraManager != null
                && (mCameraManager.isBackgroundReviewEnabled()
                || mCameraManager.isBackgroundRecordingEnabled());
    }

    private void resetRecordingUiState() {
        isRecording = false;
        if (recordingTime != null) {
            recordingTime.setVisibility(View.GONE);
        }
        stopRecordingTimer();
    }

    private void release() {
        Log.d(TAG, "release: ");
        if (mCameraManager != null) {
            mCameraManager.removeStateObserver(this);
        }
        if (isRecording && mCameraManager != null) {
            try {
                mCameraManager.stopRecording();
            } catch (RuntimeException e) {
                Log.w(TAG, "stopRecording during release failed", e);
            }
        }
        resetRecordingUiState();
        updateModeButtons();
        updateCaptureButton();
        if (mCameraManager != null) {
            try {
                mCameraManager.stopPreview();
            } catch (RuntimeException e) {
                Log.w(TAG, "stopPreview during release failed", e);
            }
            try {
                mCameraManager.closeCamera();
            } catch (RuntimeException e) {
                Log.w(TAG, "closeCamera during release failed", e);
            }
            try {
                mCameraManager.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "release camera manager failed", e);
            }
            mCameraManager = null;
        }
    }
    
    private void startCameraSessionIfNeeded() {
        if (!hasAllPermissions()) {
            return;
        }

        if (shouldKeepCameraInBackground()) {
            updateApiSwitcherVisibility();
            refreshFocusAfStateFromNode();
            updateModeButtons();
            updateCaptureButton();
            updateSettingsDisplay();
            return;
        }

        resetRecordingUiState();
        initializeCameraManager();
        initializeCamera();

        CameraApiType cameraApiType = mCameraManager.getCameraApiType();
        isSyncingApiSelection = true;
        if (cameraApiType == CameraApiType.CAMERA1) {
            apiCamera1.setChecked(true);
        } else if (cameraApiType == CameraApiType.CAMERA2) {
            apiCamera2.setChecked(true);
        } else if (cameraApiType == CameraApiType.CAMERAX) {
            apiCameraX.setChecked(true);
        }
        isSyncingApiSelection = false;
        updateApiSwitcherVisibility();
        refreshFocusAfStateFromNode();
        updateModeButtons();
        updateCaptureButton();
    }

    private void initializeCamera() {
        Log.d(TAG, "initializeCamera: ");
        if (mCameraManager == null) {
            initializeCameraManager();
        }
        if (mCameraManager == null) {
            Log.e(TAG, "initializeCamera failed: camera manager is null");
            statusText.setText("Camera init failed");
            loadingIndicator.setVisibility(View.GONE);
            return;
        }
        loadingIndicator.setVisibility(View.VISIBLE);
        statusText.setText("Initializing camera...");
        mCameraManager.initializeCamera(this);
        // Safety timeout so resolution switch never leaves UI spinning forever.
        cameraPreview.postDelayed(() -> {
            if (loadingIndicator != null && loadingIndicator.getVisibility() == View.VISIBLE) {
                Log.w(TAG, "initializeCamera: loading timeout, force hide spinner");
                loadingIndicator.setVisibility(View.GONE);
                if (mCameraManager != null && mCameraManager.isCameraAvailable()) {
                    statusText.setText("Camera ready");
                    mCameraManager.startPreview(cameraPreview, MainActivity.this);
                    updateSettingsDisplay();
                } else {
                    statusText.setText("Camera init timeout");
                }
            }
        }, 2500);
    }
    
    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    private void handleCaptureAction() {
        Log.d(TAG, String.format("handleCaptureAction: isVideoMode=%s,isRecording=%s", isVideoMode, isRecording));
        if (isRecording) {
            stopRecording();
            return;
        }
        if (isVideoMode) {
            startRecording();
        } else {
            capturePhoto();
        }
    }

    private void toggleRecordingFromHardwareKey() {
        if (mCameraManager == null) {
            return;
        }
        if (isRecording) {
            stopRecording();
        } else {
            enterVideoModeForRecording();
            startRecording();
        }
    }

    /**
     * Long-press / record button: switch UI + CaptureMode to video without requiring
     * the user to tap the video tab first. Pipeline is restored as video after stop.
     */
    private void enterVideoModeForRecording() {
        isVideoMode = true;
        updateModeButtons();
        updateCaptureButton();
        if (mCameraManager != null) {
            // false: do not rebuild preview now; startRecording creates record session.
            mCameraManager.ensureCaptureMode(CaptureMode.VIDEO, false);
        }
        updateSettingsDisplay();
    }
    
    private void startRecording() {
        Log.d(TAG, "startRecording: ");
        if (mCameraManager == null) {
            Log.w(TAG, "startRecording ignored: camera manager is null");
            return;
        }
        enterVideoModeForRecording();
        mCameraManager.startRecording();
    }
    
    private void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (mCameraManager == null) {
            resetRecordingUiState();
            updateModeButtons();
            updateCaptureButton();
            return;
        }
        mCameraManager.stopRecording();
    }

    private void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        if (mCameraManager != null) {
            mCameraManager.stopPreview();
        }
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        if (mCameraManager != null) {
            mCameraManager.closeCamera();
        }
    }
    
    private void capturePhoto() {
        if (isRecording || mCameraManager == null) {
            return;
        }
        showCaptureFlash();
        mCameraManager.capturePhoto();
    }
    
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void requestYuvDumpFrame() {
        if (!"1".equals(SystemPropertyUtils.get(PROP_YUV_DUMP_ENABLE, "0"))) {
            Toast.makeText(this, R.string.yuv_dump_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        hardwareKeyHandler.removeCallbacksAndMessages(null);
        SystemPropertyUtils.set(PROP_YUV_DUMP_CONTINUE, "0");
        hardwareKeyHandler.postDelayed(
                () -> SystemPropertyUtils.set(PROP_YUV_DUMP_CONTINUE, "1"), 150);
        hardwareKeyHandler.postDelayed(
                () -> SystemPropertyUtils.set(PROP_YUV_DUMP_CONTINUE, "0"), 1150);
        Toast.makeText(this, R.string.yuv_dump_triggered, Toast.LENGTH_SHORT).show();
    }
    
    private void switchCamera() {
        if (mCameraManager == null || mCameraManager.getCurrentConfig() == null) {
            return;
        }
        String currentId = mCameraManager.getCurrentConfig().getCameraId();
        android.hardware.camera2.CameraManager cameraManager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length <= 1) {
                Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
                return;
            }
            int currentIndex = -1;
            for (int i = 0; i < cameraIds.length; i++) {
                if (cameraIds[i].equals(currentId)) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex == -1) {
                currentIndex = 0;
            }
            int nextIndex = (currentIndex + 1) % cameraIds.length;
            mCameraManager.switchCamera(cameraIds[nextIndex]);
            cameraPreview.postDelayed(this::updateZoomUi, 500);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleFlash() {
        if (mCameraManager != null) {
            mCameraManager.toggleFlash();
            updateFlashButton();
            flashButton.postDelayed(this::updateFlashButton, 100);
        }
    }

    private void updateSwitchCameraButtonIcon() {
        android.hardware.camera2.CameraManager systemCameraManager = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            int cameraCount = systemCameraManager.getCameraIdList().length;
            if (switchCameraButton != null) {
                switch (cameraCount) {
                    case 1:
                        switchCameraButton.setImageResource(R.drawable.ic_camera_switch_1);
                        break;
                    case 2:
                        switchCameraButton.setImageResource(R.drawable.ic_camera_switch_2);
                        break;
                    case 3:
                        switchCameraButton.setImageResource(R.drawable.ic_camera_switch_3);
                        break;
                    default:
                        switchCameraButton.setImageResource(R.drawable.ic_camera_switch_4);
                        break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera count for icon update.", e);
            if (switchCameraButton != null) {
                switchCameraButton.setImageResource(R.drawable.ic_camera_switch);
            }
        }
    }
    
    private void setPhotoMode() {
        if (isRecording) {
            return;
        }
        if (!isVideoMode) {
            return;
        }
        isVideoMode = false;
        updateModeButtons();
        updateCaptureButton();
        reapplyCameraForModeSwitch("photo");
        Log.d(TAG, "setPhotoMode: isVideoMode=false");
    }
    
    private void setVideoMode() {
        if (isRecording) {
            return;
        }
        if (isVideoMode) {
            return;
        }
        isVideoMode = true;
        updateModeButtons();
        updateCaptureButton();
        reapplyCameraForModeSwitch("video");
        Log.d(TAG, "setVideoMode: isVideoMode=true");
    }

    /**
     * Photo/video sizes are independent. Re-open current API so preview/capture
     * pipelines pick up the active mode's configured size.
     */
    private void reapplyCameraForModeSwitch(String mode) {
        if (mCameraManager == null) {
            return;
        }
        CameraConfig config = mCameraManager.getCurrentConfig();
        if (config == null) {
            return;
        }
        CaptureMode captureMode = "video".equals(mode) ? CaptureMode.VIDEO : CaptureMode.PHOTO;
        CameraConfig.Builder configBuilder = new CameraConfig.Builder(config)
                .setCaptureMode(captureMode);
        if (captureMode == CaptureMode.PHOTO) {
            // High-speed recording settings must not leak into the JPEG pipeline.
            configBuilder.setFrameRate(30);
        }
        config = configBuilder.build();
        Log.d(TAG, "reapplyCameraForModeSwitch mode=" + mode
                + ", api=" + config.getApiType()
                + ", captureMode=" + config.getCaptureMode()
                + ", videoQuality=" + config.getQuality()
                + ", videoResolution=" + config.getResolution()
                + ", photoResolution=" + config.getPhotoResolution());
        // Set the loading state before reconfiguration. Camera2 may synchronously
        // notify OPENED while rebuilding the pipeline, and that callback closes it.
        loadingIndicator.setVisibility(View.VISIBLE);
        statusText.setText("Switching " + mode + " mode...");
        // Rebuild pipeline for the selected mode (Camera2: JPEG vs VIDEO_ENCODER).
        mCameraManager.updateConfiguration(config);
        updateSettingsDisplay();
    }
    
    private void updateModeButtons() {
        if (photoModeButton == null || videoModeButton == null) {
            return;
        }
        boolean videoSelected = isRecording || isVideoMode;
        int activeColor = getColor(R.color.zoom_active);
        int inactiveColor = 0xB3FFFFFF;
        photoModeButton.setClickable(!isRecording);
        photoModeButton.setEnabled(!isRecording);
        photoModeButton.setAlpha(1f);
        videoModeButton.setAlpha(1f);
        photoModeButton.setColorFilter(videoSelected ? inactiveColor : activeColor);
        videoModeButton.setColorFilter(videoSelected ? activeColor : inactiveColor);
        photoModeButton.setImageTintList(null);
        videoModeButton.setImageTintList(null);
        photoModeButton.setSelected(!videoSelected);
        videoModeButton.setSelected(videoSelected);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == Y1_RECORD_KEY && event.getRepeatCount() == 0) {
            recordKeyLongPressHandled = false;
            hardwareKeyHandler.postDelayed(recordKeyLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (keyCode == Y1_RECORD_KEY) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_POWER && event.getRepeatCount() == 0) {
            powerKeyLongPressHandled = false;
            hardwareKeyHandler.postDelayed(powerKeyLongPressRunnable,
                    ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == Y1_RECORD_KEY) {
            hardwareKeyHandler.removeCallbacks(recordKeyLongPressRunnable);
            if (!recordKeyLongPressHandled && !event.isCanceled() && !isRecording) {
                if (isVideoMode) {
                    setPhotoMode();
                }
                capturePhoto();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            hardwareKeyHandler.removeCallbacks(powerKeyLongPressRunnable);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    
    private void updateCaptureButton() {
        if (captureButton == null) {
            return;
        }
        if (isRecording) {
            captureButton.setImageResource(R.drawable.ic_stop_button);
        } else if (isVideoMode) {
            captureButton.setImageResource(R.drawable.ic_record_button);
        } else {
            captureButton.setImageResource(R.drawable.ic_capture_button);
        }
        captureButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.white)));
    }
    
    private void updateFlashButton() {
        if (flashButton == null) return;

        boolean flashAvailable = FLASH_UI_ENABLED
                && mCameraManager != null
                && mCameraManager.isFlashAvailable();
        flashButton.setVisibility(flashAvailable ? View.VISIBLE : View.GONE);
        if (!flashAvailable) return;

        flashButton.setEnabled(true);
        flashButton.setImageResource(mCameraManager.isFlashEnabled()
                ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
    }

    private boolean handleZoomTouch(MotionEvent event) {
        if (mCameraManager == null || !mCameraManager.isZoomSupported()) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            recordingTimerHandler.removeCallbacks(hideZoomDialRunnable);
            float progress = Math.max(0f, Math.min(1f, event.getX() / zoomQuickBar.getWidth()));
            int stopIndex = Math.round(progress * (zoomStops.length - 1));
            float zoom = zoomStops[stopIndex];
            mCameraManager.setZoom(zoom);
            updateZoomUi();
            zoomDial.setVisibility(View.VISIBLE);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            recordingTimerHandler.postDelayed(hideZoomDialRunnable, 900);
            return true;
        }
        return true;
    }

    private void updateZoomUi() {
        if (mCameraManager == null || !mCameraManager.isZoomSupported()) {
            zoomQuickBar.setVisibility(View.GONE);
            zoomDial.setVisibility(View.GONE);
            return;
        }
        float minZoom = mCameraManager.getMinZoom();
        float maxZoom = mCameraManager.getMaxZoom();
        float zoom = mCameraManager.getZoom();
        zoomStops = createZoomStops(minZoom, maxZoom);
        zoomQuickBar.setVisibility(View.VISIBLE);
        updateZoomLabel(zoomMinText, zoomStops[0], zoom);
        updateZoomLabel(zoomCurrentText, zoomStops[Math.min(1, zoomStops.length - 1)], zoom);
        updateZoomLabel(zoomThirdText, zoomStops[Math.min(2, zoomStops.length - 1)], zoom);
        updateZoomLabel(zoomMaxText, zoomStops[zoomStops.length - 1], zoom);
        zoomDial.setZoom(minZoom, maxZoom, zoom);
    }

    private void updateManualExposureUi() {
        boolean supported = mCameraManager != null && mCameraManager.isManualExposureSupported();
        exposureButton.setVisibility(supported ? View.VISIBLE : View.GONE);
        if (!supported) {
            exposureControlsPanel.setVisibility(View.GONE);
            supportedIsoRange = null;
            supportedExposureTimeRange = null;
            return;
        }

        supportedIsoRange = mCameraManager.getSupportedIsoRange();
        supportedExposureTimeRange = mCameraManager.getSupportedExposureTimeRange();
        if (supportedIsoRange == null || supportedExposureTimeRange == null) {
            exposureButton.setVisibility(View.GONE);
            exposureControlsPanel.setVisibility(View.GONE);
            return;
        }

        int iso = mCameraManager.getManualIso();
        long shutterTimeNs = mCameraManager.getManualExposureTimeNs();
        boolean isManualExposure = mCameraManager.isManualExposureEnabled();
        isSyncingExposureControls = true;
        isoSeekBar.setProgress(isManualExposure ? isoToProgress(iso) : 0);
        shutterSeekBar.setProgress(isManualExposure ? shutterToProgress(shutterTimeNs) : 0);
        isSyncingExposureControls = false;
        isoValueText.setText(isManualExposure ? "ISO " + iso : "AUTO");
        shutterValueText.setText(isManualExposure ? formatShutterTime(shutterTimeNs) : "AUTO");
        isoSeekBar.setEnabled(isManualExposure);
        shutterSeekBar.setEnabled(isManualExposure);
        isoSeekBar.setAlpha(isManualExposure ? 1f : 0.45f);
        shutterSeekBar.setAlpha(isManualExposure ? 1f : 0.45f);
        exposureAutoButton.setEnabled(isManualExposure);
        exposureAutoButton.setAlpha(isManualExposure ? 1f : 0.55f);
    }

    private void applyManualExposureFromControls() {
        if (mCameraManager == null || supportedIsoRange == null || supportedExposureTimeRange == null) {
            return;
        }
        int iso = isoFromProgress(isoSeekBar.getProgress());
        long shutterTimeNs = shutterFromProgress(shutterSeekBar.getProgress());
        mCameraManager.setManualExposure(iso, shutterTimeNs);
        isoValueText.setText("ISO " + iso);
        shutterValueText.setText(formatShutterTime(shutterTimeNs));
        exposureAutoButton.setEnabled(true);
        exposureAutoButton.setAlpha(1f);
    }

    private void applyExposureScene(int targetIso, long targetShutterTimeNs) {
        if (mCameraManager == null || supportedIsoRange == null || supportedExposureTimeRange == null) {
            return;
        }
        mCameraManager.setManualExposure(
                supportedIsoRange.clamp(targetIso),
                supportedExposureTimeRange.clamp(targetShutterTimeNs));
        updateManualExposureUi();
        scheduleExposureControlsHide();
    }


    private int isoToProgress(int iso) {
        long min = supportedIsoRange.getLower();
        long max = supportedIsoRange.getUpper();
        if (max <= min) return 0;
        return (int) Math.round((iso - min) * EXPOSURE_SLIDER_STEPS / (double) (max - min));
    }

    private int isoFromProgress(int progress) {
        long min = supportedIsoRange.getLower();
        long max = supportedIsoRange.getUpper();
        return (int) Math.round(min + (max - min) * progress / (double) EXPOSURE_SLIDER_STEPS);
    }

    private int shutterToProgress(long shutterTimeNs) {
        double min = Math.max(1d, supportedExposureTimeRange.getLower());
        double max = Math.max(min, supportedExposureTimeRange.getUpper());
        if (max == min) return 0;
        double value = Math.max(min, Math.min(max, shutterTimeNs));
        return (int) Math.round((Math.log(value) - Math.log(min))
                / (Math.log(max) - Math.log(min)) * EXPOSURE_SLIDER_STEPS);
    }

    private long shutterFromProgress(int progress) {
        double min = Math.max(1d, supportedExposureTimeRange.getLower());
        double max = Math.max(min, supportedExposureTimeRange.getUpper());
        if (max == min) return (long) min;
        return Math.round(Math.exp(Math.log(min) + (Math.log(max) - Math.log(min))
                * progress / EXPOSURE_SLIDER_STEPS));
    }

    private String formatShutterTime(long shutterTimeNs) {
        if (shutterTimeNs >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f s", shutterTimeNs / 1_000_000_000d);
        }
        long denominator = Math.max(1, Math.round(1_000_000_000d / shutterTimeNs));
        return "1/" + denominator + " s";
    }

    private float[] createZoomStops(float minZoom, float maxZoom) {
        java.util.ArrayList<Float> stops = new java.util.ArrayList<>();
        stops.add(minZoom);
        if (minZoom < 2f && maxZoom > 2f) stops.add(2f);
        if (minZoom < 3f && maxZoom > 3f) stops.add(3f);
        if (Math.abs(stops.get(stops.size() - 1) - maxZoom) > 0.05f) stops.add(maxZoom);
        float[] result = new float[stops.size()];
        for (int i = 0; i < stops.size(); i++) result[i] = stops.get(i);
        return result;
    }

    private void updateZoomLabel(TextView label, float value, float zoom) {
        label.setText(formatZoom(value));
        boolean selected = Math.abs(value - zoom) < 0.12f;
        label.setTextColor(getColor(selected ? R.color.zoom_active : R.color.white));
        label.setTextSize(selected ? 15f : 13f);
    }

    private String formatZoom(float zoom) {
        return zoom < 10f ? String.format(Locale.US, "%.1fx", zoom) : String.format(Locale.US, "%.0fx", zoom);
    }
    
    private void updateApiSwitcherVisibility() {
        if (apiSwitcherButton == null) {
            return;
        }
        boolean show = settingsManager != null && settingsManager.isShowApiSwitcherEnabled();
        apiSwitcherButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && apiSwitcherPanel != null) {
            apiSwitcherPanel.setVisibility(View.GONE);
        }
    }

    private void toggleFocusAf() {
        boolean targetEnabled = !isFocusAfEnabled;
        if (writeOisAfNode(targetEnabled ? "1" : "0")) {
            isFocusAfEnabled = targetEnabled;
            updateFocusAfButton();
            Toast.makeText(
                    this,
                    isFocusAfEnabled ? R.string.af_ois_enabled : R.string.af_ois_disabled,
                    Toast.LENGTH_SHORT
            ).show();
        } else {
            Toast.makeText(this, R.string.af_ois_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDenoise() {
        setDenoiseEnabled(!isDenoiseEnabled, true);
    }

    private void setDenoiseEnabled(boolean enabled, boolean showToast) {
        if (writeDenoiseNode(enabled ? "1" : "0")) {
            isDenoiseEnabled = enabled;
            updateDenoiseButton();
            if (showToast) {
                Toast.makeText(this,
                        enabled ? R.string.denoise_enabled : R.string.denoise_disabled,
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            isDenoiseEnabled = false;
            updateDenoiseButton();
            if (showToast) {
                Toast.makeText(this, R.string.denoise_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateDenoiseButton() {
        if (denoiseButton == null) {
            return;
        }
        int color = getColor(isDenoiseEnabled ? R.color.zoom_active : R.color.white);
        denoiseButton.setImageTintList(ColorStateList.valueOf(color));
        denoiseButton.setSelected(isDenoiseEnabled);
    }

    private boolean writeDenoiseNode(String value) {
        File node = new File(DENOISE_NODE_PATH);
        try (FileOutputStream outputStream = new FileOutputStream(node)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return true;
        } catch (IOException directWriteError) {
            Log.w(TAG, "Direct denoise node write failed, try shell", directWriteError);
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "echo " + value + " > " + DENOISE_NODE_PATH
            });
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            Log.e(TAG, "Shell denoise node write failed, exit=" + exitCode);
        } catch (Exception shellError) {
            Log.e(TAG, "Shell denoise node write failed", shellError);
        }
        return false;
    }

    private void refreshFocusAfStateFromNode() {
        String value = readOisAfNode();
        if (value != null) {
            isFocusAfEnabled = "1".equals(value.trim());
        }
        updateFocusAfButton();
    }

    private void updateFocusAfButton() {
        if (focusAfButton == null) {
            return;
        }
        int color = getColor(isFocusAfEnabled ? R.color.zoom_active : R.color.white);
        focusAfButton.setImageTintList(ColorStateList.valueOf(color));
        focusAfButton.setSelected(isFocusAfEnabled);
    }

    private String readOisAfNode() {
        File node = new File(OIS_AF_NODE_PATH);
        if (!node.exists()) {
            Log.w(TAG, "OIS/AF node missing: " + OIS_AF_NODE_PATH);
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(node))) {
            return reader.readLine();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read OIS/AF node", e);
            return null;
        }
    }

    private boolean writeOisAfNode(String value) {
        File node = new File(OIS_AF_NODE_PATH);
        try (FileOutputStream outputStream = new FileOutputStream(node)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return true;
        } catch (IOException directWriteError) {
            Log.w(TAG, "Direct OIS/AF node write failed, try shell", directWriteError);
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "echo " + value + " > " + OIS_AF_NODE_PATH
            });
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            Log.e(TAG, "Shell OIS/AF write failed, exit=" + exitCode);
        } catch (Exception shellError) {
            Log.e(TAG, "Shell OIS/AF node write failed", shellError);
        }
        return false;
    }

    private void switchCameraApi(CameraApiType apiType) {
        loadingIndicator.setVisibility(View.VISIBLE);
        statusText.setText("Switching camera API...");
        mCameraManager.switchCameraApi(apiType);
        // Effective API may differ (e.g. CameraX+2K forced Camera2, or CameraX demotes 2K->FHD).
        syncApiRadioFromConfig();
        updateSettingsDisplay();
    }

    private void syncApiRadioFromConfig() {
        if (mCameraManager == null || apiRadioGroup == null) {
            return;
        }
        CameraApiType effective = mCameraManager.getCameraApiType();
        isSyncingApiSelection = true;
        if (effective == CameraApiType.CAMERA1) {
            apiCamera1.setChecked(true);
        } else if (effective == CameraApiType.CAMERA2) {
            apiCamera2.setChecked(true);
        } else {
            apiCameraX.setChecked(true);
        }
        isSyncingApiSelection = false;
    }
    
    private void startRecordingTimer() {
        recordingStartTime = System.currentTimeMillis();
        recordingTimerHandler.postDelayed(recordingTimerRunnable, 1000);
    }
    
    private void stopRecordingTimer() {
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        recordingTime.setText("");
    }
    
    private final Runnable recordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long elapsed = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (elapsed / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;
                String timeString = String.format("%02d:%02d", minutes, seconds);
                recordingTime.setText(timeString);
                recordingTimerHandler.postDelayed(this, 1000);
            }
        }
    };
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasAllPermissions()) {
                startCameraSessionIfNeeded();
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
    
    @Override
    public void onCameraStateChanged(CameraState newState) {
        Log.d(TAG, "onCameraStateChanged: " + newState);

        runOnUiThread(() -> {
            loadingIndicator.setVisibility(View.GONE);
            switch (newState) {
                case INITIALIZING:
                    statusText.setText("Initializing...");
                    break;
                case OPENED:
                    statusText.setText("Camera ready");
                    // Only start preview on true open. Avoid restarting after stop-recording.
                    if (!isRecording) {
                        mCameraManager.startPreview(cameraPreview, this);
                    }
                    updateFlashButton();
                    updateZoomUi();
                    updateManualExposureUi();
                    updateSettingsDisplay();
                    updateSwitchCameraButtonIcon();
                    break;
                case PREVIEW_STARTED:
                    statusText.setText("Ready");
                    break;
                case RECORDING:
                    statusText.setText("Recording");
                case CAPTURING:
                case ERROR:
                case CLOSED:
                    break;
            }
        });
    }
    
    @Override
    public void onCameraError(String errorMessage) {
        runOnUiThread(() -> {
            loadingIndicator.setVisibility(View.GONE);
            statusText.setText("Error: " + errorMessage);
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        });
    }
    
    @Override
    public void onRecordingStarted() {
        Log.d(TAG, "onRecordingStarted: ");
        runOnUiThread(() -> {
            isRecording = true;
            isVideoMode = true;
            updateModeButtons();
            recordingTime.setVisibility(View.VISIBLE);
            startRecordingTimer();
            updateCaptureButton();
            // Show video resolution (e.g. 1920x1080), not still 36M photo size.
            updateSettingsDisplay();
            playRecordBeep();
        });
    }
    
    @Override
    public void onRecordingStopped() {
        Log.d(TAG, "onRecordingStopped: ");
        runOnUiThread(() -> {
            isRecording = false;
            // Stay in video mode after long-press record; keep video resolution on HUD.
            isVideoMode = true;
            recordingTime.setVisibility(View.GONE);
            stopRecordingTimer();
            updateModeButtons();
            updateCaptureButton();
            updateSettingsDisplay();
            playRecordBeep();
        });
    }

    private void playRecordBeep() {
        try {
            ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 250);
            hardwareKeyHandler.postDelayed(toneGenerator::release, 250);
        } catch (RuntimeException e) {
            Log.w(TAG, "playRecordBeep failed", e);
        }
    }
    
    @Override
    public void onPhotoCaptured(String filePath) {
    }

    private void showCaptureFlash() {
        if (captureFlashOverlay == null) return;
        captureFlashOverlay.animate().cancel();
        captureFlashOverlay.setAlpha(0.75f);
        captureFlashOverlay.setVisibility(View.VISIBLE);
        captureFlashOverlay.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> captureFlashOverlay.setVisibility(View.GONE))
                .start();
    }
    
    @Override
    public void onPreviewStarted() {
        runOnUiThread(() -> {
            loadingIndicator.setVisibility(View.GONE);
            statusText.setText("Ready");
            updateFlashButton();
            updateZoomUi();
            updateManualExposureUi();
        });
    }

    private void updateSettingsDisplay() {
        if (mCameraManager == null) return;

        com.android.mycamera.camera.config.CameraConfig config = mCameraManager.getCurrentConfig();
        if (config == null) {
            if (settingsResolutionText != null) settingsResolutionText.setVisibility(View.GONE);
            if (settingsFramerateText != null) settingsFramerateText.setVisibility(View.GONE);
            return;
        }

        CameraApiType apiType = mCameraManager.getCameraApiType();

        if (!isVideoMode) {
            // Still capture mode: show photo resolution.
            com.android.mycamera.model.PhotoResolution photoResolution =
                    com.android.mycamera.model.PhotoResolution.normalize(
                            config.getPhotoResolution(), config.getCameraId());
            settingsResolutionText.setText(photoResolution.getDisplayName());
            settingsResolutionText.setVisibility(View.VISIBLE);
            settingsFramerateText.setVisibility(View.GONE);
            return;
        } else if (apiType == CameraApiType.CAMERA1 || apiType == CameraApiType.CAMERA2) {
            com.android.mycamera.model.Resolution resolution = config.getResolution();
            if (resolution != null) {
                settingsResolutionText.setText(resolution.getWidth() + "x" + resolution.getHeight());
                settingsResolutionText.setVisibility(View.VISIBLE);
            } else {
                settingsResolutionText.setVisibility(View.GONE);
            }
        } else { // CameraX video
            com.android.mycamera.model.Quality quality = config.getQuality();
            if (quality != null) {
                settingsResolutionText.setText(quality.getDisplayName());
                settingsResolutionText.setVisibility(View.VISIBLE);
            } else {
                settingsResolutionText.setVisibility(View.GONE);
            }
        }

        int frameRate = config.getFrameRate();
        settingsFramerateText.setText(frameRate + " FPS");
        settingsFramerateText.setVisibility(View.VISIBLE);
    }
}
