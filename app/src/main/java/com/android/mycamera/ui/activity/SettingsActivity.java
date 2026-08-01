package com.android.mycamera.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
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
import com.android.mycamera.model.PhotoResolution;
import com.android.mycamera.model.Quality;
import com.android.mycamera.model.Resolution;
import com.android.mycamera.utils.LocaleUtils;
import com.android.mycamera.utils.CameraUtils;
import com.android.mycamera.utils.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private static final int DEFAULT_VIDEO_FRAME_RATE = 30;
    private static final String[] LANGUAGE_VALUES = {
            SettingsManager.LANGUAGE_SYSTEM,
            SettingsManager.LANGUAGE_CHINESE,
            SettingsManager.LANGUAGE_ENGLISH
    };
    
    private CameraManager cameraManager;
    private SettingsManager settingsManager;
    
    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Spinner qualitySpinner;
    private Spinner photoResolutionSpinner;
    private Switch audioEnabledSwitch;
    private Switch backgroundReviewSwitch;
    private Switch backgroundRecordingSwitch;
    private Switch keepScreenOnSwitch;
    private Switch customResolutionSwitch;
    private Switch showApiSwitcherSwitch;
    private Spinner languageSpinner;
    private TextView resolutionLabel;
    private TextView qualityLabel;
    private TextView photoResolutionLabel;
    private TextView saveLocationText;
    
    private CameraConfig currentConfig;
    private boolean isUpdatingResolutionSpinner = false;
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
        qualitySpinner = findViewById(R.id.qualitySpinner);
        photoResolutionSpinner = findViewById(R.id.photoResolutionSpinner);
        audioEnabledSwitch = findViewById(R.id.audioEnabledSwitch);
        backgroundReviewSwitch = findViewById(R.id.backgroundReviewSwitch);
        backgroundRecordingSwitch = findViewById(R.id.backgroundRecordingSwitch);
        keepScreenOnSwitch = findViewById(R.id.keepScreenOnSwitch);
        customResolutionSwitch = findViewById(R.id.customResolutionSwitch);
        showApiSwitcherSwitch = findViewById(R.id.showApiSwitcherSwitch);
        languageSpinner = findViewById(R.id.languageSpinner);
        resolutionLabel = findViewById(R.id.resolutionLabel);
        qualityLabel = findViewById(R.id.qualityLabel);
        photoResolutionLabel = findViewById(R.id.photoResolutionLabel);
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
        setupPhotoResolutionSpinner();
        
        audioEnabledSwitch.setChecked(currentConfig.isAudioEnabled());
        backgroundReviewSwitch.setChecked(currentConfig.isBackgroundReviewEnabled());
        backgroundRecordingSwitch.setChecked(currentConfig.isBackgroundRecordingEnabled());
        keepScreenOnSwitch.setChecked(settingsManager.isKeepScreenOnEnabled());
        showApiSwitcherSwitch.setChecked(settingsManager.isShowApiSwitcherEnabled());
        // Force public DCIM path for capture output.
        currentConfig = new CameraConfig.Builder(currentConfig)
                .setSaveLocation(CameraUtils.createCameraDirectory(this))
                .build();
        settingsManager.saveCameraConfig(currentConfig);
        updateSaveLocationDisplay();
        customResolutionSwitch.setChecked(shouldUseCamera2CustomResolutions());
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
            customResolutionSwitch.setVisibility(apiType == CameraApiType.CAMERA2 ? View.VISIBLE : View.GONE);
        } else { // CameraX video quality
            resolutionLabel.setVisibility(View.GONE);
            resolutionSpinner.setVisibility(View.GONE);
            customResolutionSwitch.setVisibility(View.GONE);
            qualityLabel.setVisibility(View.VISIBLE);
            qualitySpinner.setVisibility(View.VISIBLE);
        }
    }
    
    private void setupResolutionSpinner() {
        resolutionOptions = shouldUseCamera2CustomResolutions()
                ? getCamera2ResolutionOptions(currentConfig.getCameraId())
                : getFixedResolutionOptions();
        if (resolutionOptions.isEmpty()) {
            resolutionOptions = getFixedResolutionOptions();
        }
        
        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        isUpdatingResolutionSpinner = true;
        resolutionSpinner.setAdapter(adapter);
        
        int position = findResolutionOptionIndex(currentConfig);
        if (position < 0) {
            ResolutionOption fallback = findRegularOption(Resolution.FULL_HD_1080P);
            if (fallback == null) {
                fallback = resolutionOptions.get(0);
            }
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setResolution(fallback.resolution)
                    .setFrameRate(fallback.fixedHighSpeedFps != null
                            ? fallback.fixedHighSpeedFps : currentConfig.getFrameRate())
                    .build();
            applyAndSaveChanges();
            position = resolutionOptions.indexOf(fallback);
        }
        resolutionSpinner.setSelection(Math.max(position, 0), false);
        isUpdatingResolutionSpinner = false;
    }
    
    private void setupFrameRateSpinner() {
        Integer presetFps = getSelectedPresetFps();
        List<Integer> frameRates;
        if (presetFps != null) {
            frameRates = Collections.singletonList(presetFps);
        } else {
            frameRates = cameraManager.getSupportedFrameRates();
            if (frameRates == null || frameRates.isEmpty()) {
                frameRates = new ArrayList<>();
                frameRates.add(15);
                frameRates.add(24);
                frameRates.add(30);
            }
        }

        List<FrameRateOption> frameRateOptions = new ArrayList<>();
        for (Integer frameRate : frameRates) {
            if (frameRate != null) {
                frameRateOptions.add(new FrameRateOption(frameRate, presetFps != null));
            }
        }
        
        ArrayAdapter<FrameRateOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, frameRateOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        frameRateSpinner.setAdapter(adapter);
        frameRateSpinner.setEnabled(presetFps == null);
        frameRateSpinner.setAlpha(presetFps == null ? 1.0f : 0.6f);

        int selectedFps = presetFps != null ? presetFps : currentConfig.getFrameRate();
        if (!frameRates.contains(selectedFps)) {
            selectedFps = frameRates.get(frameRates.size() - 1);
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setFrameRate(selectedFps)
                    .build();
            applyAndSaveChanges();
        }

        int position = findFrameRateOptionIndex(frameRateOptions, selectedFps);
        if (position >= 0) {
            frameRateSpinner.setSelection(position, false);
        }
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

    
    private void setupPhotoResolutionSpinner() {
        String cameraId = currentConfig.getCameraId();
        List<PhotoResolution> options = new ArrayList<>(
                PhotoResolution.selectableValuesForCamera(cameraId));
        ArrayAdapter<PhotoResolution> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        photoResolutionSpinner.setAdapter(adapter);

        PhotoResolution selected = PhotoResolution.normalize(
                currentConfig.getPhotoResolution(), cameraId);
        if (selected != currentConfig.getPhotoResolution()) {
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
                int selectedFps = selectedOption.fixedHighSpeedFps != null
                        ? selectedOption.fixedHighSpeedFps
                        : (selectedOption.preferredFps != null
                                ? selectedOption.preferredFps : DEFAULT_VIDEO_FRAME_RATE);
                if (!selectedOption.resolution.equals(currentConfig.getResolution())
                        || selectedFps != currentConfig.getFrameRate()) {
                    // Camera2/1 are resolution-driven; keep quality label in sync.
                    Quality matchedQuality = matchQualityForResolution(selectedOption.resolution);
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setResolution(selectedOption.resolution)
                            .setQuality(matchedQuality)
                            .setFrameRate(selectedFps)
                            .build();
                    applyAndSaveChanges();
                    setupFrameRateSpinner();
                    setupQualitySpinner();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FrameRateOption selectedFrameRate = (FrameRateOption) parent.getItemAtPosition(position);
                if (selectedFrameRate.fps != currentConfig.getFrameRate()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setFrameRate(selectedFrameRate.fps)
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
                    // Video quality only; do not touch still-photo resolution.
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setQuality(selectedQuality)
                            .setResolution(Resolution.of(selectedQuality.getWidth(), selectedQuality.getHeight()))
                            .build();
                    applyAndSaveChanges();
                    setupFrameRateSpinner();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        photoResolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PhotoResolution selectedPhoto = (PhotoResolution) parent.getItemAtPosition(position);
                if (selectedPhoto != currentConfig.getPhotoResolution()) {
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setPhotoResolution(selectedPhoto)
                            .setCaptureMode(com.android.mycamera.model.CaptureMode.PHOTO)
                            .build();
                    // Stage only. Opening camera + alloc 36M/64M ImageReader in Settings is slow.
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

        customResolutionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentConfig.getApiType() != CameraApiType.CAMERA2) {
                return;
            }
            if (isChecked != settingsManager.isCamera2CustomResolutionEnabled()) {
                settingsManager.setCamera2CustomResolutionEnabled(isChecked);
                setupResolutionSpinner();
                setupFrameRateSpinner();
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

    private boolean shouldUseCamera2CustomResolutions() {
        return currentConfig != null
                && currentConfig.getApiType() == CameraApiType.CAMERA2
                && settingsManager.isCamera2CustomResolutionEnabled();
    }

    private List<ResolutionOption> getFixedResolutionOptions() {
        // Video-only fixed sizes: HD / FHD / 2K
        List<ResolutionOption> options = new ArrayList<>();
        options.add(new ResolutionOption(Resolution.QHD_2K, null));
        options.add(new ResolutionOption(Resolution.FULL_HD_1080P, null));
        options.add(new ResolutionOption(Resolution.HD_720P, null));
        return options;
    }

    private List<ResolutionOption> getCamera2ResolutionOptions(String cameraId) {
        Set<Resolution> resolutions = new LinkedHashSet<>();
        List<ResolutionOption> highSpeedOptions = new ArrayList<>();
        android.hardware.camera2.CameraManager systemCameraManager =
                (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (systemCameraManager == null) {
            return Collections.emptyList();
        }

        try {
            CameraCharacteristics characteristics = systemCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return Collections.emptyList();
            }

            Size[] recorderSizes = map.getOutputSizes(MediaRecorder.class);
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            Size[] highResolutionJpegSizes = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
            addSizes(resolutions, recorderSizes);
            addSizes(resolutions, jpegSizes);
            addSizes(resolutions, highResolutionJpegSizes);

            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();
            if (highSpeedSizes != null) {
                for (Size size : highSpeedSizes) {
                    if (size == null) continue;
                    Set<Integer> fixedFpsValues = new HashSet<>();
                    for (Range<Integer> range : map.getHighSpeedVideoFpsRangesFor(size)) {
                        if (range != null && range.getUpper() > 30) {
                            fixedFpsValues.add(range.getUpper());
                        }
                    }
                    List<Integer> sortedFpsValues = new ArrayList<>(fixedFpsValues);
                    Collections.sort(sortedFpsValues, Collections.reverseOrder());
                    for (Integer fps : sortedFpsValues) {
                        highSpeedOptions.add(new ResolutionOption(
                                Resolution.of(size.getWidth(), size.getHeight()), fps));
                    }
                }
            }

            List<Resolution> sortedResolutions = new ArrayList<>(resolutions);
            sortResolutions(sortedResolutions);
            Collections.sort(highSpeedOptions, new Comparator<ResolutionOption>() {
                @Override
                public int compare(ResolutionOption left, ResolutionOption right) {
                    int resolutionCompare = compareResolutions(left.resolution, right.resolution);
                    if (resolutionCompare != 0) return resolutionCompare;
                    return Integer.compare(right.fixedHighSpeedFps, left.fixedHighSpeedFps);
                }
            });

            List<ResolutionOption> options = new ArrayList<>();
            options.addAll(highSpeedOptions);
            for (Resolution resolution : sortedResolutions) {
                options.add(new ResolutionOption(resolution, null));
                if (Resolution.FULL_HD_1080P.equals(resolution)) {
                    options.add(new ResolutionOption(resolution, null, 60));
                }
            }
            return options;
        } catch (CameraAccessException | IllegalArgumentException e) {
            e.printStackTrace();
        }

        return Collections.emptyList();
    }

    private void addSizes(Set<Resolution> resolutions, Size[] sizes) {
        if (sizes == null) {
            return;
        }
        for (Size size : sizes) {
            if (size != null) {
                resolutions.add(Resolution.of(size.getWidth(), size.getHeight()));
            }
        }
    }

    private void sortResolutions(List<Resolution> resolutions) {
        Collections.sort(resolutions, this::compareResolutions);
    }

    private int compareResolutions(Resolution left, Resolution right) {
        long rightArea = (long) right.getWidth() * right.getHeight();
        long leftArea = (long) left.getWidth() * left.getHeight();
        int areaCompare = Long.compare(rightArea, leftArea);
        return areaCompare != 0 ? areaCompare : Integer.compare(right.getWidth(), left.getWidth());
    }

    private int findResolutionOptionIndex(CameraConfig config) {
        for (int i = 0; i < resolutionOptions.size(); i++) {
            ResolutionOption option = resolutionOptions.get(i);
            if (option.resolution.equals(config.getResolution())
                    && option.fixedHighSpeedFps != null
                    && option.fixedHighSpeedFps == config.getFrameRate()) {
                return i;
            }
            if (option.resolution.equals(config.getResolution())
                    && option.preferredFps != null
                    && option.preferredFps == config.getFrameRate()) {
                return i;
            }
        }
        ResolutionOption regularOption = findRegularOption(config.getResolution());
        if (regularOption != null) {
            return resolutionOptions.indexOf(regularOption);
        }
        return -1;
    }

    private ResolutionOption findRegularOption(Resolution resolution) {
        for (ResolutionOption option : resolutionOptions) {
            if (option.fixedHighSpeedFps == null && option.resolution.equals(resolution)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Resolution entries which name an FPS are explicit recording presets.
     * Keep the 1080p60 preset out of the generic 15/24/30 FPS capability
     * list, otherwise reopening Settings silently replaces its saved 60 FPS.
     */
    private Integer getSelectedPresetFps() {
        Object selectedItem = resolutionSpinner.getSelectedItem();
        if (!(selectedItem instanceof ResolutionOption)) {
            return null;
        }
        ResolutionOption option = (ResolutionOption) selectedItem;
        return option.fixedHighSpeedFps != null
                ? option.fixedHighSpeedFps : option.preferredFps;
    }

    private int findFrameRateOptionIndex(List<FrameRateOption> options, int fps) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).fps == fps) {
                return i;
            }
        }
        return -1;
    }

    private static final class ResolutionOption {
        private final Resolution resolution;
        private final Integer fixedHighSpeedFps;
        private final Integer preferredFps;

        private ResolutionOption(Resolution resolution, Integer fixedHighSpeedFps) {
            this(resolution, fixedHighSpeedFps, null);
        }

        private ResolutionOption(Resolution resolution, Integer fixedHighSpeedFps,
                                 Integer preferredFps) {
            this.resolution = resolution;
            this.fixedHighSpeedFps = fixedHighSpeedFps;
            this.preferredFps = preferredFps;
        }

        @Override
        public String toString() {
            if (fixedHighSpeedFps != null) {
                return resolution + " [HFR " + fixedHighSpeedFps + " FPS]";
            }
            return preferredFps == null ? resolution.toString()
                    : resolution + " [" + preferredFps + " FPS]";
        }
    }

    private static final class FrameRateOption {
        private final int fps;
        private final boolean fixed;

        private FrameRateOption(int fps, boolean fixed) {
            this.fps = fps;
            this.fixed = fixed;
        }

        @Override
        public String toString() {
            return fixed ? fps + " FPS (fixed)" : fps + " FPS";
        }
    }

    
}
