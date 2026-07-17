package com.android.mycamera.ui.activity;

import android.net.Uri;
import android.os.Bundle;
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

    private void showCurrentMedia() {
        String path = mediaPaths.get(currentIndex);
        File mediaFile = new File(path);
        mediaPosition.setText((currentIndex + 1) + " / " + mediaPaths.size());
        mediaVideo.stopPlayback();

        if (path.toLowerCase().endsWith(".mp4")) {
            mediaImage.setVisibility(View.GONE);
            mediaVideo.setVisibility(View.VISIBLE);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(mediaVideo);
            mediaVideo.setMediaController(controller);
            mediaVideo.setVideoURI(Uri.fromFile(mediaFile));
            mediaVideo.setOnPreparedListener(player -> {
                int rotation = MediaOrientationUtils.getVideoRotation(path);
                layoutVideo(player.getVideoWidth(), player.getVideoHeight(), rotation);
                mediaVideo.start();
            });
        } else {
            mediaVideo.setVisibility(View.GONE);
            mediaImage.setVisibility(View.VISIBLE);
            mediaImage.setImageBitmap(MediaOrientationUtils.decodeOrientedImage(path, null));
        }
    }

    private void layoutVideo(int sourceWidth, int sourceHeight, int rotation) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        boolean rotated = rotation == 90 || rotation == 270;
        int displayWidth = rotated ? sourceHeight : sourceWidth;
        int displayHeight = rotated ? sourceWidth : sourceHeight;
        float scale = Math.min((float) viewerRoot.getWidth() / displayWidth,
                (float) viewerRoot.getHeight() / displayHeight);
        int renderedWidth = Math.round(displayWidth * scale);
        int renderedHeight = Math.round(displayHeight * scale);

        FrameLayout.LayoutParams params;
        if (rotated) {
            params = new FrameLayout.LayoutParams(renderedHeight, renderedWidth, Gravity.CENTER);
        } else {
            params = new FrameLayout.LayoutParams(renderedWidth, renderedHeight, Gravity.CENTER);
        }
        mediaVideo.setLayoutParams(params);
        mediaVideo.setRotation(rotation);
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
            if (Math.abs(horizontalDistance) < 100 || Math.abs(horizontalDistance) < Math.abs(end.getY() - start.getY())) {
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
