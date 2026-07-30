package com.android.mycamera.utils;

import android.os.SystemProperties;
import android.util.Log;

public final class SystemPropertyUtils {
    private static final String TAG = "SystemPropertyUtils";

    private SystemPropertyUtils() {
    }

    public static void set(String key, String value) {
        SystemProperties.set(key, value);
        Log.d(TAG, "setprop " + key + "=" + value);
    }

    public static String get(String key, String def) {
        return SystemProperties.get(key, def);
    }
}
