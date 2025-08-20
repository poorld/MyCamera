package com.android.mycamera.record;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.camera.core.CameraInfo;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CameraSetupHelper {
    private static final String TAG = "CameraSetupHelper";
    
    private final VideoRecordActivity activity;
    private ArrayAdapter<String> cameraAdapter;
    private ArrayAdapter<String> cameraXAdapter;
    private ArrayAdapter<String> qualityAdapter;
    private Map<String, Quality> qualityMap;
    private ArrayAdapter<String> fpsAdapter;
    
    public CameraSetupHelper(VideoRecordActivity activity) {
        this.activity = activity;
    }
    
    public void setupSpinners(String cameraId, Spinner resolutionSpinner, Spinner fpsSpinner) {
        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            
            setupResolutionAdapter(characteristics, resolutionSpinner);
            setupFpsAdapter(characteristics, fpsSpinner);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera", e);
        }
    }
    
    private void setupResolutionAdapter(CameraCharacteristics characteristics, Spinner resolutionSpinner) {
        String selectedApi = activity.getSelectedApi();
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        
        if ("CameraX".equals(selectedApi)) {
            setupCameraXResolutionAdapter(resolutionSpinner);
        } else {
            setupCameraResolutionAdapter(map, resolutionSpinner);
        }
    }
    
    private void setupCameraXResolutionAdapter(Spinner resolutionSpinner) {
        if (qualityAdapter != null) {
            resolutionSpinner.setAdapter(qualityAdapter);
            setMiddleSelection(resolutionSpinner);
        } else if (cameraXAdapter == null) {
            cameraXAdapter = createCameraXAdapter();
            resolutionSpinner.setAdapter(cameraXAdapter);
            setMiddleSelection(resolutionSpinner);
        }
    }
    
    private void setupCameraResolutionAdapter(StreamConfigurationMap map, Spinner resolutionSpinner) {
        if (cameraAdapter == null) {
            cameraAdapter = createCameraAdapter(map);
        }
        resolutionSpinner.setAdapter(cameraAdapter);
        setMiddleSelection(resolutionSpinner);
    }
    
    private void setupFpsAdapter(CameraCharacteristics characteristics, Spinner fpsSpinner) {
        if (fpsAdapter == null) {
            fpsAdapter = createFpsAdapter(characteristics);
        }
        fpsSpinner.setAdapter(fpsAdapter);
    }
    
    private ArrayAdapter<String> createCameraXAdapter() {
        qualityMap = new HashMap<>();
        List<Quality> camXQualities = Arrays.asList(Quality.HIGHEST);
        List<String> resolutions = new ArrayList<>();
        
        for (Quality quality : camXQualities) {
            String name = ((Quality.ConstantQuality) quality).getName();
            qualityMap.put(name, quality);
            resolutions.add(name);
        }
        
        return createSpinnerAdapter(resolutions);
    }
    
    private ArrayAdapter<String> createCameraAdapter(StreamConfigurationMap map) {
        Size[] outputSizes = map.getOutputSizes(MediaRecorder.class);
        Set<String> resolutions = new HashSet<>();
        
        for (Size size : outputSizes) {
            String resolution = size.getWidth() + "x" + size.getHeight();
            resolutions.add(resolution);
        }
        
        List<String> sortedResolutions = new ArrayList<>(resolutions);
        Collections.sort(sortedResolutions, (a, b) -> {
            int widthA = Integer.parseInt(a.split("x")[0]);
            int widthB = Integer.parseInt(b.split("x")[0]);
            int heightA = Integer.parseInt(a.split("x")[1]);
            int heightB = Integer.parseInt(b.split("x")[1]);
            return widthA == widthB ? heightB - heightA : widthB - widthA;
        });
        
        return createSpinnerAdapter(sortedResolutions);
    }
    
    private ArrayAdapter<String> createFpsAdapter(CameraCharacteristics characteristics) {
        Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Set<String> frameRatesSet = new HashSet<>();
        
        for (Range<Integer> range : fpsRanges) {
            frameRatesSet.add(range.getUpper().toString());
        }
        
        List<String> frameRates = new ArrayList<>(frameRatesSet);
        Collections.sort(frameRates);
        return createSpinnerAdapter(frameRates);
    }
    
    private ArrayAdapter<String> createSpinnerAdapter(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            activity, 
            android.R.layout.simple_spinner_item, 
            items
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }
    
    private void setMiddleSelection(Spinner spinner) {
        int count = spinner.getAdapter().getCount();
        if (count > 0) {
            int selection = count == 3 ? 1 : count / 2;
            spinner.setSelection(selection);
        }
    }
    
    public void updateCameraXAdapter(CameraInfo cameraInfo, Spinner resolutionSpinner) {
        if (qualityAdapter != null) return;
        
        qualityMap = new HashMap<>();
        List<Quality> camXQualities = QualitySelector.getSupportedQualities(cameraInfo);
        List<String> resolutions = new ArrayList<>();
        
        for (Quality quality : camXQualities) {
            String name = ((Quality.ConstantQuality) quality).getName();
            qualityMap.put(name, quality);
            resolutions.add(name);
        }
        
        qualityAdapter = createSpinnerAdapter(resolutions);
        resolutionSpinner.setAdapter(qualityAdapter);
        setMiddleSelection(resolutionSpinner);
    }
    
    public void clearAdapters() {
        cameraAdapter = null;
        cameraXAdapter = null;
        qualityAdapter = null;
        qualityMap = null;
        fpsAdapter = null;
    }
    
    public Map<String, Quality> getQualityMap() {
        return qualityMap;
    }
}