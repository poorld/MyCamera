package com.android.mycamera;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;

import com.android.mycamera.utils.SystemPropertyUtils;

import java.util.ArrayList;

public class MyCameraApplication extends Application implements CameraXConfig.Provider {

    private static final String PROP_POWER_GLOBAL_ACTION = "persist.sys.power_global_action";

    private int startedActivityCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        setPowerGlobalActionEnabled(false);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (startedActivityCount == 0) {
                    setPowerGlobalActionEnabled(false);
                }
                startedActivityCount++;
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                startedActivityCount = Math.max(0, startedActivityCount - 1);
                if (startedActivityCount == 0) {
                    setPowerGlobalActionEnabled(true);
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    private void setPowerGlobalActionEnabled(boolean enabled) {
        SystemPropertyUtils.set(PROP_POWER_GLOBAL_ACTION, enabled ? "true" : "false");
    }

    @NonNull
    @Override
    public CameraXConfig getCameraXConfig() {
        CameraSelector allAvailableCameras = new CameraSelector.Builder()
                .addCameraFilter(cameraInfos -> new ArrayList<>(cameraInfos))
                .build();
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .setAvailableCamerasLimiter(allAvailableCameras)
                .build();
    }
}
