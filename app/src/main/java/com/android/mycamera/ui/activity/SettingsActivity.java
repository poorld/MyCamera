package com.android.mycamera.ui.activity;

import android.content.Context;
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
import com.android.mycamera.model.VideoBitrate;
import com.android.mycamera.model.VideoCodec;
import com.android.mycamera.utils.LocaleUtils;
import com.android.mycamera.utils.CameraUtils;
import com.android.mycamera.utils.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private static final String[] LANGUAGE_VALUES = {
            SettingsManager.LANGUAGE_SYSTEM,
            SettingsManager.LANGUAGE_CHINESE,
            SettingsManager.LANGUAGE_ENGLISH
    };
    
    private CameraManager cameraManager;
    private SettingsManager settingsManager;
    
    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Spinner bitrateSpinner;
    private Spinner videoCodecSpinner;
    private Spinner qualitySpinner;
    private Spinner photoResolutionSpinner;
    private Switch audioEnabledSwitch;
    private Switch highSpeedPlaybackSwitch;
    private Switch backgroundReviewSwitch;
    private Switch backgroundRecordingSwitch;
    private Switch keepScreenOnSwitch;
    private Switch showApiSwitcherSwitch;
    private Switch lowMemoryModeSwitch;
    private Spinner languageSpinner;
    private TextView resolutionLabel;
    private TextView qualityLabel;
    private TextView photoResolutionLabel;
    private TextView videoCodecLabel;
    private TextView saveLocationText;
    
    private CameraConfig currentConfig;
    private boolean isUpdatingResolutionSpinner = false;
    private boolean isUpdatingFrameRateSpinner = false;
    private List<ResolutionOption> resolutionOptions = Collections.emptyList();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LocaleUtils.applySavedLanguage(this);
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
        bitrateSpinner = findViewById(R.id.bitrateSpinner);
        videoCodecSpinner = findViewById(R.id.videoCodecSpinner);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        photoResolutionSpinner = findViewById(R.id.photoResolutionSpinner);
        audioEnabledSwitch = findViewById(R.id.audioEnabledSwitch);
        highSpeedPlaybackSwitch = findViewById(R.id.highSpeedPlaybackSwitch);
        backgroundReviewSwitch = findViewById(R.id.backgroundReviewSwitch);
        backgroundRecordingSwitch = findViewById(R.id.backgroundRecordingSwitch);
        keepScreenOnSwitch = findViewById(R.id.keepScreenOnSwitch);
        showApiSwitcherSwitch = findViewById(R.id.showApiSwitcherSwitch);
        lowMemoryModeSwitch = findViewById(R.id.lowMemoryModeSwitch);
        languageSpinner = findViewById(R.id.languageSpinner);
        resolutionLabel = findViewById(R.id.resolutionLabel);
        qualityLabel = findViewById(R.id.qualityLabel);
        photoResolutionLabel = findViewById(R.id.photoResolutionLabel);
        videoCodecLabel = findViewById(R.id.videoCodecLabel);
        saveLocationText = findViewById(R.id.saveLocationText);
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
        findViewById(R.id.yuvDumpTest).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, YuvDumpActivity.class)));
        findViewById(R.id.videoDebugTest).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, VideoDebugActivity.class)));
    }
    
    private void initializeManagers() {
        cameraManager = CameraManager.getInstance(this);
        settingsManager = new SettingsManager(this);
    }
    
    private void loadCurrentSettings() {
        currentConfig = cameraManager.getCurrentConfig();
        
        setupResolutionSpinner();
        setupFrameRateSpinner();
        setupVideoBitrateSpinner();
        setupVideoCodecSpinner();
        setupQualitySpinner();
        setupPhotoResolutionSpinner();
        
        audioEnabledSwitch.setChecked(currentConfig.isAudioEnabled());
        highSpeedPlaybackSwitch.setChecked(currentConfig.isHighSpeedPlaybackEnabled());
        backgroundReviewSwitch.setChecked(currentConfig.isBackgroundReviewEnabled());
        backgroundRecordingSwitch.setChecked(currentConfig.isBackgroundRecordingEnabled());
        keepScreenOnSwitch.setChecked(settingsManager.isKeepScreenOnEnabled());
        showApiSwitcherSwitch.setChecked(settingsManager.isShowApiSwitcherEnabled());
        lowMemoryModeSwitch.setChecked(currentConfig.isLowMemoryModeEnabled());
        // Force public DCIM path for capture output.
        currentConfig = new CameraConfig.Builder(currentConfig)
                .setSaveLocation(CameraUtils.createCameraDirectory(this))
                .build();
        settingsManager.saveCameraConfig(currentConfig);
        updateSaveLocationDisplay();
        setupLanguageSpinner();
    }

    private void updateSettingsVisibility() {
        CameraApiType apiType = currentConfig.getApiType();

        // Photo resolution is independent and always available.
        photoResolutionLabel.setVisibility(View.VISIBLE);
        photoResolutionSpinner.setVisibility(View.VISIBLE);

        if (apiType == CameraApiType.CAMERA1 || apiType == CameraApiType.CAMERA2) {
            // Video resolution for Camera1/Camera2
            resolutionLabel.setVisibility(View.VISIBLE);
            resolutionSpinner.setVisibility(View.VISIBLE);
            qualityLabel.setVisibility(View.GONE);
            qualitySpinner.setVisibility(View.GONE);
        } else { // CameraX video quality
            resolutionLabel.setVisibility(View.GONE);
            resolutionSpinner.setVisibility(View.GONE);
            qualityLabel.setVisibility(View.VISIBLE);
            qualitySpinner.setVisibility(View.VISIBLE);
        }

        // CameraX 1.4.2 does not expose a public video encoder selector.
        int codecVisibility = apiType == CameraApiType.CAMERAX ? View.GONE : View.VISIBLE;
        videoCodecLabel.setVisibility(codecVisibility);
        videoCodecSpinner.setVisibility(codecVisibility);

        boolean showHighSpeedPlayback = apiType == CameraApiType.CAMERA2
                && cameraManager != null
                && cameraManager.isHighSpeedVideoSupported(
                        currentConfig.getResolution(), currentConfig.getFrameRate());
        highSpeedPlaybackSwitch.setVisibility(
                showHighSpeedPlayback ? View.VISIBLE : View.GONE);
    }
    
    private void setupResolutionSpinner() {
        resolutionOptions = getSupportedResolutionOptions();
        
        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        isUpdatingResolutionSpinner = true;
        resolutionSpinner.setAdapter(adapter);

        if (resolutionOptions.isEmpty()) {
            isUpdatingResolutionSpinner = false;
            return;
        }
        
        int position = findResolutionOptionIndex(currentConfig);
        if (position < 0) {
            ResolutionOption fallback = findResolutionOption(currentConfig.getResolution());
            if (fallback == null) {
                fallback = resolutionOptions.get(0);
            }
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setResolution(fallback.resolution)
                    .setQuality(matchQualityForResolution(fallback.resolution))
                    .setFrameRate(chooseFrameRateForResolution(
                            getSupportedFrameRates(fallback.resolution), fallback.resolution,
                            currentConfig.getFrameRate()))
                    .build();
            applyAndSaveChanges();
            position = resolutionOptions.indexOf(fallback);
        }
        resolutionSpinner.setSelection(Math.max(position, 0), false);
        isUpdatingResolutionSpinner = false;
    }
    
    private void setupFrameRateSpinner() {
        List<Integer> supportedFrameRates = getSupportedFrameRates(currentConfig.getResolution());
        int selectedFrameRate = chooseFrameRateForResolution(
                supportedFrameRates, currentConfig.getResolution(), currentConfig.getFrameRate());
        List<FrameRateOption> frameRateOptions = new ArrayList<>();
        for (Integer frameRate : supportedFrameRates) {
            frameRateOptions.add(new FrameRateOption(frameRate));
        }
        
        ArrayAdapter<FrameRateOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, frameRateOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        isUpdatingFrameRateSpinner = true;
        frameRateSpinner.setAdapter(adapter);
        boolean hasMultipleFrameRates = frameRateOptions.size() > 1;
        frameRateSpinner.setEnabled(hasMultipleFrameRates);
        frameRateSpinner.setAlpha(hasMultipleFrameRates ? 1f : 0.6f);

        if (selectedFrameRate > 0 && selectedFrameRate != currentConfig.getFrameRate()) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setFrameRate(selectedFrameRate)
                    .build();
            applyAndSaveChanges();
        }
        int position = findFrameRateOptionIndex(frameRateOptions, selectedFrameRate);
        if (position >= 0) {
            frameRateSpinner.setSelection(position, false);
        }
        isUpdatingFrameRateSpinner = false;
    }
    
    private void setupQualitySpinner() {
        List<Quality> qualities = new ArrayList<>(Quality.selectableValues());

        ArrayAdapter<Quality> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, qualities);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        qualitySpinner.setAdapter(adapter);

        Quality selected = Quality.normalizeSelectable(currentConfig.getQuality());
        if (selected != currentConfig.getQuality()) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setQuality(selected)
                    .setResolution(Resolution.of(selected.getWidth(), selected.getHeight()))
                    .build();
            applyAndSaveChanges();
        }
        int position = qualities.indexOf(selected);
        if (position >= 0) {
            qualitySpinner.setSelection(position, false);
        }
    }

    private void setupVideoBitrateSpinner() {
        List<VideoBitrate> bitrates = new ArrayList<>();
        Collections.addAll(bitrates, VideoBitrate.values());
        ArrayAdapter<VideoBitrate> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, bitrates);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(adapter);

        VideoBitrate selected = currentConfig.getVideoBitrate();
        int position = bitrates.indexOf(selected);
        if (position >= 0) {
            bitrateSpinner.setSelection(position, false);
        }
    }

    private void setupVideoCodecSpinner() {
        List<VideoCodec> codecs = new ArrayList<>();
        for (VideoCodec codec : VideoCodec.values()) {
            if (codec == VideoCodec.H265
                    && (currentConfig.getApiType() == CameraApiType.CAMERAX
                    || !codec.isSupportedOnDevice())) {
                continue;
            }
            codecs.add(codec);
        }
        if (codecs.isEmpty()) {
            codecs.add(VideoCodec.DEFAULT);
        }

        VideoCodec selected = currentConfig.getVideoCodec();
        if (!codecs.contains(selected)) {
            selected = VideoCodec.DEFAULT;
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setVideoCodec(selected)
                    .build();
            applyAndSaveChanges();
        }

        ArrayAdapter<VideoCodec> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, codecs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        videoCodecSpinner.setAdapter(adapter);

        int position = codecs.indexOf(selected);
        if (position >= 0) {
            videoCodecSpinner.setSelection(position, false);
        }
    }

    
    private void setupPhotoResolutionSpinner() {
        List<Resolution> options = new ArrayList<>(
                cameraManager != null
                        ? cameraManager.getSupportedPhotoResolutions()
                        : Collections.emptyList());
        if (options.isEmpty() && currentConfig.getPhotoResolution() != null) {
            options.add(currentConfig.getPhotoResolution());
        }
        ArrayAdapter<Resolution> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        photoResolutionSpinner.setAdapter(adapter);

        Resolution selected = choosePhotoResolution(options, currentConfig.getPhotoResolution());
        if (selected != currentConfig.getPhotoResolution()
                && (selected == null || !selected.equals(currentConfig.getPhotoResolution()))) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setPhotoResolution(selected)
                    .build();
            applyAndSaveChanges();
        }
        int position = options.indexOf(selected);
        if (position >= 0) {
            photoResolutionSpinner.setSelection(position, false);
        }
    }
    private void setupLanguageSpinner() {
        List<String> languages = new ArrayList<>();
        languages.add(getString(R.string.language_follow_system));
        languages.add(getString(R.string.language_chinese));
        languages.add(getString(R.string.language_english));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        languageSpinner.setAdapter(adapter);

        String currentLanguage = settingsManager.getAppLanguage();
        int position = 0;
        for (int i = 0; i < LANGUAGE_VALUES.length; i++) {
            if (LANGUAGE_VALUES[i].equals(currentLanguage)) {
                position = i;
                break;
            }
        }
        languageSpinner.setSelection(position, false);
    }
    
    private void setupListeners() {
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingResolutionSpinner) {
                    return;
                }
                ResolutionOption selectedOption = (ResolutionOption) parent.getItemAtPosition(position);
                int selectedFps = chooseFrameRateForResolution(
                        getSupportedFrameRates(selectedOption.resolution), selectedOption.resolution,
                        currentConfig.getFrameRate());
                if (!selectedOption.resolution.equals(currentConfig.getResolution())
                        || (selectedFps > 0 && selectedFps != currentConfig.getFrameRate())) {
                    // Camera2/1 are resolution-driven; keep quality label in sync.
                    Quality matchedQuality = matchQualityForResolution(selectedOption.resolution);
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setResolution(selectedOption.resolution)
                            .setQuality(matchedQuality)
                            .setFrameRate(selectedFps > 0
                                    ? selectedFps : currentConfig.getFrameRate())
                            .setVideoBitrate(VideoBitrate.recommendedFor(
                                    selectedOption.resolution, currentConfig.getVideoCodec()))
                            .build();
                    applyAndSaveChanges();
                    setupFrameRateSpinner();
                    setupQualitySpinner();
                    setupVideoBitrateSpinner();
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
                    // Video quality only; do not touch still-photo resolution.
                    Resolution selectedResolution = Resolution.of(
                            selectedQuality.getWidth(), selectedQuality.getHeight());
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setQuality(selectedQuality)
                            .setResolution(selectedResolution)
                            .setFrameRate(chooseFrameRateForResolution(
                                    getSupportedFrameRates(selectedResolution), selectedResolution,
                                    currentConfig.getFrameRate()))
                            .setVideoBitrate(VideoBitrate.recommendedFor(
                                    selectedResolution, currentConfig.getVideoCodec()))
                            .build();
                    applyAndSaveChanges();
                    setupFrameRateSpinner();
                    setupVideoBitrateSpinner();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingFrameRateSpinner || parent.getSelectedItem() == null) {
                    return;
                }
                FrameRateOption selectedOption = (FrameRateOption) parent.getSelectedItem();
                if (selectedOption.fps != currentConfig.getFrameRate()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setFrameRate(selectedOption.fps)
                            .build();
                    applyAndSaveChanges();
                    updateSettingsVisibility();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                VideoBitrate selectedBitrate = (VideoBitrate) parent.getItemAtPosition(position);
                if (selectedBitrate != currentConfig.getVideoBitrate()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setVideoBitrate(selectedBitrate)
                            .build();
                    applyAndSaveChanges();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        videoCodecSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                VideoCodec selectedCodec = (VideoCodec) parent.getItemAtPosition(position);
                if (selectedCodec != currentConfig.getVideoCodec()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setVideoCodec(selectedCodec)
                            .setVideoBitrate(VideoBitrate.recommendedFor(
                                    currentConfig.getResolution(), selectedCodec))
                            .build();
                    applyAndSaveChanges();
                    setupVideoBitrateSpinner();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        photoResolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Resolution selectedPhoto = (Resolution) parent.getItemAtPosition(position);
                if (selectedPhoto != null && !selectedPhoto.equals(currentConfig.getPhotoResolution())) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setPhotoResolution(selectedPhoto)
                            .setCaptureMode(com.android.mycamera.model.CaptureMode.PHOTO)
                            .build();
                    // Stage only. Opening the camera and allocating a large ImageReader in Settings is slow.
                    // MainActivity applies once on resume.
                    applyAndSaveChanges();
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

        highSpeedPlaybackSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != currentConfig.isHighSpeedPlaybackEnabled()) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setHighSpeedPlaybackEnabled(isChecked)
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

        keepScreenOnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != settingsManager.isKeepScreenOnEnabled()) {
                settingsManager.setKeepScreenOnEnabled(isChecked);
            }
        });

        showApiSwitcherSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != settingsManager.isShowApiSwitcherEnabled()) {
                settingsManager.setShowApiSwitcherEnabled(isChecked);
            }
        });

        lowMemoryModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != currentConfig.isLowMemoryModeEnabled()) {
                currentConfig = new CameraConfig.Builder(currentConfig)
                        .setLowMemoryModeEnabled(isChecked)
                        .build();
                applyAndSaveChanges();
            }
        });

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLanguage = LANGUAGE_VALUES[position];
                if (!selectedLanguage.equals(settingsManager.getAppLanguage())) {
                    settingsManager.setAppLanguage(selectedLanguage);
                    LocaleUtils.applyAppLanguage(selectedLanguage);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    

    private Quality matchQualityForResolution(Resolution resolution) {
        if (resolution == null) {
            return Quality.DEFAULT;
        }
        long area = (long) resolution.getWidth() * resolution.getHeight();
        long bestDelta = Long.MAX_VALUE;
        Quality best = Quality.DEFAULT;
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
    private void applyAndSaveChanges() {
        // Stage only: do not rebuild camera here. MainActivity applies on resume.
        // Ensures resolution spinner changes feel instant (no sensor reconfigure stall).
        if (currentConfig.getSaveLocation() == null
                || CameraUtils.isLegacyAppMediaPath(currentConfig.getSaveLocation().getAbsolutePath())) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setSaveLocation(CameraUtils.createCameraDirectory(this))
                    .build();
        }
        cameraManager.stageConfiguration(currentConfig);
        currentConfig = cameraManager.getCurrentConfig();
        updateSettingsVisibility();
        updateSaveLocationDisplay();
    }


    private void updateSaveLocationDisplay() {
        if (saveLocationText == null || currentConfig == null) {
            return;
        }
        saveLocationText.setText(CameraUtils.getDisplaySavePath(currentConfig.getSaveLocation()));
    }

    private List<ResolutionOption> getSupportedResolutionOptions() {
        List<Resolution> supported = cameraManager != null
                ? cameraManager.getSupportedResolutions() : Collections.emptyList();
        List<ResolutionOption> options = new ArrayList<>();
        if (supported != null) {
            for (Resolution resolution : supported) {
                if (resolution == null || containsResolution(options, resolution)) {
                    continue;
                }
                options.add(new ResolutionOption(resolution));
            }
        }
        if (options.isEmpty() && currentConfig != null && currentConfig.getResolution() != null) {
            options.add(new ResolutionOption(currentConfig.getResolution()));
        }
        return options;
    }

    private Resolution choosePhotoResolution(List<Resolution> options, Resolution requested) {
        if (options == null || options.isEmpty()) {
            return requested;
        }
        if (requested == null) {
            return options.get(0);
        }
        for (Resolution option : options) {
            if (requested.equals(option)) {
                return option;
            }
        }

        long targetArea = (long) requested.getWidth() * requested.getHeight();
        Resolution closest = options.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (Resolution option : options) {
            long area = (long) option.getWidth() * option.getHeight();
            long distance = Math.abs(area - targetArea);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = option;
            }
        }
        return closest;
    }

    private List<Integer> getSupportedFrameRates(Resolution resolution) {
        List<Integer> queried = cameraManager != null
                ? cameraManager.getSupportedFrameRates(resolution) : Collections.emptyList();
        List<Integer> frameRates = new ArrayList<>();
        if (queried != null) {
            for (Integer frameRate : queried) {
                if (frameRate != null && frameRate > 0 && !frameRates.contains(frameRate)) {
                    frameRates.add(frameRate);
                }
            }
        }
        Collections.sort(frameRates);
        if (frameRates.isEmpty() && currentConfig != null && currentConfig.getFrameRate() > 0) {
            frameRates.add(currentConfig.getFrameRate());
        }
        if (frameRates.isEmpty() && cameraManager != null) {
            int preferred = cameraManager.getPreferredFrameRate(resolution);
            if (preferred > 0) {
                frameRates.add(preferred);
            }
        }
        return frameRates;
    }

    private int chooseFrameRate(List<Integer> frameRates, int requestedFrameRate) {
        if (frameRates == null || frameRates.isEmpty()) {
            return requestedFrameRate;
        }
        if (requestedFrameRate <= 0) {
            return frameRates.get(frameRates.size() - 1);
        }
        int selected = frameRates.get(0);
        int bestDistance = Math.abs(selected - requestedFrameRate);
        for (Integer frameRate : frameRates) {
            if (frameRate == null || frameRate <= 0) {
                continue;
            }
            int distance = Math.abs(frameRate - requestedFrameRate);
            if (distance < bestDistance
                    || (distance == bestDistance && frameRate > selected)) {
                selected = frameRate;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private int chooseFrameRateForResolution(
            List<Integer> frameRates, Resolution resolution, int requestedFrameRate) {
        int requested = requestedFrameRate;
        if (requested <= 0 && cameraManager != null) {
            requested = cameraManager.getPreferredFrameRate(resolution);
        }
        return chooseFrameRate(frameRates, requested);
    }

    private boolean containsResolution(List<ResolutionOption> options, Resolution resolution) {
        for (ResolutionOption option : options) {
            if (option.resolution.equals(resolution)) {
                return true;
            }
        }
        return false;
    }

    private int findFrameRateOptionIndex(List<FrameRateOption> options, int frameRate) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).fps == frameRate) {
                return i;
            }
        }
        return -1;
    }

    private int findResolutionOptionIndex(CameraConfig config) {
        for (int i = 0; i < resolutionOptions.size(); i++) {
            ResolutionOption option = resolutionOptions.get(i);
            if (option.resolution.equals(config.getResolution())) {
                return i;
            }
        }
        return -1;
    }

    private ResolutionOption findResolutionOption(Resolution resolution) {
        for (ResolutionOption option : resolutionOptions) {
            if (option.resolution.equals(resolution)) {
                return option;
            }
        }
        return null;
    }

    private static final class ResolutionOption {
        private final Resolution resolution;

        private ResolutionOption(Resolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public String toString() {
            return resolution.toString();
        }
    }

    private static final class FrameRateOption {
        private final int fps;

        private FrameRateOption(int fps) {
            this.fps = fps;
        }

        @Override
        public String toString() {
            return fps + " FPS";
        }
    }

    
}
