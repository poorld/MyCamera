package com.android.mycamera.bgr_yes;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.camera.core.CameraInfo;
import androidx.camera.video.Quality;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

public class BgrYesActivity extends BaseAct {

    private static final String TAG = "BgrYesActivity";
    
    private TextureView textureView;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Button recordButton;
    private TextView statusTextView;
    private TextView timeTextView;
    private RadioGroup apiRadioGroup;
    
    private BgrYesRecordService recordService;
    private boolean isServiceBound = false;
    private boolean isRecording = false;
    
    private CameraSetupHelper cameraSetupHelper;
    private RecordingTimer recordingTimer;
    
    private String currentCameraId = "0";
    private String currentApi;
    private Quality currentQuality;
    private String currentResolution;
    private int currentFps;
    private String currentCameraXQualityName;
    private String currentCameraXCameraId;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            BgrYesRecordService.BgrYesRecordBinder binder = (BgrYesRecordService.BgrYesRecordBinder) service;
            recordService = binder.getService();
            isServiceBound = true;
            updateCameraParameters();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bgr_yes);

        textureView = findViewById(R.id.fragment_container);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        recordButton = findViewById(R.id.recordButton);
        statusTextView = findViewById(R.id.statusTextView);
        timeTextView = findViewById(R.id.timeTextView);
        apiRadioGroup = findViewById(R.id.apiRadioGroup);

        cameraSetupHelper = new CameraSetupHelper(this);
        recordingTimer = new RecordingTimer(timeTextView);
        
        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isRecording) {
                Toast.makeText(this, "Cannot switch API while recording", Toast.LENGTH_SHORT).show();
                group.check(getCurrentApiRadioButtonId());
                return;
            }
            if (isServiceBound) {
                cameraSetupHelper.clearAdapters();
                setupSpinners();
                updateCameraParameters();
            }
        });

        Button switchCameraButton = findViewById(R.id.switchCameraButton);
        if (switchCameraButton != null) {
            switchCameraButton.setOnClickListener(v -> {
                switchCamera();
                setupSpinners();
            });
        }

        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isServiceBound) {
                    updateCameraParameters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isServiceBound) {
                    updateCameraParameters();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        recordButton.setOnClickListener(v -> toggleRecording());

        setupSpinners();
        startAndBindService();
    }
    
    public String getCurrentCameraId() {
        return currentCameraId;
    }
    
    public void setCurrentCameraId(String cameraId) {
        this.currentCameraId = cameraId;
    }
    
    public String getSelectedApi() {
        int selectedId = apiRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.camera1RadioButton) return "Camera1";
        if (selectedId == R.id.camera2RadioButton) return "Camera2";
        return "CameraX";
    }

    private void updateCameraParameters() {
        String selectedApi = getSelectedApi();
        recordService.setCurrentCameraId(currentCameraId);
        
        if ("CameraX".equals(selectedApi)) {
            updateCameraXParameters(selectedApi);
        } else {
            updateCameraParameters(selectedApi);
        }
    }
    
    private void updateCameraXParameters(String selectedApi) {
        Object selectedItem = resolutionSpinner.getSelectedItem();
        if (selectedItem == null || cameraSetupHelper.getQualityMap() == null) {
            return;
        }

        String qualityName = selectedItem.toString();
        Quality quality = cameraSetupHelper.getQualityMap().get(qualityName);
        if (quality == null) {
            return;
        }

        int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());

        boolean needSwitch = currentQuality != quality
                || currentFps != fps
                || !TextUtils.equals(currentApi, selectedApi)
                || !TextUtils.equals(currentCameraXQualityName, qualityName)
                || !TextUtils.equals(currentCameraXCameraId, currentCameraId);

        if (needSwitch) {
            recordService.switchCameraX(selectedApi, textureView, quality, fps, 
                cameraInfo -> cameraSetupHelper.updateCameraXAdapter(cameraInfo, resolutionSpinner));
            currentQuality = quality;
            currentFps = fps;
            currentApi = selectedApi;
            currentCameraXQualityName = qualityName;
            currentCameraXCameraId = currentCameraId;
        }
    }
    
    private void updateCameraParameters(String selectedApi) {
        String resolution = resolutionSpinner.getSelectedItem().toString();
        int width = Integer.parseInt(resolution.split("x")[0]);
        int height = Integer.parseInt(resolution.split("x")[1]);
        int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());
        
        if (!TextUtils.equals(currentResolution, resolution) || 
            currentFps != fps || 
            !TextUtils.equals(currentApi, selectedApi)) {
            
            recordService.switchCamera(selectedApi, textureView, width, height, fps);
            currentResolution = resolution;
            currentFps = fps;
            currentApi = selectedApi;
        }
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }
    
    private void startRecording() {
        if (isServiceBound && recordService != null && recordService.isReady()) {
            recordService.startRecording();
            isRecording = true;
            recordButton.setText("Stop Recording");
            statusTextView.setText("Status: Recording");
            setControlsEnabled(false);
            recordingTimer.start();
        }
    }

    private void stopRecording() {
        if (isServiceBound) {
            recordService.stopRecording();
            isRecording = false;
            recordButton.setText("Start Recording");
            statusTextView.setText("Status: Idle");
            setControlsEnabled(true);
            recordingTimer.stop();
        }
    }

    private int getCurrentApiRadioButtonId() {
        String currentApi = recordService.getCurrentApi();
        if (currentApi == null) return R.id.cameraXRadioButton;
        switch (currentApi) {
            case "Camera1":
                return R.id.camera1RadioButton;
            case "Camera2":
                return R.id.camera2RadioButton;
            default:
                return R.id.cameraXRadioButton;
        }
    }

    private void setControlsEnabled(boolean enabled) {
        apiRadioGroup.setEnabled(enabled);
        resolutionSpinner.setEnabled(enabled);
        fpsSpinner.setEnabled(enabled);
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, BgrYesRecordService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupSpinners() {
        cameraSetupHelper.setupSpinners(currentCameraId, resolutionSpinner, fpsSpinner);
    }
    
    private void switchCamera() {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: ");
        recordingTimer.stop();
        if (isServiceBound) {
            recordService.stopPreview();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
}
