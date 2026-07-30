package com.android.mycamera.ui.activity;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.utils.MediaOrientationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends BaseAct {

    public static final String EXTRA_MEDIA_PATHS = "media_paths";
    public static final String EXTRA_INITIAL_INDEX = "initial_index";
    private final ArrayList<String> mediaPaths = new ArrayList<>();
    private int currentIndex;
    private ImageView mediaImage;
    private VideoView mediaVideo;
    private TextView mediaPosition;
    private GestureDetector gestureDetector;
    private FrameLayout viewerRoot;
    private Bitmap currentBitmap;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int loadGeneration;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);
        mediaImage = findViewById(R.id.mediaImage);
        mediaVideo = findViewById(R.id.mediaVideo);
        mediaPosition = findViewById(R.id.mediaPosition);
        findViewById(R.id.viewerBackButton).setOnClickListener(v -> finish());

        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_MEDIA_PATHS);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        mediaPaths.addAll(paths);
        currentIndex = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INITIAL_INDEX, 0), mediaPaths.size() - 1));
        gestureDetector = new GestureDetector(this, new SwipeListener());
        viewerRoot = findViewById(R.id.viewerRoot);
        viewerRoot.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        showCurrentMedia();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadGeneration++;
        recycleCurrentBitmap();
        decodeExecutor.shutdownNow();
    }

    private void showCurrentMedia() {
        String path = mediaPaths.get(currentIndex);
        File mediaFile = new File(path);
        mediaPosition.setText((currentIndex + 1) + " / " + mediaPaths.size());
        mediaVideo.stopPlayback();

        if (path.toLowerCase().endsWith(".mp4")) {
            recycleCurrentBitmap();
            mediaImage.setImageBitmap(null);
            mediaImage.setVisibility(View.GONE);
            mediaVideo.setVisibility(View.VISIBLE);
            mediaVideo.setRotation(0f);
            FrameLayout.LayoutParams videoLayoutParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            videoLayoutParams.gravity = Gravity.CENTER;
            mediaVideo.setLayoutParams(videoLayoutParams);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(mediaVideo);
            mediaVideo.setMediaController(controller);
            mediaVideo.setOnPreparedListener(player -> mediaVideo.start());
            mediaVideo.setVideoURI(Uri.fromFile(mediaFile));
        } else {
            mediaVideo.setVisibility(View.GONE);
            mediaImage.setVisibility(View.VISIBLE);
            loadImageAsync(path);
        }
    }

    private void loadImageAsync(String path) {
        final int generation = ++loadGeneration;
        mediaImage.setImageBitmap(null);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int maxSide = Math.max(metrics.widthPixels, metrics.heightPixels) * 2;
        decodeExecutor.execute(() -> {
            Bitmap bitmap = MediaOrientationUtils.decodeOrientedImageForDisplay(path, maxSide);
            mainHandler.post(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                recycleCurrentBitmap();
                currentBitmap = bitmap;
                mediaImage.setImageBitmap(bitmap);
            });
        });
    }

    private void recycleCurrentBitmap() {
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = null;
    }

    private void showNextMedia() {
        if (currentIndex < mediaPaths.size() - 1) {
            currentIndex++;
            showCurrentMedia();
        }
    }

    private void showPreviousMedia() {
        if (currentIndex > 0) {
            currentIndex--;
            showCurrentMedia();
        }
    }

    private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
            if (start == null || end == null) return false;
            float horizontalDistance = end.getX() - start.getX();
            if (Math.abs(horizontalDistance) < 100
                    || Math.abs(horizontalDistance) < Math.abs(end.getY() - start.getY())) {
                return false;
            }
            if (horizontalDistance < 0) {
                showNextMedia();
            } else {
                showPreviousMedia();
            }
            return true;
        }
    }
}
