package com.android.mycamera.ui.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;
import com.android.mycamera.ui.service.YuvDumpOverlayService;
import com.android.mycamera.utils.SystemPropertyUtils;

public class YuvDumpActivity extends AppCompatActivity {
    private static final String PROP_ENABLE = "vendor.debug.p2f.dump.enable";
    private static final String PROP_MODE = "vendor.debug.p2f.dump.mode";
    private static final String PROP_INPUT = "vendor.debug.p2f.dump.in";
    private static final String PROP_OUTPUT = "vendor.debug.p2f.dump.out";
    private static final String PROP_START = "vendor.debug.p2f.dump.start";
    private static final String PROP_COUNT = "vendor.debug.p2f.dump.count";
    private static final String PROP_CONTINUE = "vendor.debug.camera.continue.dump";
    private static final int P2_DUMP_DEBUG_MODE = 2;

    private static final int INPUT_RRZO = 1;
    private static final int OUTPUT_DISPLAY = 1;
    private static final int OUTPUT_RECORD = 2;

    private Switch enableSwitch;
    private Switch rrzoSwitch;
    private Switch displaySwitch;
    private Switch recordSwitch;
    private TextView statusText;
    private Button overlayButton;
    private Button openFilesButton;
    private boolean overlayReceiverRegistered;
    private final BroadcastReceiver overlayStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateOverlayButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yuv_dump);

        enableSwitch = findViewById(R.id.yuvDumpEnableSwitch);
        rrzoSwitch = findViewById(R.id.yuvDumpRrzoSwitch);
        displaySwitch = findViewById(R.id.yuvDumpDisplaySwitch);
        recordSwitch = findViewById(R.id.yuvDumpRecordSwitch);
        statusText = findViewById(R.id.yuvDumpStatusText);
        overlayButton = findViewById(R.id.yuvDumpOverlayButton);
        openFilesButton = findViewById(R.id.yuvDumpOpenFilesButton);

        loadProperties();
        View.OnClickListener listener = view -> applyProperties();
        enableSwitch.setOnClickListener(listener);
        rrzoSwitch.setOnClickListener(listener);
        displaySwitch.setOnClickListener(listener);
        recordSwitch.setOnClickListener(listener);
        overlayButton.setOnClickListener(view -> toggleOverlay());
        openFilesButton.setOnClickListener(view ->
                startActivity(new Intent(this, YuvDumpFilesActivity.class)));
        updateOverlayButton();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!overlayReceiverRegistered) {
            IntentFilter filter = new IntentFilter(
                    YuvDumpOverlayService.ACTION_OVERLAY_STATE_CHANGED);
            registerReceiver(overlayStateReceiver, filter);
            overlayReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (overlayReceiverRegistered) {
            unregisterReceiver(overlayStateReceiver);
            overlayReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOverlayButton();
    }

    private void loadProperties() {
        enableSwitch.setChecked(getIntProperty(PROP_ENABLE) > 0);
        rrzoSwitch.setChecked((getIntProperty(PROP_INPUT) & INPUT_RRZO) != 0);
        displaySwitch.setChecked((getIntProperty(PROP_OUTPUT) & OUTPUT_DISPLAY) != 0);
        recordSwitch.setChecked((getIntProperty(PROP_OUTPUT) & OUTPUT_RECORD) != 0);
        updateStatus();
    }

    private void applyProperties() {
        if (enableSwitch.isChecked()) {
            // P2_DUMP_DEBUG is 2; mode 0 means P2_DUMP_NONE on mtkcam3.
            SystemPropertyUtils.set(PROP_MODE, Integer.toString(P2_DUMP_DEBUG_MODE));
            SystemPropertyUtils.set(PROP_ENABLE, "1");
            SystemPropertyUtils.set(PROP_INPUT, Integer.toString(rrzoSwitch.isChecked() ? INPUT_RRZO : 0));
            int outputMask = (displaySwitch.isChecked() ? OUTPUT_DISPLAY : 0)
                    | (recordSwitch.isChecked() ? OUTPUT_RECORD : 0);
            SystemPropertyUtils.set(PROP_OUTPUT, Integer.toString(outputMask));
            SystemPropertyUtils.set(PROP_START, "0");
            SystemPropertyUtils.set(PROP_COUNT, "0");
        } else {
            SystemPropertyUtils.set(PROP_ENABLE, "0");
            SystemPropertyUtils.set(PROP_MODE, "0");
            SystemPropertyUtils.set(PROP_INPUT, "0");
            SystemPropertyUtils.set(PROP_OUTPUT, "0");
            SystemPropertyUtils.set(PROP_CONTINUE, "0");
        }
        updateStatus();
    }

    private void toggleOverlay() {
        if (getIntProperty(PROP_ENABLE) == 0) {
            Toast.makeText(this, R.string.yuv_dump_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent serviceIntent = new Intent(this, YuvDumpOverlayService.class);
        if (YuvDumpOverlayService.isOverlayVisible()) {
            stopService(serviceIntent);
            scheduleOverlayButtonUpdate();
            return;
        }
        if (getIntProperty(PROP_OUTPUT) == 0) {
            Toast.makeText(this, R.string.yuv_dump_capture_no_output, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent permissionIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permissionIntent);
            Toast.makeText(this, R.string.yuv_dump_overlay_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        ContextCompat.startForegroundService(this, serviceIntent);
        scheduleOverlayButtonUpdate();
    }

    private void scheduleOverlayButtonUpdate() {
        overlayButton.postDelayed(this::updateOverlayButton, 300);
        overlayButton.postDelayed(this::updateOverlayButton, 1000);
    }

    private void updateOverlayButton() {
        if (overlayButton != null) {
            overlayButton.setText(YuvDumpOverlayService.isOverlayVisible()
                    ? R.string.yuv_dump_overlay_stop
                    : R.string.yuv_dump_overlay_start);
        }
    }

    private int getIntProperty(String key) {
        try {
            return Integer.parseInt(SystemPropertyUtils.get(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void updateStatus() {
        statusText.setText(getString(R.string.yuv_dump_status,
                getIntProperty(PROP_INPUT), getIntProperty(PROP_OUTPUT)));
    }

}
