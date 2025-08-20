package com.android.mycamera.bgr_yes;

import androidx.camera.video.Quality;

public abstract class ICameraXHelper implements ICameraHelper {
    public void openCamera(int width, int height, int fps, String cameraId) {

    }

    public abstract void openCamera(Quality quality, int fps);

}
