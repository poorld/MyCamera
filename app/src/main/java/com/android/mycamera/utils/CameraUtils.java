package com.android.mycamera.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

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
     * Public save directory shown in settings: /sdcard/DCIM/Camera
     */
    public static File createCameraDirectory(Context context) {
        File cameraDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera");
        if (!cameraDir.exists() && !cameraDir.mkdirs()) {
            Log.w("CameraUtils", "Failed to create DCIM/Camera, fallback to app Media dir");
            File fallback = context.getExternalFilesDir("Media");
            if (fallback == null) {
                fallback = new File(context.getFilesDir(), "Media");
            }
            if (!fallback.exists()) {
                fallback.mkdirs();
            }
            return fallback;
        }
        return cameraDir;
    }

    /** Prefer /sdcard/... display form for UI. */
    public static String getDisplaySavePath(File dir) {
        if (dir == null) {
            return "/sdcard/DCIM/Camera";
        }
        String path = dir.getAbsolutePath().replace('\\', '/');
        if (path.startsWith("/storage/emulated/0")) {
            return "/sdcard" + path.substring("/storage/emulated/0".length());
        }
        return path;
    }

    public static boolean isLegacyAppMediaPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        return normalized.contains("/Android/data/com.android.mycamera")
                || normalized.contains("/android/data/com.android.mycamera");
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
    public static File generateUniqueMediaFile(Context context, String extension) {
        File cameraDir = createCameraDirectory(context);
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
