package com.android.mycamera;

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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VideoRecordActivity extends BaseAct implements RecordingCallback {

    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Button recordButton;
    private RadioGroup apiRadioGroup;
    private TextView statusTextView;

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

        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof IRecordingFragment && ((IRecordingFragment) currentFragment).isCurrentlyRecording()) {
                group.check(getCheckedRadioButtonId()); // Revert selection if recording
                return;
            }

            Fragment selectedFragment = null;
            if (checkedId == R.id.camera1RadioButton) {
                selectedFragment = new Camera1Fragment();
            } else if (checkedId == R.id.camera2RadioButton) {
                selectedFragment = new Camera2Fragment();
            } else if (checkedId == R.id.cameraXRadioButton) {
                selectedFragment = new CameraXFragment();
            }

            if (selectedFragment != null) {
                loadFragment(selectedFragment);
            }
        });

        recordButton.setOnClickListener(v -> {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof IRecordingFragment) {
                IRecordingFragment recordingFragment = (IRecordingFragment) currentFragment;
                if (recordingFragment.isCurrentlyRecording()) {
                    recordingFragment.stopRecording();
                } else {
                    String resolution = (String) resolutionSpinner.getSelectedItem();
                    int fps = Integer.parseInt((String) fpsSpinner.getSelectedItem());
                    recordingFragment.startRecording(resolution, fps);
                }
            }
        });
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
        });
    }

    @Override
    public void onRecordingStopped(String message) {
        runOnUiThread(() -> {
            recordButton.setText("Start Recording");
            statusTextView.setText("Status: Idle. " + message);
            setControlsEnabled(true);
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

            Size[] outputSizes = map.getOutputSizes(MediaRecorder.class);
            List<String> resolutions = new ArrayList<>();
            if (outputSizes != null) {
                for (Size size : outputSizes) {
                    resolutions.add(size.getWidth() + "x" + size.getHeight());
                }
            }
            ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
            resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            resolutionSpinner.setAdapter(resolutionAdapter);

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
            fpsSpinner.setAdapter(fpsAdapter);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
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




