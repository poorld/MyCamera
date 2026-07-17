package com.android.mycamera;

import android.content.Context;

import com.android.mycamera.utils.CameraUtils;

import java.io.File;

public class Utils {

    public static File getOutputMediaFile(Context context) {
        return CameraUtils.generateUniqueMediaFile(context, "jpg");
    }


}
