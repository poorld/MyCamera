package com.android.mycamera.utils;

import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory management utility for camera application
 */
public class MemoryManager {
    
    private static final String TAG = "MemoryManager";
    private static final double MEMORY_THRESHOLD = 0.8; // 80% of available memory
    private static final long MIN_FREE_MEMORY = 50 * 1024 * 1024; // 50MB
    
    private static MemoryManager instance;
    private final Map<String, WeakReference<Object>> weakReferences = new HashMap<>();
    private final Map<String, Long> lastAccessTime = new HashMap<>();
    
    private MemoryManager() {}
    
    public static synchronized MemoryManager getInstance() {
        if (instance == null) {
            instance = new MemoryManager();
        }
        return instance;
    }
    
    /**
     * Add object to cache with weak reference
     */
    public void addToCache(String key, Object obj) {
        if (isMemoryAvailable()) {
            weakReferences.put(key, new WeakReference<>(obj));
            lastAccessTime.put(key, System.currentTimeMillis());
        } else {
            Log.w(TAG, "Memory threshold reached, clearing cache");
            clearCache();
            weakReferences.put(key, new WeakReference<>(obj));
            lastAccessTime.put(key, System.currentTimeMillis());
        }
    }
    
    /**
     * Get object from cache
     */
    public Object getFromCache(String key) {
        WeakReference<Object> ref = weakReferences.get(key);
        if (ref != null) {
            Object obj = ref.get();
            if (obj != null) {
                lastAccessTime.put(key, System.currentTimeMillis());
                return obj;
            } else {
                // Object was garbage collected, remove from cache
                removeFromCache(key);
            }
        }
        return null;
    }
    
    /**
     * Remove object from cache
     */
    public void removeFromCache(String key) {
        weakReferences.remove(key);
        lastAccessTime.remove(key);
    }
    
    /**
     * Check if memory is available for caching
     */
    public boolean isMemoryAvailable() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        
        return (usedMemory < (maxMemory * MEMORY_THRESHOLD)) && (freeMemory > MIN_FREE_MEMORY);
    }
    
    /**
     * Clear cache and suggest garbage collection
     */
    public void clearCache() {
        Log.d(TAG, "Clearing cache");
        weakReferences.clear();
        lastAccessTime.clear();
        System.gc();
    }
    
    /**
     * Clear old entries from cache (not accessed in last 5 minutes)
     */
    public void clearOldCacheEntries() {
        long currentTime = System.currentTimeMillis();
        long fiveMinutes = 5 * 60 * 1000;
        
        java.util.Iterator<Map.Entry<String, Long>> iterator = lastAccessTime.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > fiveMinutes) {
                String key = entry.getKey();
                weakReferences.remove(key);
                iterator.remove();
            }
        }
    }
    
    /**
     * Get memory usage statistics
     */
    public String getMemoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        return String.format(
                "Memory: Used=%dMB, Free=%dMB, Total=%dMB, Max=%dMB, Usage=%.2f%%",
                usedMemory / (1024 * 1024),
                freeMemory / (1024 * 1024),
                totalMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                (usedMemory * 100.0 / maxMemory)
        );
    }
    
    /**
     * Get cache size
     */
    public int getCacheSize() {
        return weakReferences.size();
    }
    
    /**
     * Log current memory status
     */
    public void logMemoryStatus() {
        Log.d(TAG, getMemoryStats());
        Log.d(TAG, "Cache size: " + getCacheSize());
        
        if (!isMemoryAvailable()) {
            Log.w(TAG, "Memory usage is critical!");
        }
    }
    
    /**
     * Optimize memory usage
     */
    public void optimizeMemory() {
        PerformanceUtils.startMeasurement("MemoryManager_optimize");
        
        // Clear old cache entries
        clearOldCacheEntries();
        
        // If still memory constrained, clear all cache
        if (!isMemoryAvailable()) {
            clearCache();
        }
        
        // Suggest garbage collection
        System.gc();
        
        PerformanceUtils.endMeasurement("MemoryManager_optimize");
    }
}