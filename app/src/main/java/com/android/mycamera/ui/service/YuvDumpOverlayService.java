package com.android.mycamera.ui.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.android.mycamera.R;
import com.android.mycamera.utils.SystemPropertyUtils;

public class YuvDumpOverlayService extends Service {
    public static final String ACTION_OVERLAY_STATE_CHANGED = "com.android.mycamera.action.YUV_DUMP_OVERLAY_STATE_CHANGED";
    public static final String ACTION_CAPTURE_FINISHED = "com.android.mycamera.action.YUV_DUMP_CAPTURE_FINISHED";
    private static final String CHANNEL_ID = "yuv_dump_overlay";
    private static final int NOTIFICATION_ID = 1003;
    private static final String PROP_OUTPUT = "vendor.debug.p2f.dump.out";
    private static final String PROP_CONTINUE = "vendor.debug.camera.continue.dump";
    private static final int OUTPUT_DISPLAY = 1;
    private static final int OUTPUT_RECORD = 2;

    private static volatile boolean overlayVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private TextView captureButton;
    private boolean captureInProgress;

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        captureInProgress = false;
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, "0");
        } catch (RuntimeException ignored) {
            // The service is already stopping; there is no UI action left to report.
        }
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        captureButton = null;
        overlayVisible = false;
        sendOverlayStateChanged();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView button = new TextView(this);
        captureButton = button;
        button.setText(R.string.yuv_dump_overlay_capture);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackground(createOverlayButtonBackground());
        button.setElevation(dp(6));
        button.setOnClickListener(view -> requestOneFrame());
        button.setOnLongClickListener(view -> {
            stopSelf();
            return true;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(88),
                dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(16);
        params.y = dp(160);
        windowManager.addView(button, params);
        overlayView = button;
        overlayVisible = true;
        sendOverlayStateChanged();
    }

    private void requestOneFrame() {
        if (captureInProgress) {
            return;
        }
        if ((getIntProperty(PROP_OUTPUT) & (OUTPUT_DISPLAY | OUTPUT_RECORD)) == 0) {
            Toast.makeText(this, R.string.yuv_dump_capture_no_output, Toast.LENGTH_SHORT).show();
            return;
        }

        captureInProgress = true;
        captureButton.setEnabled(false);
        captureButton.setText(R.string.yuv_dump_overlay_capturing);
        captureButton.setAlpha(0.7f);
        Toast.makeText(this, R.string.yuv_dump_capture_requested, Toast.LENGTH_SHORT).show();
        handler.removeCallbacksAndMessages(null);
        try {
            if (!setContinue("0")) {
                return;
            }
            handler.postDelayed(() -> setContinue("1"), 150);
            handler.postDelayed(() -> setContinue("0"), 1150);
            handler.postDelayed(this::finishCapture, 1500);
        } catch (RuntimeException exception) {
            handleCaptureError(exception);
        }
    }

    private boolean setContinue(String value) {
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, value);
            return true;
        } catch (RuntimeException exception) {
            handleCaptureError(exception);
            return false;
        }
    }

    private void finishCapture() {
        if (!captureInProgress) {
            return;
        }
        captureInProgress = false;
        captureButton.setEnabled(true);
        captureButton.setText(R.string.yuv_dump_overlay_capture);
        captureButton.setAlpha(1f);
        sendBroadcast(new Intent(ACTION_CAPTURE_FINISHED).setPackage(getPackageName()));
    }

    private void handleCaptureError(RuntimeException exception) {
        handler.removeCallbacksAndMessages(null);
        captureInProgress = false;
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, "0");
        } catch (RuntimeException ignored) {
            // Keep the original error visible if the property service is unavailable.
        }
        if (captureButton != null) {
            captureButton.setEnabled(true);
            captureButton.setText(R.string.yuv_dump_overlay_capture);
            captureButton.setAlpha(1f);
        }
        String message = exception.getMessage();
        if (message == null || message.length() == 0) {
            message = exception.getClass().getSimpleName();
        }
        Toast.makeText(this, getString(R.string.yuv_dump_capture_error, message),
                Toast.LENGTH_LONG).show();
    }

    private int getIntProperty(String key) {
        try {
            return Integer.parseInt(SystemPropertyUtils.get(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private RippleDrawable createOverlayButtonBackground() {
        GradientDrawable content = new GradientDrawable();
        content.setColor(Color.rgb(35, 113, 185));
        content.setCornerRadius(dp(24));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(24));
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(110, 255, 255, 255)),
                content,
                mask);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.yuv_dump_overlay_title))
                .setContentText(getString(R.string.yuv_dump_overlay_notification))
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.yuv_dump_overlay_title),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void sendOverlayStateChanged() {
        sendBroadcast(new Intent(ACTION_OVERLAY_STATE_CHANGED));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
