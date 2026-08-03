package com.android.mycamera.ui.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;
import com.android.mycamera.ui.service.YuvDumpOverlayService;
import com.android.mycamera.utils.YuvDumpParser;
import com.android.mycamera.utils.SystemPropertyUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YuvDumpActivity extends AppCompatActivity {
    private static final File DUMP_DIRECTORY = new File("/data/vendor/p2_dump");
    private static final int MAX_DUMP_FILES = 12;
    private static final int PREVIEW_MAX_WIDTH = 1280;
    private static final int CAPTURE_SETTLE_DELAY_MS = 1500;
    private static final String PROP_ENABLE = "vendor.debug.p2f.dump.enable";
    private static final String PROP_INPUT = "vendor.debug.p2f.dump.in";
    private static final String PROP_OUTPUT = "vendor.debug.p2f.dump.out";
    private static final String PROP_START = "vendor.debug.p2f.dump.start";
    private static final String PROP_COUNT = "vendor.debug.p2f.dump.count";
    private static final String PROP_CONTINUE = "vendor.debug.camera.continue.dump";

    private static final int INPUT_RRZO = 1;
    private static final int OUTPUT_DISPLAY = 1;
    private static final int OUTPUT_RECORD = 2;

    private Switch enableSwitch;
    private Switch rrzoSwitch;
    private Switch displaySwitch;
    private Switch recordSwitch;
    private TextView statusText;
    private Button overlayButton;
    private Button captureDumpButton;
    private Button refreshDumpButton;
    private Button decodeDumpButton;
    private Spinner dumpFileSpinner;
    private TextView dumpFileStatus;
    private ImageView dumpPreview;
    private ArrayAdapter<String> dumpFileAdapter;
    private List<YuvDumpParser.DumpFile> dumpFiles = Collections.emptyList();
    private Bitmap previewBitmap;
    private final ExecutorService dumpExecutor = Executors.newSingleThreadExecutor();
    private final Handler captureHandler = new Handler(Looper.getMainLooper());
    private int dumpOperationToken;
    private boolean overlayReceiverRegistered;
    private final BroadcastReceiver overlayStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateOverlayButton();
            if (YuvDumpOverlayService.ACTION_CAPTURE_FINISHED.equals(intent.getAction())) {
                refreshDumpFiles();
            }
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
        captureDumpButton = findViewById(R.id.yuvDumpCaptureButton);
        refreshDumpButton = findViewById(R.id.yuvDumpRefreshButton);
        decodeDumpButton = findViewById(R.id.yuvDumpDecodeButton);
        dumpFileSpinner = findViewById(R.id.yuvDumpFileSpinner);
        dumpFileStatus = findViewById(R.id.yuvDumpFileStatus);
        dumpPreview = findViewById(R.id.yuvDumpPreview);

        dumpFileAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>());
        dumpFileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dumpFileSpinner.setAdapter(dumpFileAdapter);

        loadProperties();
        View.OnClickListener listener = view -> applyProperties();
        enableSwitch.setOnClickListener(listener);
        rrzoSwitch.setOnClickListener(listener);
        displaySwitch.setOnClickListener(listener);
        recordSwitch.setOnClickListener(listener);
        overlayButton.setOnClickListener(view -> toggleOverlay());
        captureDumpButton.setOnClickListener(view -> captureOneFrame());
        refreshDumpButton.setOnClickListener(view -> refreshDumpFiles());
        decodeDumpButton.setOnClickListener(view -> decodeSelectedDump());
        updateOverlayButton();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!overlayReceiverRegistered) {
            IntentFilter filter = new IntentFilter(
                    YuvDumpOverlayService.ACTION_OVERLAY_STATE_CHANGED);
            filter.addAction(YuvDumpOverlayService.ACTION_CAPTURE_FINISHED);
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
        refreshDumpFiles();
    }

    @Override
    protected void onDestroy() {
        dumpOperationToken++;
        captureHandler.removeCallbacksAndMessages(null);
        dumpExecutor.shutdownNow();
        clearPreview();
        super.onDestroy();
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
            SystemPropertyUtils.set(PROP_ENABLE, "1");
            SystemPropertyUtils.set(PROP_INPUT, Integer.toString(rrzoSwitch.isChecked() ? INPUT_RRZO : 0));
            int outputMask = (displaySwitch.isChecked() ? OUTPUT_DISPLAY : 0)
                    | (recordSwitch.isChecked() ? OUTPUT_RECORD : 0);
            SystemPropertyUtils.set(PROP_OUTPUT, Integer.toString(outputMask));
            SystemPropertyUtils.set(PROP_START, "0");
            SystemPropertyUtils.set(PROP_COUNT, "0");
        } else {
            SystemPropertyUtils.set(PROP_ENABLE, "0");
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

    private void captureOneFrame() {
        if (getIntProperty(PROP_ENABLE) == 0) {
            Toast.makeText(this, R.string.yuv_dump_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (getIntProperty(PROP_OUTPUT) == 0) {
            Toast.makeText(this, R.string.yuv_dump_capture_no_output, Toast.LENGTH_SHORT).show();
            dumpFileStatus.setText(R.string.yuv_dump_capture_no_output);
            return;
        }

        captureHandler.removeCallbacksAndMessages(null);
        captureDumpButton.setEnabled(false);
        captureDumpButton.setText(R.string.yuv_dump_capture_running);
        dumpFileStatus.setText(R.string.yuv_dump_capture_requested);
        Toast.makeText(this, R.string.yuv_dump_capture_requested, Toast.LENGTH_SHORT).show();
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, "0");
            captureHandler.postDelayed(() -> setContinueFromActivity("1"), 150);
            captureHandler.postDelayed(() -> setContinueFromActivity("0"), 1150);
            captureHandler.postDelayed(this::finishActivityCapture, CAPTURE_SETTLE_DELAY_MS);
        } catch (RuntimeException exception) {
            handleActivityCaptureError(exception);
        }
    }

    private void setContinueFromActivity(String value) {
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, value);
        } catch (RuntimeException exception) {
            handleActivityCaptureError(exception);
        }
    }

    private void finishActivityCapture() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        captureDumpButton.setEnabled(true);
        captureDumpButton.setText(R.string.yuv_dump_capture);
        dumpFileStatus.setText(R.string.yuv_dump_capture_refreshing);
        refreshDumpFiles();
    }

    private void handleActivityCaptureError(RuntimeException exception) {
        captureHandler.removeCallbacksAndMessages(null);
        try {
            SystemPropertyUtils.set(PROP_CONTINUE, "0");
        } catch (RuntimeException ignored) {
            // Keep the original error visible if the property service is unavailable.
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        captureDumpButton.setEnabled(true);
        captureDumpButton.setText(R.string.yuv_dump_capture);
        String message = exception.getMessage();
        if (message == null || message.length() == 0) {
            message = exception.getClass().getSimpleName();
        }
        dumpFileStatus.setText(getString(R.string.yuv_dump_capture_error, message));
        Toast.makeText(this, getString(R.string.yuv_dump_capture_error, message),
                Toast.LENGTH_LONG).show();
    }

    private void refreshDumpFiles() {
        final int operationToken = ++dumpOperationToken;
        refreshDumpButton.setEnabled(false);
        decodeDumpButton.setEnabled(false);
        dumpFileStatus.setText(R.string.yuv_dump_files_loading);
        dumpExecutor.execute(() -> {
            try {
                List<YuvDumpParser.DumpFile> dumps = YuvDumpParser.listYv12Dumps(
                        DUMP_DIRECTORY, MAX_DUMP_FILES);
                runOnUiThread(() -> showDumpFiles(operationToken, dumps, null));
            } catch (Exception exception) {
                String message = exception.getMessage();
                if (message == null || message.length() == 0) {
                    message = exception.getClass().getSimpleName();
                }
                final String errorMessage = message;
                runOnUiThread(() -> showDumpFiles(operationToken, null, errorMessage));
            }
        });
    }

    private void showDumpFiles(int operationToken, List<YuvDumpParser.DumpFile> dumps,
            String errorMessage) {
        if (operationToken != dumpOperationToken || isFinishing() || isDestroyed()) {
            return;
        }
        refreshDumpButton.setEnabled(true);
        dumpFiles = dumps == null ? Collections.emptyList() : dumps;
        dumpFileAdapter.clear();
        for (YuvDumpParser.DumpFile dump : dumpFiles) {
            dumpFileAdapter.add(dump.getDisplayName());
        }
        dumpFileAdapter.notifyDataSetChanged();

        if (errorMessage != null) {
            dumpFileStatus.setText(getString(R.string.yuv_dump_files_error, errorMessage));
            clearPreview();
            return;
        }
        if (dumpFiles.isEmpty()) {
            decodeDumpButton.setEnabled(false);
            dumpFileStatus.setText(R.string.yuv_dump_files_empty);
            clearPreview();
            return;
        }

        dumpFileSpinner.setSelection(0);
        decodeDumpButton.setEnabled(true);
        dumpFileStatus.setText(getString(R.string.yuv_dump_files_found, dumpFiles.size()));
        decodeSelectedDump();
    }

    private void decodeSelectedDump() {
        int position = dumpFileSpinner.getSelectedItemPosition();
        if (position < 0 || position >= dumpFiles.size()) {
            dumpFileStatus.setText(R.string.yuv_dump_file_not_selected);
            return;
        }

        final YuvDumpParser.DumpFile dump = dumpFiles.get(position);
        final int operationToken = ++dumpOperationToken;
        decodeDumpButton.setEnabled(false);
        clearPreview();
        dumpFileStatus.setText(getString(R.string.yuv_dump_decoding, dump.getDisplayName()));
        dumpExecutor.execute(() -> {
            try {
                Bitmap bitmap = YuvDumpParser.decodeToBitmap(dump, PREVIEW_MAX_WIDTH);
                runOnUiThread(() -> showDecodedDump(operationToken, dump, bitmap, null));
            } catch (Exception exception) {
                String message = exception.getMessage();
                if (message == null || message.length() == 0) {
                    message = exception.getClass().getSimpleName();
                }
                final String errorMessage = message;
                runOnUiThread(() -> showDecodedDump(operationToken, dump, null, errorMessage));
            }
        });
    }

    private void showDecodedDump(int operationToken, YuvDumpParser.DumpFile dump,
            Bitmap bitmap, String errorMessage) {
        if (operationToken != dumpOperationToken || isFinishing() || isDestroyed()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return;
        }
        decodeDumpButton.setEnabled(true);
        if (errorMessage != null) {
            dumpFileStatus.setText(getString(R.string.yuv_dump_decode_error, errorMessage));
            return;
        }

        Bitmap previousBitmap = previewBitmap;
        previewBitmap = bitmap;
        dumpPreview.setImageBitmap(bitmap);
        if (previousBitmap != null && previousBitmap != bitmap && !previousBitmap.isRecycled()) {
            previousBitmap.recycle();
        }
        dumpPreview.setContentDescription(dump.getDisplayName());
        dumpFileStatus.setText(dump.getSummary() + "\n" + getString(R.string.yuv_dump_decode_ready));
    }

    private void clearPreview() {
        if (dumpPreview != null) {
            dumpPreview.setImageDrawable(null);
        }
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
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
