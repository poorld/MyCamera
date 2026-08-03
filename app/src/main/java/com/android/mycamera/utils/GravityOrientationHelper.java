package com.android.mycamera.utils;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/** Requests a portrait or landscape window orientation from the device gravity. */
public final class GravityOrientationHelper {

    private static final String TAG = "GravityOrientation";

    public interface Listener {
        void onOrientationChanged(boolean landscape, float orientationDegrees);
    }

    private final Activity activity;
    private final Listener listener;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener sensorListener;
    private int lastRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean hasStableOrientation;
    private boolean lastLandscape;

    public GravityOrientationHelper(Activity activity) {
        this(activity, null);
    }

    public GravityOrientationHelper(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start() {
        if (sensorManager == null) {
            sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
        }
        if (accelerometer == null && sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (sensorListener == null) {
            sensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event == null || event.values == null || event.values.length < 2) {
                        return;
                    }
                    updateRequestedOrientation(event.values[0], event.values[1]);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    // Gravity direction does not require a calibrated heading.
                }
            };
        }
        if (sensorManager == null || accelerometer == null) {
            Log.w(TAG, "Accelerometer is unavailable");
            return;
        }
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    public void stop() {
        if (sensorManager != null && sensorListener != null) {
            sensorManager.unregisterListener(sensorListener);
        }
    }

    private void updateRequestedOrientation(float gravityX, float gravityY) {
        float planarGravity = (float) Math.sqrt(gravityX * gravityX + gravityY * gravityY);
        // When the phone is lying flat, X/Y cannot distinguish portrait from
        // landscape. Keep the last stable orientation until it is picked up.
        if (planarGravity < 1f) {
            return;
        }

        // Match OrientationEventListener's coordinate convention: 0 degrees
        // is portrait, 90/270 degrees are the two landscape directions.
        float orientationDegrees = (float) Math.toDegrees(Math.atan2(-gravityX, gravityY));
        if (orientationDegrees < 0f) {
            orientationDegrees += 360f;
        }
        boolean landscape = (orientationDegrees >= 45f && orientationDegrees < 135f)
                || (orientationDegrees >= 225f && orientationDegrees < 315f);
        if (hasStableOrientation && landscape == lastLandscape) {
            return;
        }
        lastLandscape = landscape;
        hasStableOrientation = true;
        applyRequestedOrientation(landscape, orientationDegrees);
    }

    private void applyRequestedOrientation(boolean landscape, float orientationDegrees) {
        int requestedOrientation;
        if (landscape) {
            requestedOrientation = orientationDegrees < 180f
                    ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            requestedOrientation = orientationDegrees < 90f || orientationDegrees >= 270f
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        if (requestedOrientation == lastRequestedOrientation) {
            return;
        }
        lastRequestedOrientation = requestedOrientation;
        Log.d(TAG, "gravity=" + Math.round(orientationDegrees) + ", request="
                + orientationName(requestedOrientation));
        activity.setRequestedOrientation(requestedOrientation);
        if (listener != null) {
            listener.onOrientationChanged(landscape, orientationDegrees);
        }
    }

    private String orientationName(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                return "LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return "REVERSE_LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return "REVERSE_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            default:
                return "PORTRAIT";
        }
    }
}
