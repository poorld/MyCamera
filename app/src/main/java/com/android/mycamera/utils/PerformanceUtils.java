package com.android.mycamera.utils;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for performance monitoring and optimization
 */
public class PerformanceUtils {
    
    private static final String TAG = "PerformanceUtils";
    private static final Map<String, Long> startTimeMap = new HashMap<>();
    private static final Map<String, Long> executionTimeMap = new HashMap<>();
    
    /**
     * Start measuring execution time for a task
     */
    public static void startMeasurement(String taskName) {
        startTimeMap.put(taskName, System.currentTimeMillis());
    }
    
    /**
     * Stop measuring execution time and log the result
     */
    public static void endMeasurement(String taskName) {
        Long startTime = startTimeMap.get(taskName);
        if (startTime != null) {
            long executionTime = System.currentTimeMillis() - startTime;
            executionTimeMap.put(taskName, executionTime);
            Log.d(TAG, taskName + " executed in " + executionTime + "ms");
            startTimeMap.remove(taskName);
        }
    }
    
    /**
     * Get execution time for a task
     */
    public static long getExecutionTime(String taskName) {
        return executionTimeMap.getOrDefault(taskName, -1L);
    }
    
    /**
     * Log current memory usage
     */
    public static void logMemoryUsage(String tag) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        
        Log.d(tag, String.format(
                "Memory: Used=%dMB, Free=%dMB, Max=%dMB, Usage=%.2f%%",
                usedMemory / (1024 * 1024),
                freeMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                (usedMemory * 100.0 / maxMemory)
        ));
    }
    
    /**
     * Check if memory usage is critical (>80%)
     */
    public static boolean isMemoryUsageCritical() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (usedMemory * 100.0 / maxMemory) > 80.0;
    }
    
    /**
     * Suggest garbage collection if memory usage is high
     */
    public static void suggestGarbageCollection() {
        if (isMemoryUsageCritical()) {
            Log.w(TAG, "Memory usage is critical, suggesting garbage collection");
            System.gc();
        }
    }
    
    /**
     * Clear execution time measurements
     */
    public static void clearMeasurements() {
        startTimeMap.clear();
        executionTimeMap.clear();
    }
    
    /**
     * Get performance summary
     */
    public static String getPerformanceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Performance Summary:\n");
        
        for (Map.Entry<String, Long> entry : executionTimeMap.entrySet()) {
            summary.append(entry.getKey())
                   .append(": ")
                   .append(entry.getValue())
                   .append("ms\n");
        }
        
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double memoryUsagePercent = (usedMemory * 100.0 / maxMemory);
        
        summary.append(String.format("Memory Usage: %.2f%%\n", memoryUsagePercent));
        
        return summary.toString();
    }
}