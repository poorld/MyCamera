package com.android.mycamera.bgr_yes;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BgrYesActivity extends BaseAct {

    public static final String TAG = "BgrYesActivity";

    private TextureView textureView;
    private Spinner resolutionSpinner, fpsSpinner;
    private Button recordButton;
    private TextView statusTextView;
    private RadioGroup apiRadioGroup;

    private BgrYesRecordService recordService;
    private boolean isBound = false;
    private boolean isRecording = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            BgrYesRecordService.BgrYesRecordBinder binder = (BgrYesRecordService.BgrYesRecordBinder) service;
            recordService = binder.getService();
            isBound = true;

            String selectedApi = getSelectedApi();
            String resolution = resolutionSpinner.getSelectedItem().toString();
            int width = Integer.parseInt(resolution.split("x")[0]);
            int height = Integer.parseInt(resolution.split("x")[1]);
            int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());

            recordService.switchCamera(selectedApi, textureView, width, height, fps);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bgr_yes);

        textureView = findViewById(R.id.fragment_container); // Re-using the ID, but it's a TextureView now
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        recordButton = findViewById(R.id.recordButton);
        statusTextView = findViewById(R.id.statusTextView);
        apiRadioGroup = findViewById(R.id.apiRadioGroup);

        apiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isRecording) {
                Toast.makeText(this, "Cannot switch API while recording", Toast.LENGTH_SHORT).show();
                group.check(getCheckedRadioButtonId()); // Revert selection
                return;
            }
            if (isBound) {
                String selectedApi = getSelectedApi();
                String resolution = resolutionSpinner.getSelectedItem().toString();
                int width = Integer.parseInt(resolution.split("x")[0]);
                int height = Integer.parseInt(resolution.split("x")[1]);
                int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());
                recordService.switchCamera(selectedApi, textureView, width, height, fps);
            }
        });

        recordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        setupSpinners();
        startServiceAndBind();
    }

    private void startRecording() {
        if (isBound && recordService != null && recordService.isReady()) {
            recordService.startRecording();
            isRecording = true;
            recordButton.setText("Stop Recording");
            statusTextView.setText("Status: Recording");
            setControlsEnabled(false);
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: ");
        if (isBound) {
            recordService.stopRecording();
            isRecording = false;
            recordButton.setText("Start Recording");
            statusTextView.setText("Status: Idle");
            setControlsEnabled(true);
        }
    }

    private void startPreviewForSelectedApi() {
        Log.d(TAG, "startPreviewForSelectedApi: ");
        String selectedApi = getSelectedApi();
        String resolution = resolutionSpinner.getSelectedItem().toString();
        int width = Integer.parseInt(resolution.split("x")[0]);
        int height = Integer.parseInt(resolution.split("x")[1]);
        int fps = Integer.parseInt(fpsSpinner.getSelectedItem().toString());
        // recordService.startPreview(selectedApi, textureView, width, height, fps, this);
    }

    private String getSelectedApi() {
        int selectedId = apiRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.camera1RadioButton) return "Camera1";
        if (selectedId == R.id.camera2RadioButton) return "Camera2";
        return "CameraX";
    }
    private int getCheckedRadioButtonId() {
        String currentApi = recordService.getCurrentApi();
        if (currentApi == null) return R.id.cameraXRadioButton; // Default
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

    private void startServiceAndBind() {
        Log.d(TAG, "startServiceAndBind: ");
        Intent intent = new Intent(this, BgrYesRecordService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void setupSpinners() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = "0"; // Default to back camera
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            Size[] outputSizes = map.getOutputSizes(MediaRecorder.class);
            List<String> resolutions = new ArrayList<>();
            for (Size size : outputSizes) {
                resolutions.add(size.getWidth() + "x" + size.getHeight());
            }
            ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
            resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            resolutionSpinner.setAdapter(resolutionAdapter);

            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Set<String> frameRatesSet = new HashSet<>();
            for (Range<Integer> range : fpsRanges) {
                frameRatesSet.add(range.getUpper().toString());
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


    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        super.onDestroy();
        if (isBound) {
            recordService.stopPreview();
            unbindService(connection);
            isBound = false;
        }
    }
}