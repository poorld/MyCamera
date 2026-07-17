package com.android.mycamera.ui.activity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.utils.CameraUtils;
import com.android.mycamera.utils.MediaOrientationUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shows only media captured by this app from its app-specific external directory. */
public class GalleryActivity extends BaseAct {

    private GridLayout mediaGrid;
    private TextView emptyView;
    private TextView galleryTitle;
    private final Set<File> selectedFiles = new HashSet<>();
    private final Map<File, FrameLayout> mediaItems = new HashMap<>();
    private final ArrayList<File> displayedMediaFiles = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        mediaGrid = findViewById(R.id.mediaGrid);
        emptyView = findViewById(R.id.emptyView);
        galleryTitle = findViewById(R.id.galleryTitle);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.moreButton).setOnClickListener(this::showMoreMenu);
        updateSelectionUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMedia();
    }

    private void loadMedia() {
        File mediaDirectory = CameraUtils.createCameraDirectory(this);
        File[] mediaFiles = mediaDirectory.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lowerName = name.toLowerCase();
                return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                        || lowerName.endsWith(".png") || lowerName.endsWith(".mp4");
            }
        });

        mediaGrid.removeAllViews();
        mediaItems.clear();
        displayedMediaFiles.clear();
        if (mediaFiles == null || mediaFiles.length == 0) {
            selectedFiles.clear();
            emptyView.setVisibility(View.VISIBLE);
            updateSelectionUi();
            return;
        }

        selectedFiles.retainAll(Arrays.asList(mediaFiles));
        Arrays.sort(mediaFiles, Comparator.comparingLong(File::lastModified).reversed());
        displayedMediaFiles.addAll(Arrays.asList(mediaFiles));
        emptyView.setVisibility(View.GONE);
        int itemSize = (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;
        for (File mediaFile : mediaFiles) {
            addMediaItem(mediaFile, itemSize);
        }
    }

    private void addMediaItem(File mediaFile, int itemSize) {
        FrameLayout item = new FrameLayout(this);
        GridLayout.LayoutParams itemParams = new GridLayout.LayoutParams();
        itemParams.width = itemSize;
        itemParams.height = itemSize;
        itemParams.setMargins(dp(1), dp(1), dp(1), dp(1));
        item.setLayoutParams(itemParams);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setImageBitmap(createThumbnail(mediaFile));
        item.addView(thumbnail);

        View selectionOverlay = new View(this);
        selectionOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        selectionOverlay.setBackgroundColor(0x99000000);
        selectionOverlay.setVisibility(selectedFiles.contains(mediaFile) ? View.VISIBLE : View.GONE);
        item.addView(selectionOverlay);

        ImageView checkMark = new ImageView(this);
        FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        checkMark.setLayoutParams(checkParams);
        checkMark.setImageResource(R.drawable.ic_check);
        checkMark.setVisibility(selectedFiles.contains(mediaFile) ? View.VISIBLE : View.GONE);
        item.addView(checkMark);

        if (isVideo(mediaFile)) {
            TextView videoLabel = new TextView(this);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            labelParams.setMargins(dp(6), dp(4), dp(6), dp(6));
            videoLabel.setLayoutParams(labelParams);
            videoLabel.setText("VIDEO");
            videoLabel.setTextColor(getColor(android.R.color.white));
            videoLabel.setTextSize(11);
            item.addView(videoLabel);
        }

        item.setContentDescription(mediaFile.getName());
        item.setOnClickListener(v -> {
            if (selectedFiles.isEmpty()) {
                openMedia(mediaFile);
            } else {
                toggleSelection(mediaFile);
            }
        });
        item.setOnLongClickListener(v -> {
            toggleSelection(mediaFile);
            return true;
        });
        mediaItems.put(mediaFile, item);
        mediaGrid.addView(item);
    }

    private Bitmap createThumbnail(File mediaFile) {
        if (!isVideo(mediaFile)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            return MediaOrientationUtils.decodeOrientedImage(mediaFile.getAbsolutePath(), options);
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(mediaFile.getAbsolutePath());
            return retriever.getFrameAtTime(0);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (java.io.IOException ignored) {
                // The thumbnail has already been read, so a release failure is non-fatal.
            }
        }
    }

    private void openMedia(File mediaFile) {
        ArrayList<String> mediaPaths = new ArrayList<>();
        for (File file : displayedMediaFiles) {
            mediaPaths.add(file.getAbsolutePath());
        }
        Intent intent = new Intent(this, MediaViewerActivity.class);
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_MEDIA_PATHS, mediaPaths);
        intent.putExtra(MediaViewerActivity.EXTRA_INITIAL_INDEX, displayedMediaFiles.indexOf(mediaFile));
        startActivity(intent);
    }

    private boolean isVideo(File mediaFile) {
        return mediaFile.getName().toLowerCase().endsWith(".mp4");
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "全选");
        menu.getMenu().add(0, 2, 1, "取消选择");
        MenuItem deleteItem = menu.getMenu().add(0, 3, 2, "删除");
        deleteItem.setEnabled(!selectedFiles.isEmpty());
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                selectedFiles.addAll(mediaItems.keySet());
                updateSelectionUi();
                return true;
            }
            if (item.getItemId() == 2) {
                selectedFiles.clear();
                updateSelectionUi();
                return true;
            }
            if (item.getItemId() == 3) {
                confirmDelete();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void toggleSelection(File mediaFile) {
        if (!selectedFiles.add(mediaFile)) {
            selectedFiles.remove(mediaFile);
        }
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        int count = selectedFiles.size();
        galleryTitle.setText(count == 0 ? "相册" : "已选择 " + count + " 项");
        for (Map.Entry<File, FrameLayout> entry : mediaItems.entrySet()) {
            boolean selected = selectedFiles.contains(entry.getKey());
            FrameLayout item = entry.getValue();
            item.getChildAt(1).setVisibility(selected ? View.VISIBLE : View.GONE);
            item.getChildAt(2).setVisibility(selected ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmDelete() {
        int count = selectedFiles.size();
        if (count == 0) return;
        new AlertDialog.Builder(this)
                .setMessage("确定删除已选择的 " + count + " 项吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteSelectedMedia())
                .show();
    }

    private void deleteSelectedMedia() {
        int deletedCount = 0;
        for (File mediaFile : new HashSet<>(selectedFiles)) {
            if (mediaFile.delete()) {
                deletedCount++;
            }
        }
        selectedFiles.clear();
        loadMedia();
        if (deletedCount == 0) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已删除 " + deletedCount + " 项", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (!selectedFiles.isEmpty()) {
            selectedFiles.clear();
            updateSelectionUi();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
