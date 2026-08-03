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
import com.android.mycamera.model.PhotoResolution;
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
    private Spinner bitrateSpinner;
    private Spinner videoCodecSpinner;
    private Spinner qualitySpinner;
    private Spinner photoResolutionSpinner;
    private Switch audioEnabledSwitch;
    private Switch backgroundReviewSwitch;
    private Switch backgroundRecordingSwitch;
    private Switch keepScreenOnSwitch;
    private Switch showApiSwitcherSwitch;
    private Spinner languageSpinner;
    private TextView resolutionLabel;
    private TextView qualityLabel;
    private TextView photoResolutionLabel;
    private TextView videoCodecLabel;
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
        bitrateSpinner = findViewById(R.id.bitrateSpinner);
        videoCodecSpinner = findViewById(R.id.videoCodecSpinner);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        photoResolutionSpinner = findViewById(R.id.photoResolutionSpinner);
        audioEnabledSwitch = findViewById(R.id.audioEnabledSwitch);
        backgroundReviewSwitch = findViewById(R.id.backgroundReviewSwitch);
        backgroundRecordingSwitch = findViewById(R.id.backgroundRecordingSwitch);
        keepScreenOnSwitch = findViewById(R.id.keepScreenOnSwitch);
        showApiSwitcherSwitch = findViewById(R.id.showApiSwitcherSwitch);
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
    }
    
    private void setupResolutionSpinner() {
        resolutionOptions = getFixedResolutionOptions();
        
        ArrayAdapter<ResolutionOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        isUpdatingResolutionSpinner = true;
        resolutionSpinner.setAdapter(adapter);
        
        int position = findResolutionOptionIndex(currentConfig);
        if (position < 0) {
            ResolutionOption fallback = findResolutionOption(currentConfig.getResolution());
            if (fallback == null) {
                fallback = findResolutionOption(Resolution.FULL_HD_1080P);
            }
            if (fallback == null) {
                fallback = resolutionOptions.get(0);
            }
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setResolution(fallback.resolution)
                    .setQuality(matchQualityForResolution(fallback.resolution))
                    .setFrameRate(fallback.fixedFrameRate)
                    .build();
            applyAndSaveChanges();
            position = resolutionOptions.indexOf(fallback);
        }
        resolutionSpinner.setSelection(Math.max(position, 0), false);
        isUpdatingResolutionSpinner = false;
    }
    
    private void setupFrameRateSpinner() {
        int fixedFrameRate = getFixedFrameRate(currentConfig.getResolution());
        List<FrameRateOption> frameRateOptions = Collections.singletonList(
                new FrameRateOption(fixedFrameRate));
        
        ArrayAdapter<FrameRateOption> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item_light, frameRateOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        frameRateSpinner.setAdapter(adapter);
        frameRateSpinner.setEnabled(false);
        frameRateSpinner.setAlpha(0.6f);

        if (fixedFrameRate != currentConfig.getFrameRate()) {
            currentConfig = new CameraConfig.Builder(currentConfig)
                    .setFrameRate(fixedFrameRate)
                    .build();
            applyAndSaveChanges();
        }
        frameRateSpinner.setSelection(0, false);
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
                int selectedFps = selectedOption.fixedFrameRate;
                if (!selectedOption.resolution.equals(currentConfig.getResolution())
                        || selectedFps != currentConfig.getFrameRate()) {
                    // Camera2/1 are resolution-driven; keep quality label in sync.
                    Quality matchedQuality = matchQualityForResolution(selectedOption.resolution);
                    currentConfig = new CameraConfig.Builder(currentConfig)
                            .setResolution(selectedOption.resolution)
                            .setQuality(matchedQuality)
                            .setFrameRate(selectedFps)
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
                            .setFrameRate(getFixedFrameRate(selectedResolution))
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

    private List<ResolutionOption> getFixedResolutionOptions() {
        // Y1 recording presets. FPS is selected with the resolution and cannot be changed separately.
        List<ResolutionOption> options = new ArrayList<>();
        options.add(new ResolutionOption(Resolution.QHD_2K, 30));
        options.add(new ResolutionOption(Resolution.FULL_HD_1080P, 30));
        options.add(new ResolutionOption(Resolution.HD_720P, 120));
        return options;
    }

    private int getFixedFrameRate(Resolution resolution) {
        return Resolution.HD_720P.equals(resolution) ? 120 : DEFAULT_VIDEO_FRAME_RATE;
    }

    private int findResolutionOptionIndex(CameraConfig config) {
        for (int i = 0; i < resolutionOptions.size(); i++) {
            ResolutionOption option = resolutionOptions.get(i);
            if (option.resolution.equals(config.getResolution())
                    && option.fixedFrameRate == config.getFrameRate()) {
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
        private final int fixedFrameRate;

        private ResolutionOption(Resolution resolution, int fixedFrameRate) {
            this.resolution = resolution;
            this.fixedFrameRate = fixedFrameRate;
        }

        @Override
        public String toString() {
            return resolution + " [" + fixedFrameRate + " FPS]";
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
