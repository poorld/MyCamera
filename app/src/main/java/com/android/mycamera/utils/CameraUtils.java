package com.android.mycamera.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class for camera-related operations
 */
public class CameraUtils {
    
    /**
     * Check if all required permissions are granted
     */
    public static boolean hasAllPermissions(Context context) {
        String[] requiredPermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get required permissions array
     */
    public static String[] getRequiredPermissions() {
        return new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
    }
    
    /**
     * Create camera directory if it doesn't exist
     */
    public static File createCameraDirectory() {
        File cameraDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        if (!cameraDir.exists()) {
            cameraDir.mkdirs();
        }
        return cameraDir;
    }
    
    /**
     * Generate unique file name for media
     */
    public static String generateUniqueFileName(String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "CAM_" + timestamp + "." + extension;
    }
    
    /**
     * Generate unique file path for media
     */
    public static File generateUniqueMediaFile(String extension) {
        File cameraDir = createCameraDirectory();
        String fileName = generateUniqueFileName(extension);
        return new File(cameraDir, fileName);
    }
    
    /**
     * Get available storage space
     */
    public static long getAvailableStorageSpace() {
        android.os.StatFs stat = new android.os.StatFs(Environment.getExternalStorageDirectory().getPath());
        long availableBlocks = stat.getAvailableBlocksLong();
        long blockSize = stat.getBlockSizeLong();
        return availableBlocks * blockSize;
    }
    
    /**
     * Check if there's enough storage space (at least 100MB)
     */
    public static boolean hasEnoughStorageSpace() {
        return getAvailableStorageSpace() > 100 * 1024 * 1024; // 100MB
    }
    
    /**
     * Format file size to human readable format
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}