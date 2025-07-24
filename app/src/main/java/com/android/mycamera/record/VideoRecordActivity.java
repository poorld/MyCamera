package com.android.mycamera.record;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
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
import androidx.camera.video.QualitySelector;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class VideoRecordActivity extends BaseAct implements RecordingCallback {

    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Button recordButton;
    private RadioGroup apiRadioGroup;
    private TextView statusTextView;
    private TextView timeTextView;
    private Timer timer;
    private int time = 0;

    private ArrayAdapter<String> camXadapter;
    private ArrayAdapter<String> camAdapter;
    private ArrayAdapter<String> fpsAdapter;
    public Map<String, Quality> qualityMap;
    private ArrayAdapter<String> qualityAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_record);
        initViews();
        if (allPermissionsGranted()) {
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
        apiRadioGroup = findViewById(R.id.apiRadioGroup);
        statusTextView = findViewById(R.id.statusTextView);
        timeTextView = findViewById(R.id.timeTextView);

        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isRecording()) return;
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

        recordButton.setOnClickListener(v -> {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof IRecordingFragment) {
                IRecordingFragment recordingFragment = (IRecordingFragment) currentFragment;
                if (recordingFragment.isCurrentlyRecording()) {
                    recordingFragment.stopRecording();
                } else {
                    recordingFragment.startRecording(getSelectedResolution(), getSelectedFps());
                }
            }
        });
    }

    private void switchCamParam() {
        Fragment selectedFragment;
        String selectedApi = getSelectedApi();
        if ("Camera1".equals(selectedApi)) {
            selectedFragment = new Camera1Fragment();
        } else if ("Camera2".equals(selectedApi)) {
            selectedFragment = new Camera2Fragment();
        } else {
            selectedFragment = new CameraXFragment();
        }

        Bundle args = new Bundle();
        args.putString("resolution", getSelectedResolution());
        args.putInt("fps", getSelectedFps());
        selectedFragment.setArguments(args);

        loadFragment(selectedFragment);
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
            startTimer();
        });
    }

    private void startTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        time = 0;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> timeTextView.setText(getTime()));
            }
        }, 1000, 1000);
    }

    private String getTime() {
        time += 1;
        int hours = time / 3600;
        int minutes = (time % 3600) / 60;
        int seconds = time % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    @Override
    public void onRecordingStopped(String message) {
        runOnUiThread(() -> {
            recordButton.setText("Start Recording");
            statusTextView.setText("Status: Idle. " + message);
            setControlsEnabled(true);
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            timeTextView.setText("00:00");
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
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = "0";
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) return;

            setupResolutionAdapter(characteristics);
            setupFpsAdapter(characteristics);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupResolutionAdapter(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if ("CameraX".equals(getSelectedApi())) {
            if (qualityAdapter != null) {
                resolutionSpinner.setAdapter(qualityAdapter);
            } else if (camXadapter == null) {
                camXadapter = getCamXAdapter();
                resolutionSpinner.setAdapter(camXadapter);
            }
        } else {
            if (camAdapter == null) {
                camAdapter = getCamAdapter(map);
            }
            resolutionSpinner.setAdapter(camAdapter);
        }
    }

    private void setupFpsAdapter(CameraCharacteristics characteristics) {
        if (fpsAdapter == null) {
            fpsAdapter = getFpsAdapter(characteristics);
        }
        fpsSpinner.setAdapter(fpsAdapter);
    }

    @NonNull
    private ArrayAdapter<String> getCamXAdapter() {
        List<Quality> camXQualities = new ArrayList<>();
        camXQualities.add(Quality.HIGHEST);
        List<String> resolutions = new ArrayList<>();
        for (Quality quality : camXQualities) {
            String name = ((Quality.ConstantQuality) quality).getName();
            resolutions.add(name);
        }
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return resolutionAdapter;
    }

    @NonNull
    private void updateCamXAdapter(CameraInfo cameraInfo) {
        if (qualityAdapter != null) {
            return;
        }
        qualityMap = new HashMap<>();

        List<Quality> camXQualities = QualitySelector.getSupportedQualities(cameraInfo);
        List<String> resolutions = new ArrayList<>();
        for (Quality quality : camXQualities) {
            String name = ((Quality.ConstantQuality) quality).getName();
            qualityMap.put(name, quality);
            resolutions.add(name);
        }
        qualityAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(qualityAdapter);
    }

    @NonNull
    private ArrayAdapter<String> getCamAdapter(StreamConfigurationMap map) {
        Size[] outputSizes = map.getOutputSizes(MediaRecorder.class);
        List<String> resolutions = new ArrayList<>();
        if (outputSizes != null) {
            for (Size size : outputSizes) {
                resolutions.add(size.getWidth() + "x" + size.getHeight());
            }
        }
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return resolutionAdapter;
    }

    private ArrayAdapter<String> getFpsAdapter(CameraCharacteristics characteristics) {
        Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Set<String> frameRatesSet = new HashSet<>();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                frameRatesSet.add(range.getUpper().toString());
            }
        }
        List<String> frameRates = new ArrayList<>(frameRatesSet);
        Collections.sort(frameRates);
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, frameRates);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return fpsAdapter;
    }

    private String getSelectedApi() {
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
                setupSpinners();
                loadFragment(new CameraXFragment());
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}




