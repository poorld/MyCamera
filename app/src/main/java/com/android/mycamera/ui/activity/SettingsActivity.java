package com.android.mycamera.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.mycamera.MainAct;
import com.android.mycamera.R;
import com.android.mycamera.camera.CamSizeActivity;
import com.android.mycamera.camera.config.CameraConfig;
import com.android.mycamera.camera.manager.CameraManager;
import com.android.mycamera.model.CameraApiType;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.SettingsManager;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    
    private CameraManager cameraManager;
    private SettingsManager settingsManager;
    
    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Spinner qualitySpinner;
    private Switch audioEnabledSwitch;
    private Switch backgroundReviewSwitch;
    private Switch backgroundRecordingSwitch;
    private TextView resolutionLabel;
    private TextView qualityLabel;
    
    private CameraConfig currentConfig;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        initializeViews();
        initializeManagers();
        loadCurrentSettings();
        updateSettingsVisibility();
        setupListeners();
    }
    
    private void initializeViews() {
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        frameRateSpinner = findViewById(R.id.frameRateSpinner);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        audioEnabledSwitch = findViewById(R.id.audioEnabledSwitch);
        backgroundReviewSwitch = findViewById(R.id.backgroundReviewSwitch);
        backgroundRecordingSwitch = findViewById(R.id.backgroundRecordingSwitch);
        resolutionLabel = findViewById(R.id.resolutionLabel);
        qualityLabel = findViewById(R.id.qualityLabel);
        findViewById(R.id.cam_size).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, CamSizeActivity.class));
            }
        });
        findViewById(R.id.old_cam).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, MainAct.class));
            }
        });
    }
    
    private void initializeManagers() {
        cameraManager = CameraManager.getInstance(this);
        settingsManager = new SettingsManager(this);
    }
    
    private void loadCurrentSettings() {
        currentConfig = cameraManager.getCurrentConfig();
        
        setupResolutionSpinner();
        setupFrameRateSpinner();
        setupQualitySpinner();
        
        audioEnabledSwitch.setChecked(currentConfig.isAudioEnabled());
        backgroundReviewSwitch.setChecked(currentConfig.isBackgroundReviewEnabled());
        backgroundRecordingSwitch.setChecked(currentConfig.isBackgroundRecordingEnabled());
    }

    private void updateSettingsVisibility() {
        CameraApiType apiType = currentConfig.getApiType();

        if (apiType == CameraApiType.CAMERA1 || apiType == CameraApiType.CAMERA2) {
            resolutionLabel.setVisibility(View.VISIBLE);
            resolutionSpinner.setVisibility(View.VISIBLE);
            qualityLabel.setVisibility(View.GONE);
            qualitySpinner.setVisibility(View.GONE);
        } else { // CameraX
            resolutionLabel.setVisibility(View.GONE);
            resolutionSpinner.setVisibility(View.GONE);
            qualityLabel.setVisibility(View.VISIBLE);
            qualitySpinner.setVisibility(View.VISIBLE);
        }
    }
    
    private void setupResolutionSpinner() {
        List<Resolution> resolutions = new ArrayList<>();
        resolutions.add(Resolution.VGA_640x480);
        resolutions.add(Resolution.HD_720P);
        resolutions.add(Resolution.FULL_HD_1080P);
        resolutions.add(Resolution.UHD_4K);
        
        ArrayAdapter<Resolution> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, resolutions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        resolutionSpinner.setAdapter(adapter);
        
        int position = resolutions.indexOf(currentConfig.getResolution());
        if (position >= 0) {
            resolutionSpinner.setSelection(position, false);
        }
    }
    
    private void setupFrameRateSpinner() {
        List<Integer> frameRates = cameraManager.getSupportedFrameRates();
        if (frameRates == null || frameRates.isEmpty()) {
            frameRates = new ArrayList<>();
            frameRates.add(15);
            frameRates.add(24);
            frameRates.add(30);
        }
        
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, frameRates);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        frameRateSpinner.setAdapter(adapter);

        int selectedFps = currentConfig.getFrameRate();
        if (!frameRates.contains(selectedFps)) {
            selectedFps = frameRates.get(frameRates.size() - 1);
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setFrameRate(selectedFps)
                    .build();
            applyAndSaveChanges();
        }

        int position = frameRates.indexOf(selectedFps);
        if (position >= 0) {
            frameRateSpinner.setSelection(position, false);
        }
    }
    
    private void setupQualitySpinner() {
        List<Quality> qualities = new ArrayList<>();
        qualities.add(Quality.SD);
        qualities.add(Quality.HD);
        qualities.add(Quality.FULL_HD);
        qualities.add(Quality.UHD);
        
        ArrayAdapter<Quality> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, qualities);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        qualitySpinner.setAdapter(adapter);

        int position = qualities.indexOf(currentConfig.getQuality());
        if (position >= 0) {
            qualitySpinner.setSelection(position, false);
        }
    }
    
    private void setupListeners() {
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Resolution selectedResolution = (Resolution) parent.getItemAtPosition(position);
                if (!selectedResolution.equals(currentConfig.getResolution())) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setResolution(selectedResolution)
                            .build();
                    applyAndSaveChanges();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Integer selectedFrameRate = (Integer) parent.getItemAtPosition(position);
                if (selectedFrameRate != currentConfig.getFrameRate()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setFrameRate(selectedFrameRate)
                            .build();
                    applyAndSaveChanges();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Quality selectedQuality = (Quality) parent.getItemAtPosition(position);
                if (selectedQuality != currentConfig.getQuality()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setQuality(selectedQuality)
                            .build();
                    applyAndSaveChanges();
                    setupFrameRateSpinner();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        audioEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != currentConfig.isAudioEnabled()) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setAudioEnabled(isChecked)
                        .build();
                applyAndSaveChanges();
            }
        });

        backgroundReviewSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != currentConfig.isBackgroundReviewEnabled()) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setBackgroundReviewEnabled(isChecked)
                        .build();
                applyAndSaveChanges();
            }
        });
        backgroundRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != currentConfig.isBackgroundRecordingEnabled()) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setBackgroundRecordingEnabled(isChecked)
                        .build();
                applyAndSaveChanges();
            }
        });
    }
    
    private void applyAndSaveChanges() {
        cameraManager.updateConfiguration(currentConfig);
        settingsManager.saveCameraConfig(currentConfig);
    }

    
}
