package com.android.mycamera.multicamera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MultiCameraManager {
    private static final String TAG = "MultiCameraManager";
    
    private Context context;
    private CameraManager cameraManager;
    
    public MultiCameraManager(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }
    
    public CameraManager getCameraManager() {
        return cameraManager;
    }
    
    public boolean isMultiCameraSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                
                for (int capability : capabilities) {
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                        return true;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error checking multi-camera support", e);
        }
        
        return false;
    }
    
    @RequiresApi(api = Build.VERSION_CODES.P)
    public List<LogicalCameraInfo>
        getLogicalCameras() {
        List<LogicalCameraInfo> logicalCameras = new ArrayList<>();
        
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                
                if (isLogicalMultiCamera(characteristics)) {
                    LogicalCameraInfo info = new LogicalCameraInfo(cameraId, characteristics);
                    logicalCameras.add(info);
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting logical cameras", e);
        }
        
        return logicalCameras;
    }
    
    @RequiresApi(api = Build.VERSION_CODES.P)
    private boolean isLogicalMultiCamera(CameraCharacteristics characteristics) {
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) {
            for (int capability : capabilities) {
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public static class LogicalCameraInfo {
        private String cameraId;
        private CameraCharacteristics characteristics;
        private Set<String> physicalCameraIds;
        private Map<String, PhysicalCameraInfo> physicalCameras;
        
        public LogicalCameraInfo(String cameraId, CameraCharacteristics characteristics) {
            this.cameraId = cameraId;
            this.characteristics = characteristics;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.physicalCameraIds = characteristics.getPhysicalCameraIds();
                this.physicalCameras = new HashMap<>();
                
                if (physicalCameraIds != null) {
                    for (String physicalId : physicalCameraIds) {
                        physicalCameras.put(physicalId, new PhysicalCameraInfo(physicalId, characteristics));
                    }
                }
            }
        }
        
        public String getCameraId() {
            return cameraId;
        }
        
        public CameraCharacteristics getCharacteristics() {
            return characteristics;
        }
        
        public Set<String> getPhysicalCameraIds() {
            return physicalCameraIds;
        }
        
        public Map<String, PhysicalCameraInfo> getPhysicalCameras() {
            return physicalCameras;
        }
        
        public boolean hasPhysicalCameras() {
            return physicalCameraIds != null && !physicalCameraIds.isEmpty();
        }
        
        public StreamConfigurationMap getStreamConfigurationMap() {
            return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        }
        
        public int getLensFacing() {
            return characteristics.get(CameraCharacteristics.LENS_FACING);
        }
    }
    
    public static class PhysicalCameraInfo {
        private String physicalCameraId;
        private CameraCharacteristics logicalCharacteristics;
        
        public PhysicalCameraInfo(String physicalCameraId, CameraCharacteristics logicalCharacteristics) {
            this.physicalCameraId = physicalCameraId;
            this.logicalCharacteristics = logicalCharacteristics;
        }
        
        public String getPhysicalCameraId() {
            return physicalCameraId;
        }
        
        public CameraCharacteristics getLogicalCharacteristics() {
            return logicalCharacteristics;
        }
        
        public Size[] getOutputSizes(Class<?> klass) {
            StreamConfigurationMap map = logicalCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map != null ? map.getOutputSizes(klass) : new Size[0];
        }
    }
}