package com.android.mycamera.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.TextureView;

import com.android.mycamera.utils.PerformanceUtils;

/**
 * Optimized camera preview view with hardware acceleration
 */
public class OptimizedCameraPreview extends TextureView {
    
    private boolean isHardwareAccelerated = true;
    private boolean isOptimizedForPerformance = false;
    
    public OptimizedCameraPreview(Context context) {
        super(context);
        initialize();
    }
    
    public OptimizedCameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }
    
    public OptimizedCameraPreview(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }
    
    private void initialize() {
        // Enable hardware acceleration by default
        setLayerType(LAYER_TYPE_HARDWARE, null);
        isOptimizedForPerformance = true;
    }
    
    // TextureView.onDraw() is final and cannot be overridden
    // Hardware acceleration is handled by setLayerType() in initialize()
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        PerformanceUtils.startMeasurement("OptimizedCameraPreview_onMeasure");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        PerformanceUtils.endMeasurement("OptimizedCameraPreview_onMeasure");
    }
    
    /**
     * Optimize the preview for better performance
     */
    public void optimizeForPerformance() {
        if (!isOptimizedForPerformance) {
            PerformanceUtils.startMeasurement("OptimizedCameraPreview_optimize");
            
            // Set optimal surface size
            android.view.Display display = ((android.app.Activity) getContext()).getWindowManager().getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            display.getSize(size);
            
            int optimalWidth = size.x;
            int optimalHeight = size.y;
            
            if (getSurfaceTexture() != null) {
                getSurfaceTexture().setDefaultBufferSize(optimalWidth, optimalHeight);
            }
            
            // Enable hardware acceleration
            setLayerType(LAYER_TYPE_HARDWARE, null);
            isHardwareAccelerated = true;
            
            // Set rendering hints
            setWillNotDraw(false);
            setDrawingCacheEnabled(false);
            
            isOptimizedForPerformance = true;
            PerformanceUtils.endMeasurement("OptimizedCameraPreview_optimize");
        }
    }
    
    /**
     * Set hardware acceleration enabled/disabled
     */
    public void setHardwareAccelerationEnabled(boolean enabled) {
        if (isHardwareAccelerated != enabled) {
            isHardwareAccelerated = enabled;
            setLayerType(enabled ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        }
    }
    
    /**
     * Check if hardware acceleration is enabled
     */
    public boolean isHardwareAccelerationEnabled() {
        return isHardwareAccelerated;
    }
    
    /**
     * Check if view is optimized for performance
     */
    public boolean isOptimizedForPerformance() {
        return isOptimizedForPerformance;
    }
    
    /**
     * Release resources
     */
    public void release() {
        if (getSurfaceTexture() != null) {
            getSurfaceTexture().release();
        }
        isOptimizedForPerformance = false;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }
}