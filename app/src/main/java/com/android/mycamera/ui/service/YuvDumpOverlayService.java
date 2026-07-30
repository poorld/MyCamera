package com.android.mycamera.ui.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.android.mycamera.R;
import com.android.mycamera.utils.SystemPropertyUtils;

public class YuvDumpOverlayService extends Service {
    public static final String ACTION_OVERLAY_STATE_CHANGED = "com.android.mycamera.action.YUV_DUMP_OVERLAY_STATE_CHANGED";
    private static final String CHANNEL_ID = "yuv_dump_overlay";
    private static final int NOTIFICATION_ID = 1003;
    private static final String PROP_CONTINUE = "vendor.debug.camera.continue.dump";

    private static volatile boolean overlayVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;

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
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
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
        button.setText(R.string.yuv_dump_overlay_capture);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(35, 113, 185));
        background.setCornerRadius(dp(24));
        button.setBackground(background);
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
        handler.removeCallbacksAndMessages(null);
        SystemPropertyUtils.set(PROP_CONTINUE, "0");
        handler.postDelayed(() -> SystemPropertyUtils.set(PROP_CONTINUE, "1"), 150);
        handler.postDelayed(() -> SystemPropertyUtils.set(PROP_CONTINUE, "0"), 1150);
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
