package com.android.mycamera.multicamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the existing multi-camera preview through repeatable open/capture/close cycles. */
public class AutoMultiCameraActivity extends MultiCameraActivity {
    private static final String TAG = "AutoMultiCameraActivity";
    private static final long CLOSE_POLL_MS = 250L;
    private static final long CLOSE_TIMEOUT_MS = 15_000L;
    private static final long PREVIEW_READY_TIMEOUT_MS = 15_000L;
    private static final long SCREENSHOT_BEFORE_STOP_MS = 1_000L;

    private final Handler automationHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EditText cycleCountInput;
    private EditText previewMsInput;
    private EditText stopWaitMsInput;
    private TextView counterView;
    private TextView statusView;
    private Button runButton;
    private Button cancelButton;
    private Button startPreviewButton;
    private Button stopPreviewButton;
    private View previewContainer;
    private ScrollView automationScrollView;
    private File runDirectory;
    private int cycleCount;
    private int currentCycle;
    private int completedCycles;
    private long previewMs;
    private long stopWaitMs;
    private long closeWaitStartedMs;
    private long previewReadyWaitStartedMs;
    private long previewStartedMs;
    private int expectedPreviewCount;
    private Process logcatProcess;
    private final Set<String> previewFramesReceived = new HashSet<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installAutomationControls();
    }

    private void installAutomationControls() {
        ViewGroup activityRoot = findViewById(android.R.id.content);
        View scrollContent = activityRoot == null ? null : activityRoot.getChildAt(0);
        automationScrollView = scrollContent instanceof ScrollView ? (ScrollView) scrollContent : null;
        View content = scrollContent instanceof ScrollView
                ? ((ScrollView) scrollContent).getChildAt(0) : null;
        if (!(content instanceof LinearLayout)) {
            Log.e(TAG, "Cannot add automation controls: unexpected layout");
            return;
        }

        LinearLayout root = (LinearLayout) content;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 16, 0, 16);

        TextView title = new TextView(this);
        title.setText("自动预览循环");
        panel.addView(title);

        cycleCountInput = addInput(panel, "循环次数", "100");
        previewMsInput = addInput(panel, "预览时长（毫秒）", "6000");
        stopWaitMsInput = addInput(panel, "停止后等待（毫秒）", "2000");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        runButton = new Button(this);
        runButton.setText("开始自动运行");
        runButton.setOnClickListener(view -> startAutomation());
        buttons.addView(runButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancelButton = new Button(this);
        cancelButton.setText("停止自动运行");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(view -> finishAutomation("cancelled"));
        buttons.addView(cancelButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(buttons);

        statusView = new TextView(this);
        statusView.setText("未运行");
        panel.addView(statusView);

        counterView = new TextView(this);
        counterView.setText("完成计数：0 / 0");
        counterView.setTextSize(20f);
        panel.addView(counterView);

        root.addView(panel, Math.max(0, root.getChildCount() - 1));
        startPreviewButton = findViewById(com.android.mycamera.R.id.start_preview_button);
        stopPreviewButton = findViewById(com.android.mycamera.R.id.stop_preview_button);
        previewContainer = findViewById(com.android.mycamera.R.id.preview_container);
        removeInheritedRecordingControls();
    }

    private void removeInheritedRecordingControls() {
        removeFromParent(findViewById(com.android.mycamera.R.id.start_record_button));
        removeFromParent(findViewById(com.android.mycamera.R.id.stop_record_button));
    }

    private void removeFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private EditText addInput(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        parent.addView(label);
        EditText input = new EditText(this);
        input.setText(value);
        input.setSingleLine(true);
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && automationScrollView != null) {
                scrollInputAboveKeyboard(input);
                // OEM keyboards often finish their inset animation after focus is delivered.
                automationScrollView.postDelayed(() -> scrollInputAboveKeyboard(input), 300L);
            }
        });
        parent.addView(input);
        return input;
    }

    private void scrollInputAboveKeyboard(EditText input) {
        if (automationScrollView == null || !input.hasFocus()) {
            return;
        }
        int marginPx = (int) (72 * getResources().getDisplayMetrics().density);
        automationScrollView.smoothScrollTo(0, Math.max(0, input.getTop() - marginPx));
    }

    private void startAutomation() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            cycleCount = positiveInt(cycleCountInput, "循环次数");
            previewMs = positiveLong(previewMsInput, "预览时长");
            stopWaitMs = nonNegativeLong(stopWaitMsInput, "停止后等待");
        } catch (RuntimeException exception) {
            running.set(false);
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            return;
        }

        expectedPreviewCount = selectedPreviewCount();
        if (expectedPreviewCount == 0) {
            running.set(false);
            Toast.makeText(this, "请先勾选要循环预览的相机", Toast.LENGTH_SHORT).show();
            return;
        }
        runDirectory = createRunDirectory();
        if (runDirectory == null) {
            running.set(false);
            Toast.makeText(this, "无法创建运行目录", Toast.LENGTH_SHORT).show();
            return;
        }
        appendRunLog("start cycles=" + cycleCount + " previewMs=" + previewMs
                + " stopWaitMs=" + stopWaitMs + " selectedCameras=" + expectedPreviewCount);
        startLogcatCapture();
        currentCycle = 1;
        completedCycles = 0;
        updateCounter();
        runButton.setEnabled(false);
        cancelButton.setEnabled(true);
        setStatus("输出目录：" + runDirectory.getAbsolutePath());
        runNextCycle();
    }

    private int selectedPreviewCount() {
        ViewGroup selection = findViewById(com.android.mycamera.R.id.camera_selection_layout);
        int selectedCount = 0;
        for (int index = 0; selection != null && index < selection.getChildCount(); index++) {
            View child = selection.getChildAt(index);
            if (child instanceof CheckBox) {
                CheckBox box = (CheckBox) child;
                if (box.isChecked()) {
                    selectedCount++;
                }
            }
        }
        return selectedCount;
    }

    private void runNextCycle() {
        if (!running.get()) {
            return;
        }
        if (currentCycle > cycleCount) {
            finishAutomation("completed");
            return;
        }
        if (startPreviewButton == null || !startPreviewButton.isEnabled()) {
            automationHandler.postDelayed(this::runNextCycle, CLOSE_POLL_MS);
            return;
        }
        String label = String.format(Locale.US, "cycle %04d/%04d", currentCycle, cycleCount);
        appendRunLog(label + " start preview");
        setStatus(label + " 预览中");
        previewFramesReceived.clear();
        previewReadyWaitStartedMs = System.currentTimeMillis();
        startPreviewButton.performClick();
        waitForPreviewFrames(currentCycle);
    }

    private void waitForPreviewFrames(int cycle) {
        if (!running.get() || cycle != currentCycle) {
            return;
        }
        if (previewFramesReceived.size() >= expectedPreviewCount) {
            previewStartedMs = System.currentTimeMillis();
            long beforeStopMs = Math.max(0L, previewMs - SCREENSHOT_BEFORE_STOP_MS);
            appendRunLog(String.format(Locale.US, "cycle %04d first frames received", cycle));
            automationHandler.postDelayed(() -> captureThenScheduleStop(cycle), beforeStopMs);
            return;
        }
        if (System.currentTimeMillis() - previewReadyWaitStartedMs >= PREVIEW_READY_TIMEOUT_MS) {
            appendRunLog(String.format(Locale.US, "cycle %04d preview first-frame timeout", cycle));
            finishAutomation("preview first-frame timeout");
            return;
        }
        automationHandler.postDelayed(() -> waitForPreviewFrames(cycle), CLOSE_POLL_MS);
    }

    private void captureThenScheduleStop(int cycle) {
        if (!running.get() || cycle != currentCycle) {
            return;
        }
        setStatus(String.format(Locale.US, "cycle %04d 截图中", cycle));
        captureScreenshot(cycle, () -> {
            if (!running.get() || cycle != currentCycle) {
                return;
            }
            long targetStopMs = previewStartedMs + previewMs;
            long remainingMs = Math.max(0L, targetStopMs - System.currentTimeMillis());
            appendRunLog(String.format(Locale.US,
                    "cycle %04d screenshot complete; stop scheduled in %dms", cycle, remainingMs));
            automationHandler.postDelayed(() -> stopPreviewAfterScreenshot(cycle), remainingMs);
        });
    }

    private void stopPreviewAfterScreenshot(int cycle) {
        if (!running.get() || cycle != currentCycle) {
            return;
        }
        appendRunLog(String.format(Locale.US, "cycle %04d stop preview", cycle));
        stopPreviewButton.performClick();
        closeWaitStartedMs = System.currentTimeMillis();
        waitForClose(cycle);
    }

    private void waitForClose(int cycle) {
        if (!running.get() || cycle != currentCycle) {
            return;
        }
        long waitedMs = System.currentTimeMillis() - closeWaitStartedMs;
        if (startPreviewButton.isEnabled() || waitedMs >= CLOSE_TIMEOUT_MS) {
            if (waitedMs >= CLOSE_TIMEOUT_MS && !startPreviewButton.isEnabled()) {
                appendRunLog(String.format(Locale.US, "cycle %04d close timeout", cycle));
                finishAutomation("camera close timeout");
                return;
            }
            completedCycles++;
            updateCounter();
            currentCycle++;
            automationHandler.postDelayed(this::runNextCycle, stopWaitMs);
            return;
        }
        automationHandler.postDelayed(() -> waitForClose(cycle), CLOSE_POLL_MS);
    }

    private void captureScreenshot(int cycle, Runnable complete) {
        int width = getWindow().getDecorView().getWidth();
        int height = getWindow().getDecorView().getHeight();
        if (width <= 0 || height <= 0) {
            appendRunLog("screenshot skipped: empty window");
            complete.run();
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelCopy.request(getWindow(), bitmap, result -> {
            if (result == PixelCopy.SUCCESS) {
                saveScreenshot(cropToPreviewContainer(bitmap), cycle);
            } else {
                appendRunLog("PixelCopy failed result=" + result + ", using view draw fallback");
                Bitmap fallback = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                getWindow().getDecorView().draw(new Canvas(fallback));
                saveScreenshot(cropToPreviewContainer(fallback), cycle);
            }
            complete.run();
        }, automationHandler);
    }

    /** Returns only the fixed preview container, not the checkboxes or automation controls. */
    private Bitmap cropToPreviewContainer(Bitmap windowBitmap) {
        if (previewContainer == null || previewContainer.getWidth() <= 0 || previewContainer.getHeight() <= 0) {
            appendRunLog("WARN preview container unavailable; saving full window");
            return windowBitmap;
        }
        int[] location = new int[2];
        previewContainer.getLocationInWindow(location);
        int left = Math.max(0, location[0]);
        int top = Math.max(0, location[1]);
        int right = Math.min(windowBitmap.getWidth(), left + previewContainer.getWidth());
        int bottom = Math.min(windowBitmap.getHeight(), top + previewContainer.getHeight());
        if (right <= left || bottom <= top) {
            appendRunLog("WARN preview crop outside window; saving full window");
            return windowBitmap;
        }
        Bitmap previewBitmap = Bitmap.createBitmap(windowBitmap, left, top, right - left, bottom - top);
        windowBitmap.recycle();
        return previewBitmap;
    }

    private void saveScreenshot(Bitmap bitmap, int cycle) {
        File screenshot = new File(runDirectory,
                String.format(Locale.US, "cycle_%04d_before_stop.png", cycle));
        fileExecutor.execute(() -> {
            try (FileOutputStream output = new FileOutputStream(screenshot)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                appendRunLog("screenshot=" + screenshot.getAbsolutePath());
            } catch (IOException exception) {
                Log.e(TAG, "Cannot save screenshot", exception);
                appendRunLog("ERROR screenshot: " + exception.getMessage());
            } finally {
                bitmap.recycle();
            }
        });
    }

    private File createRunDirectory() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File root = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (root == null) {
            return null;
        }
        File directory = new File(root, "preview_cycles_" + timestamp);
        return directory.mkdirs() ? directory : null;
    }

    private void startLogcatCapture() {
        try {
            logcatProcess = new ProcessBuilder("logcat", "-v", "threadtime").start();
            File logFile = new File(runDirectory, "logcat_live.txt");
            fileExecutor.execute(() -> copyLogcat(logcatProcess, logFile));
        } catch (IOException exception) {
            appendRunLog("WARN cannot start logcat: " + exception.getMessage());
        }
    }

    private void copyLogcat(Process process, File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             FileWriter writer = new FileWriter(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException exception) {
            Log.w(TAG, "Logcat capture ended", exception);
        }
    }

    private void finishAutomation(String reason) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        automationHandler.removeCallbacksAndMessages(null);
        if (stopPreviewButton != null && stopPreviewButton.isEnabled()) {
            stopPreviewButton.performClick();
        }
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        appendRunLog("finish reason=" + reason);
        runButton.setEnabled(true);
        cancelButton.setEnabled(false);
        setStatus("结束：" + reason + "\n" + runDirectory.getAbsolutePath());
        Toast.makeText(this, "自动运行结束，文件已保存", Toast.LENGTH_LONG).show();
    }

    private void appendRunLog(String message) {
        Log.i(TAG, message);
        File directory = runDirectory;
        if (directory == null) {
            return;
        }
        fileExecutor.execute(() -> {
            File eventLog = new File(directory, "run_events.txt");
            try (FileWriter writer = new FileWriter(eventLog, true)) {
                writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + " " + message + '\n');
            } catch (IOException exception) {
                Log.e(TAG, "Cannot append run log", exception);
            }
        });
    }

    private int positiveInt(EditText input, String field) {
        int value = Integer.parseInt(input.getText().toString().trim());
        if (value <= 0) throw new IllegalArgumentException(field + "必须大于 0");
        return value;
    }

    private long positiveLong(EditText input, String field) {
        long value = Long.parseLong(input.getText().toString().trim());
        if (value <= 0) throw new IllegalArgumentException(field + "必须大于 0");
        return value;
    }

    private long nonNegativeLong(EditText input, String field) {
        long value = Long.parseLong(input.getText().toString().trim());
        if (value < 0) throw new IllegalArgumentException(field + "不能小于 0");
        return value;
    }

    private void setStatus(String value) {
        if (statusView != null) statusView.setText(value);
    }

    private void updateCounter() {
        if (counterView != null) {
            counterView.setText(String.format(Locale.US, "完成计数：%d / %d", completedCycles, cycleCount));
        }
    }

    @Override
    protected void onCameraPreviewFrameUpdated(String cameraId) {
        if (running.get()) {
            previewFramesReceived.add(cameraId);
        }
    }

    @Override
    protected void onDestroy() {
        finishAutomation("activity destroyed");
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
