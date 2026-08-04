package com.android.mycamera.ui.activity;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.mycamera.R;
import com.android.mycamera.utils.YuvDumpParser;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YuvDumpFilesActivity extends AppCompatActivity {
    private static final File DUMP_DIRECTORY = new File("/data/vendor/p2_dump");
    private static final int MAX_DUMP_FILES = 12;
    private static final int PREVIEW_MAX_WIDTH = 1280;

    private Button refreshDumpButton;
    private LinearLayout dumpFileList;
    private TextView dumpFileStatus;
    private TextView dumpFileDetails;
    private ImageView dumpPreview;
    private List<YuvDumpParser.DumpFile> dumpFiles = Collections.emptyList();
    private Bitmap previewBitmap;
    private int selectedDumpPosition = -1;
    private final ExecutorService dumpExecutor = Executors.newSingleThreadExecutor();
    private int dumpOperationToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yuv_dump_files);

        refreshDumpButton = findViewById(R.id.yuvDumpRefreshButton);
        dumpFileList = findViewById(R.id.yuvDumpFileList);
        dumpFileStatus = findViewById(R.id.yuvDumpFileStatus);
        dumpFileDetails = findViewById(R.id.yuvDumpFileDetails);
        dumpPreview = findViewById(R.id.yuvDumpPreview);
        refreshDumpButton.setOnClickListener(view -> refreshDumpFiles());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDumpFiles();
    }

    @Override
    protected void onDestroy() {
        dumpOperationToken++;
        dumpExecutor.shutdownNow();
        clearPreview();
        super.onDestroy();
    }

    private void refreshDumpFiles() {
        final int operationToken = ++dumpOperationToken;
        refreshDumpButton.setEnabled(false);
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
        selectedDumpPosition = -1;
        renderDumpFileList();
        clearPreview();

        if (errorMessage != null) {
            dumpFileStatus.setText(getString(R.string.yuv_dump_files_error, errorMessage));
            return;
        }
        if (dumpFiles.isEmpty()) {
            dumpFileStatus.setText(R.string.yuv_dump_files_empty);
            return;
        }

        dumpFileStatus.setText(getString(R.string.yuv_dump_files_found, dumpFiles.size())
                + "\n" + getString(R.string.yuv_dump_files_select));
    }

    private void renderDumpFileList() {
        dumpFileList.removeAllViews();
        for (int i = 0; i < dumpFiles.size(); i++) {
            final int position = i;
            TextView item = new TextView(this);
            item.setText(dumpFiles.get(i).getDisplayName());
            item.setTextColor(ContextCompat.getColor(this, R.color.white));
            item.setTextSize(12);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setMinHeight(dp(44));
            item.setMaxLines(2);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setBackgroundResource(R.drawable.yuv_dump_file_item_background);
            item.setActivated(position == selectedDumpPosition);
            item.setOnClickListener(view -> {
                selectedDumpPosition = position;
                updateDumpFileSelection();
                decodeSelectedDump();
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(4);
            dumpFileList.addView(item, params);
        }
    }

    private void updateDumpFileSelection() {
        for (int i = 0; i < dumpFileList.getChildCount(); i++) {
            dumpFileList.getChildAt(i).setActivated(i == selectedDumpPosition);
        }
    }

    private void decodeSelectedDump() {
        int position = selectedDumpPosition;
        if (position < 0 || position >= dumpFiles.size()) {
            dumpFileStatus.setText(R.string.yuv_dump_file_not_selected);
            return;
        }

        final YuvDumpParser.DumpFile dump = dumpFiles.get(position);
        final int operationToken = ++dumpOperationToken;
        clearPreview();
        showDumpDetails(dump);
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
        if (errorMessage != null) {
            dumpFileStatus.setText(getString(R.string.yuv_dump_decode_error, errorMessage));
            return;
        }

        Bitmap previousBitmap = previewBitmap;
        previewBitmap = bitmap;
        dumpPreview.setImageBitmap(bitmap);
        dumpPreview.setVisibility(View.VISIBLE);
        showDumpDetails(dump);
        if (previousBitmap != null && previousBitmap != bitmap && !previousBitmap.isRecycled()) {
            previousBitmap.recycle();
        }
        dumpPreview.setContentDescription(dump.getDisplayName());
        dumpFileStatus.setText(R.string.yuv_dump_decode_ready);
    }

    private void showDumpDetails(YuvDumpParser.DumpFile dump) {
        String modifiedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(dump.getLastModified()));
        String sizeCheck = dump.getSize() == dump.getExpectedSize()
                ? getString(R.string.yuv_dump_size_check_ok)
                : getString(R.string.yuv_dump_size_check_mismatch);
        StringBuilder details = new StringBuilder();
        details.append(getString(R.string.yuv_dump_detail_file, dump.getDisplayName())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_modified_time, modifiedTime)).append('\n');
        details.append(getString(R.string.yuv_dump_detail_stream, dump.getStream())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_logical_size,
                dump.getWidth(), dump.getHeight())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_physical_size,
                dump.getPhysicalWidth(), dump.getPhysicalHeight())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_pixel_format,
                dump.getPixelFormat())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_stride,
                dump.getStride(), dump.getChromaStride())).append('\n');
        details.append(getString(R.string.yuv_dump_detail_file_size,
                dump.getSize(), dump.getExpectedSize(), sizeCheck)).append('\n');
        details.append(getString(R.string.yuv_dump_detail_preview_conversion)).append('\n');
        details.append(getString(R.string.yuv_dump_detail_preview_bitmap)).append('\n');
        details.append(getString(R.string.yuv_dump_detail_source,
                dump.getFile().getAbsolutePath()));
        dumpFileDetails.setText(details.toString());
        dumpFileDetails.setVisibility(View.VISIBLE);
    }

    private void clearPreview() {
        if (dumpPreview != null) {
            dumpPreview.setImageDrawable(null);
            dumpPreview.setVisibility(View.GONE);
        }
        if (dumpFileDetails != null) {
            dumpFileDetails.setText(null);
            dumpFileDetails.setVisibility(View.GONE);
        }
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
