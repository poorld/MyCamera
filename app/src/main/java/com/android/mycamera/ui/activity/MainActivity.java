package com.android.mycamera.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import com.android.mycamera.focus.GeminiFocusView;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.CameraState;
import com.android.mycamera.utils.CameraUtils;

public class MainActivity extends BaseAct implements CameraStateObserver {
    
    private static final String[] REQUIRED_PERMISSIONS = CameraUtils.getRequiredPermissions();
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    public static final String TAG = "MainActivity";
    
    private CameraManager mCameraManager;
    private TextureView cameraPreview;
    private GeminiFocusView focusView;
    private ImageButton captureButton;
    private ImageButton settingsButton;
    private ImageButton switchCameraButton;
    private ImageButton flashButton;
    private ImageButton photoModeButton;
    private ImageButton videoModeButton;
    private ProgressBar loadingIndicator;
    private TextView recordingTime;
    private TextView statusText;
    private View apiSwitcherPanel;
    private ImageButton apiSwitcherButton;
    private TextView mediaCodecTestButton;
    private RadioGroup apiRadioGroup;
    private RadioButton apiCamera1;
    private RadioButton apiCamera2;
    private RadioButton apiCameraX;
    private TextView settingsResolutionText;
    private TextView settingsFramerateText;

    private boolean isVideoMode = false;
    private boolean isRecording = false;
    private boolean isSyncingApiSelection = false;
    private Handler recordingTimerHandler;
    private long recordingStartTime = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_camera);
        
        initializeViews();
        setupClickListeners();
        
        recordingTimerHandler = new Handler(Looper.getMainLooper());

        CameraUtils.createCameraDirectory();
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
        switchCameraButton = findViewById(R.id.switchCameraButton);
        flashButton = findViewById(R.id.flashButton);
        photoModeButton = findViewById(R.id.photoModeButton);
        videoModeButton = findViewById(R.id.videoModeButton);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        recordingTime = findViewById(R.id.recordingTime);
        statusText = findViewById(R.id.statusText);
        apiSwitcherPanel = findViewById(R.id.apiSwitcherPanel);
        apiSwitcherButton = findViewById(R.id.apiSwitcherButton);
        mediaCodecTestButton = findViewById(R.id.mediaCodecTestButton);
        apiRadioGroup = findViewById(R.id.apiRadioGroup);
        apiCamera1 = findViewById(R.id.apiCamera1);
        apiCamera2 = findViewById(R.id.apiCamera2);
        apiCameraX = findViewById(R.id.apiCameraX);
        settingsResolutionText = findViewById(R.id.settings_resolution_text);
        settingsFramerateText = findViewById(R.id.settings_framerate_text);

        updateModeButtons();
    }

    private void initializeCameraManager() {
        Log.d(TAG, "initializeCameraManager: ");
        mCameraManager = CameraManager.getInstance(this);
        mCameraManager.addStateObserver(this);
        mCameraManager.preInitializeCamera();
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private void setupClickListeners() {
        captureButton.setOnClickListener(v -> handleCaptureAction());
        settingsButton.setOnClickListener(v -> openSettings());
        switchCameraButton.setOnClickListener(v -> switchCamera());
        flashButton.setOnClickListener(v -> toggleFlash());
        photoModeButton.setOnClickListener(v -> setPhotoMode());
        videoModeButton.setOnClickListener(v -> setVideoMode());
        apiSwitcherButton.setOnClickListener(v -> {
            if (apiSwitcherPanel.getVisibility() == View.VISIBLE) {
                apiSwitcherPanel.setVisibility(View.GONE);
            } else {
                apiSwitcherPanel.setVisibility(View.VISIBLE);
            }
        });
        mediaCodecTestButton.setOnClickListener(v ->
                startActivity(new Intent(this, MediaCodecTestActivity.class)));
        
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


    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        } else {

            if (mCameraManager != null) {
                if (mCameraManager.isBackgroundRecordingEnabled()) {
                    return;
                }
            }

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
        }

    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: ");

        /*if (mCameraManager.isBackgroundReviewEnabled()) {
            return;
        }

        if (mCameraManager.isBackgroundRecordingActive()) {
            return;
        }*/
        if (mCameraManager.isBackgroundRecordingEnabled()) {
            return;
        }

        release();
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void release() {
        Log.d(TAG, "release: ");
        if (isRecording) {
            stopRecording();
        }
        stopPreview();
        closeCamera();
        stopRecordingTimer();
        if (mCameraManager != null) {
            mCameraManager.removeStateObserver(this);
            mCameraManager.release();
            mCameraManager = null;
        }
    }
    
    private void initializeCamera() {
        Log.d(TAG, "initializeCamera: ");
        loadingIndicator.setVisibility(View.VISIBLE);
        statusText.setText("Initializing camera...");
        mCameraManager.initializeCamera(this);
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
        if (isVideoMode) {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        } else {
            capturePhoto();
        }
    }
    
    private void startRecording() {
        Log.d(TAG, "startRecording: ");
        mCameraManager.startRecording();
    }
    
    private void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        mCameraManager.stopRecording();
    }

    private void stopPreview() {
        Log.d(TAG, "stopPreview: ");
        mCameraManager.stopPreview();
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera: ");
        mCameraManager.closeCamera();
    }
    
    private void capturePhoto() {
        mCameraManager.capturePhoto();
    }
    
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }
    
    private void switchCamera() {
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
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleFlash() {
        if (mCameraManager != null) {
            mCameraManager.toggleFlash();
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
        isVideoMode = false;
        updateModeButtons();
        updateCaptureButton();
    }
    
    private void setVideoMode() {
        isVideoMode = true;
        updateModeButtons();
        updateCaptureButton();
    }
    
    private void updateModeButtons() {
        photoModeButton.setAlpha(isVideoMode ? 0.5f : 1.0f);
        videoModeButton.setAlpha(isVideoMode ? 1.0f : 0.5f);
    }
    
    private void updateCaptureButton() {
        if (isVideoMode) {
            captureButton.setImageResource(isRecording ? R.drawable.ic_stop_button : R.drawable.ic_record_button);
        } else {
            captureButton.setImageResource(R.drawable.ic_capture_button);
        }
    }
    
    private void updateFlashButton() {
        if (mCameraManager != null && mCameraManager.isFlashAvailable()) {
            flashButton.setEnabled(true);
            flashButton.setImageResource(mCameraManager.isFlashEnabled() ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        } else {
            flashButton.setEnabled(false);
            flashButton.setImageResource(R.drawable.ic_flash_off);
        }
    }
    
    private void switchCameraApi(CameraApiType apiType) {
        loadingIndicator.setVisibility(View.VISIBLE);
        statusText.setText("Switching camera API...");
        mCameraManager.switchCameraApi(apiType);
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
                initializeCamera();
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
                    mCameraManager.startPreview(cameraPreview, this);
                    updateFlashButton();
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
            recordingTime.setVisibility(View.VISIBLE);
            startRecordingTimer();
            updateCaptureButton();
        });
    }
    
    @Override
    public void onRecordingStopped() {
        Log.d(TAG, "onRecordingStopped: ");
        runOnUiThread(() -> {
            isRecording = false;
            recordingTime.setVisibility(View.GONE);
            stopRecordingTimer();
            updateCaptureButton();
        });
    }
    
    @Override
    public void onPhotoCaptured(String filePath) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Photo saved: " + filePath, Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onPreviewStarted() {
        runOnUiThread(() -> {
            loadingIndicator.setVisibility(View.GONE);
            statusText.setText("Ready");
            updateFlashButton();
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

        if (apiType == CameraApiType.CAMERA1 || apiType == CameraApiType.CAMERA2) {
            com.android.mycamera.model.Resolution resolution = config.getResolution();
            if (resolution != null) {
                settingsResolutionText.setText(resolution.getWidth() + "x" + resolution.getHeight());
                settingsResolutionText.setVisibility(View.VISIBLE);
            } else {
                settingsResolutionText.setVisibility(View.GONE);
            }
        } else { // CameraX
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
