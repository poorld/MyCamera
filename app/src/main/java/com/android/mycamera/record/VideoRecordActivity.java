package com.android.mycamera.record;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraInfo;
import androidx.camera.video.Quality;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

import java.util.Map;

public class VideoRecordActivity extends BaseAct implements RecordingCallback {

    public static final String TAG = "VideoRecordActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Button recordButton;
    private Button switchCameraButton;
    private RadioGroup apiRadioGroup;
    private TextView statusTextView;
    private TextView timeTextView;
    private String currentCameraId = "0";

    private CameraSetupHelper cameraSetupHelper;
    private RecordingTimer recordingTimer;

    private Quality currentQuality;
    private String currentResolution;
    private Integer currentFps;
    private String currentApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_record);
        initViews();
        cameraSetupHelper = new CameraSetupHelper(this);
        recordingTimer = new RecordingTimer(timeTextView);
        
        if (allPermissionsGranted()) {
            currentApi = "CameraX";
            setupSpinners();
            loadFragment(new CameraXFragment());
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void initViews() {
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        recordButton = findViewById(R.id.recordButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        apiRadioGroup = findViewById(R.id.apiRadioGroup);
        statusTextView = findViewById(R.id.statusTextView);
        timeTextView = findViewById(R.id.timeTextView);

        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isRecording()) return;
            cameraSetupHelper.clearAdapters();
            setupSpinners();
            switchCamParam();
        });

        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isRecording()) return;
                switchCamParam();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isRecording()) return;
                switchCamParam();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        recordButton.setOnClickListener(v -> toggleRecording());
        
        switchCameraButton.setOnClickListener(v -> {
            switchCamera();
            setupSpinners();
        });
    }

    private void switchCamParam() {
        String selectedApi = getSelectedApi();
        
        if ("CameraX".equals(selectedApi)) {
            updateCameraXParameters(selectedApi);
        } else {
            updateCameraParameters(selectedApi);
        }
    }

    public Map<String, Quality> getQualityMap() {
        return cameraSetupHelper.getQualityMap();
    }
    
    private void updateCameraXParameters(String selectedApi) {
        String qualityName = resolutionSpinner.getSelectedItem().toString();
        Quality quality = cameraSetupHelper.getQualityMap().get(qualityName);
        int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());
        
        if (currentQuality != quality || !"CameraX".equals(currentApi)) {
            Fragment selectedFragment = new CameraXFragment();
            Bundle args = new Bundle();
            args.putString("resolution", qualityName);
            args.putInt("fps", fps);
            args.putString("cameraId", currentCameraId);
            selectedFragment.setArguments(args);
            loadFragment(selectedFragment);
            currentQuality = quality;
            currentApi = "CameraX";
        }
    }
    
    private void updateCameraParameters(String selectedApi) {
        String resolution = resolutionSpinner.getSelectedItem().toString();
        int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());
        
        if (!resolution.equals(currentResolution) || 
            currentFps == null || currentFps != fps || 
            !selectedApi.equals(currentApi)) {
            
            Fragment selectedFragment;
            if ("Camera1".equals(selectedApi)) {
                selectedFragment = new Camera1Fragment();
            } else {
                selectedFragment = new Camera2Fragment();
            }
            
            Bundle args = new Bundle();
            args.putString("resolution", resolution);
            args.putInt("fps", fps);
            args.putString("cameraId", currentCameraId);
            selectedFragment.setArguments(args);
            loadFragment(selectedFragment);
            currentResolution = resolution;
            currentFps = fps;
            currentApi = selectedApi;
        }
    }
    
    private void toggleRecording() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof IRecordingFragment) {
            IRecordingFragment recordingFragment = (IRecordingFragment) currentFragment;
            if (recordingFragment.isCurrentlyRecording()) {
                recordingFragment.stopRecording();
            } else {
                recordingFragment.startRecording(getSelectedResolution(), getSelectedFps());
            }
        }
    }

    private boolean isRecording() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof IRecordingFragment) {
            return ((IRecordingFragment) currentFragment).isCurrentlyRecording();
        }
        return false;
    }

    private String getSelectedResolution() {
        return (String) resolutionSpinner.getSelectedItem();
    }

    private int getSelectedFps() {
        return Integer.parseInt((String) fpsSpinner.getSelectedItem());
    }

    private int getCheckedRadioButtonId() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (fragment instanceof Camera1Fragment) return R.id.camera1RadioButton;
        if (fragment instanceof Camera2Fragment) return R.id.camera2RadioButton;
        return R.id.cameraXRadioButton;
    }

    private void updateRadioButtonState() {
        int correctRadioButtonId = getCheckedRadioButtonId();
        if (apiRadioGroup.getCheckedRadioButtonId() != correctRadioButtonId) {
            apiRadioGroup.check(correctRadioButtonId);
        }
    }

    private void loadFragment(Fragment fragment) {
        if (fragment instanceof IRecordingFragment) {
            ((IRecordingFragment) fragment).setRecordingCallback(this);
        }
        if (fragment instanceof CameraXFragment) {
            ((CameraXFragment) fragment).setCameraInfoListener(this::updateCamXAdapter);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public void onRecordingStarted() {
        runOnUiThread(() -> {
            recordButton.setText("Stop Recording");
            statusTextView.setText("Status: Recording...");
            setControlsEnabled(false);
            recordingTimer.start();
        });
    }

    @Override
    public void onRecordingStopped(String message) {
        runOnUiThread(() -> {
            recordButton.setText("Start Recording");
            statusTextView.setText("Status: Idle. " + message);
            setControlsEnabled(true);
            recordingTimer.stop();
        });
    }

    private void setControlsEnabled(boolean enabled) {
        for (int i = 0; i < apiRadioGroup.getChildCount(); i++) {
            apiRadioGroup.getChildAt(i).setEnabled(enabled);
        }
        resolutionSpinner.setEnabled(enabled);
        fpsSpinner.setEnabled(enabled);
    }

    private void setupSpinners() {
        cameraSetupHelper.setupSpinners(currentCameraId, resolutionSpinner, fpsSpinner);
    }
    
    private void switchCamera() {
        if (isRecording()) {
            Toast.makeText(this, "录制中无法切换摄像头", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();
            
            if (cameraIds.length <= 1) {
                Toast.makeText(this, "只有一个摄像头可用", Toast.LENGTH_SHORT).show();
                return;
            }
            
            int currentIndex = -1;
            for (int i = 0; i < cameraIds.length; i++) {
                if (cameraIds[i].equals(currentCameraId)) {
                    currentIndex = i;
                    break;
                }
            }
            
            if (currentIndex == -1) {
                currentIndex = 0;
            }
            
            int nextIndex = (currentIndex + 1) % cameraIds.length;
            currentCameraId = cameraIds[nextIndex];
            
            cameraSetupHelper.clearAdapters();
            Toast.makeText(this, "切换到摄像头 " + currentCameraId, Toast.LENGTH_SHORT).show();
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "切换摄像头失败", e);
            Toast.makeText(this, "切换摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateCamXAdapter(CameraInfo cameraInfo) {
        cameraSetupHelper.updateCameraXAdapter(cameraInfo, resolutionSpinner);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recordingTimer.stop();
    }
    
    public String getSelectedApi() {
        int selectedId = apiRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.camera1RadioButton) return "Camera1";
        if (selectedId == R.id.camera2RadioButton) return "Camera2";
        return "CameraX";
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                currentApi = "CameraX";
                setupSpinners();
                loadFragment(new CameraXFragment());
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}