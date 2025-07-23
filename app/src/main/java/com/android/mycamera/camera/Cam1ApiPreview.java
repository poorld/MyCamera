package com.android.mycamera.camera;


import android.content.Context;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

/**
 * 一个基本的相机预览类
 */
public class Cam1ApiPreview extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "CameraPreview";
    private SurfaceHolder mHolder;
    private Camera mCamera;

    public Cam1ApiPreview(Context context, Camera camera) {
        super(context);
        mCamera = camera;

        // 安装一个 SurfaceHolder.Callback，以便在底层 surface 创建和销毁时得到通知。
        mHolder = getHolder();
        mHolder.addCallback(this);
        // deprecated setting, but required on Android versions prior to 3.0
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // surface 已创建，现在告诉相机在哪里绘制预览。
        try {
            mCamera.setPreviewDisplay(holder);
            mCamera.startPreview();
        } catch (IOException e) {
            Log.d(TAG, "Error setting camera preview: " + e.getMessage());
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // 空。在 activity 中处理相机的释放。
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // 如果预览可以更改或旋转，请在此处处理这些事件。
        // 确保在调整大小或重新格式化之前停止预览。
        if (mHolder.getSurface() == null){
            // preview surface does not exist
            return;
        }

        // 在进行更改之前停止预览
        try {
            mCamera.stopPreview();
        } catch (Exception e){
            // 忽略：试图停止不存在的预览
        }

        // 在此处设置预览大小并进行任何大小调整、旋转或
        // 重新格式化更改

        // 使用新设置启动预览
        try {
            mCamera.setPreviewDisplay(mHolder);
            mCamera.startPreview();
        } catch (Exception e){
            Log.d(TAG, "Error starting camera preview: " + e.getMessage());
        }
    }
}