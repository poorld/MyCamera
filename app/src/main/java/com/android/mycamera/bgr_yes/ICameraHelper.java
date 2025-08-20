package com.android.mycamera.bgr_yes;

import android.view.TextureView;
import androidx.lifecycle.LifecycleOwner;

public interface ICameraHelper {
    void openCamera(int width, int height, int fps, String cameraId);
    void startPreview(TextureView textureView, LifecycleOwner lifecycleOwner);
    void stopPreview();
    void startRecord();
    void stopRecord();
    void closeCamera();

    void setPreviewListener(IPreViewListener previewListener);
}
