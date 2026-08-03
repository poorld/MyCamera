package com.android.mycamera.ui.activity;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.mycamera.BaseAct;
import com.android.mycamera.R;
import com.android.mycamera.utils.GravityOrientationHelper;
import com.android.mycamera.utils.MediaOrientationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends BaseAct {

    private static final String TAG = "MediaViewerActivity";
    public static final String EXTRA_MEDIA_PATHS = "media_paths";
    public static final String EXTRA_INITIAL_INDEX = "initial_index";
    private final ArrayList<String> mediaPaths = new ArrayList<>();
    private int currentIndex;
    private ImageView mediaImage;
    private TextureView mediaVideo;
    private TextView mediaPosition;
    private View playbackControls;
    private ImageButton rewindButton;
    private ImageButton playButton;
    private ImageButton forwardButton;
    private SeekBar playbackSeekBar;
    private TextView playbackCurrentTime;
    private TextView playbackDuration;
    private GestureDetector gestureDetector;
    private FrameLayout viewerRoot;
    private Bitmap currentBitmap;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable viewerOrientationRunnable = this::applyViewerOrientation;
    private int loadGeneration;
    private GravityOrientationHelper gravityOrientationHelper;
    private boolean gravityLandscape;
    private float gravityOrientationDegrees;
    private MediaPlayer mediaPlayer;
    private Surface mediaSurface;
    private String pendingVideoPath;
    private boolean videoPrepared;
    private int videoWidth;
    private int videoHeight;
    private boolean playbackUserSeeking;
    private final Runnable playbackUpdateRunnable = this::updatePlaybackUi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);
        gravityOrientationHelper = new GravityOrientationHelper(this, (landscape, degrees) -> {
            gravityLandscape = landscape;
            gravityOrientationDegrees = degrees;
            mainHandler.postDelayed(viewerOrientationRunnable, 400);
        });
        mediaImage = findViewById(R.id.mediaImage);
        mediaVideo = findViewById(R.id.mediaVideo);
        mediaPosition = findViewById(R.id.mediaPosition);
        playbackControls = findViewById(R.id.playbackControls);
        rewindButton = findViewById(R.id.rewindButton);
        playButton = findViewById(R.id.playButton);
        forwardButton = findViewById(R.id.forwardButton);
        playbackSeekBar = findViewById(R.id.playbackSeekBar);
        playbackCurrentTime = findViewById(R.id.playbackCurrentTime);
        playbackDuration = findViewById(R.id.playbackDuration);
        rewindButton.setOnClickListener(v -> seekBy(-10_000));
        playButton.setOnClickListener(v -> togglePlayback());
        forwardButton.setOnClickListener(v -> seekBy(10_000));
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && videoPrepared && mediaPlayer != null) {
                    int duration = mediaPlayer.getDuration();
                    playbackCurrentTime.setText(formatPlaybackTime(
                            duration * progress / Math.max(1, seekBar.getMax())));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                playbackUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (videoPrepared && mediaPlayer != null) {
                    mediaPlayer.seekTo(mediaPlayer.getDuration() * seekBar.getProgress()
                            / Math.max(1, seekBar.getMax()));
                }
                playbackUserSeeking = false;
            }
        });
        mediaVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                prepareVideoIfNeeded();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                applyVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releaseVideoPlayer(false);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Playback state is handled by MediaPlayer callbacks.
            }
        });
        mediaVideo.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> applyVideoTransform());
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
    protected void onResume() {
        super.onResume();
        if (gravityOrientationHelper != null) {
            gravityOrientationHelper.start();
        }
    }

    @Override
    protected void onPause() {
        if (gravityOrientationHelper != null) {
            gravityOrientationHelper.stop();
        }
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.post(viewerOrientationRunnable);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(viewerOrientationRunnable);
        mainHandler.removeCallbacks(playbackUpdateRunnable);
        releaseVideoPlayer();
        super.onDestroy();
        loadGeneration++;
        recycleCurrentBitmap();
        decodeExecutor.shutdownNow();
    }

    /**
     * Some device builds report a landscape request but keep the physical
     * display at ROTATION_0. Rotate the complete viewer only in that case;
     * normal displays use the system orientation and stay untransformed.
     */
    private void applyViewerOrientation() {
        if (viewerRoot == null || isFinishing() || isDestroyed()) {
            return;
        }
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean displayLandscape = displayRotation == android.view.Surface.ROTATION_90
                || displayRotation == android.view.Surface.ROTATION_270;
        if (!gravityLandscape || displayLandscape) {
            resetViewerLayout();
            return;
        }

        if (!(viewerRoot.getParent() instanceof View)) {
            return;
        }
        View parent = (View) viewerRoot.getParent();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (parentWidth <= 0 || parentHeight <= 0) {
            mainHandler.postDelayed(viewerOrientationRunnable, 200);
            return;
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                parentHeight, parentWidth, Gravity.CENTER);
        viewerRoot.setLayoutParams(params);
        viewerRoot.setPivotX(parentHeight / 2f);
        viewerRoot.setPivotY(parentWidth / 2f);
        viewerRoot.setRotation(gravityOrientationDegrees < 180f ? -90f : 90f);
    }

    private void resetViewerLayout() {
        viewerRoot.setRotation(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        viewerRoot.setLayoutParams(params);
    }

    private void showCurrentMedia() {
        String path = mediaPaths.get(currentIndex);
        File mediaFile = new File(path);
        mediaPosition.setText((currentIndex + 1) + " / " + mediaPaths.size());
        releaseVideoPlayer();

        if (path.toLowerCase().endsWith(".mp4")) {
            recycleCurrentBitmap();
            mediaImage.setImageBitmap(null);
            mediaImage.setVisibility(View.GONE);
            mediaVideo.setVisibility(View.VISIBLE);
            playbackControls.setVisibility(View.VISIBLE);
            setPlaybackButton(false);
            playbackSeekBar.setProgress(0);
            playbackCurrentTime.setText(formatPlaybackTime(0));
            playbackDuration.setText(formatPlaybackTime(0));
            mediaVideo.setRotation(0f);
            pendingVideoPath = mediaFile.getAbsolutePath();
            if (mediaVideo.isAvailable()) {
                prepareVideoIfNeeded();
            }
        } else {
            pendingVideoPath = null;
            mediaVideo.setVisibility(View.GONE);
            playbackControls.setVisibility(View.GONE);
            mediaImage.setVisibility(View.VISIBLE);
            loadImageAsync(path);
        }
    }

    private void prepareVideoIfNeeded() {
        if (pendingVideoPath == null || mediaPlayer != null || !mediaVideo.isAvailable()) {
            return;
        }

        playbackControls.setVisibility(View.VISIBLE);
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        videoPrepared = false;
        videoWidth = 0;
        videoHeight = 0;
        mediaSurface = new Surface(mediaVideo.getSurfaceTexture());
        player.setOnPreparedListener(preparedPlayer -> {
            if (mediaPlayer != preparedPlayer || isFinishing() || isDestroyed()) {
                return;
            }
            videoPrepared = true;
            videoWidth = preparedPlayer.getVideoWidth();
            videoHeight = preparedPlayer.getVideoHeight();
            applyVideoTransform();
            playbackSeekBar.setMax(Math.max(1, preparedPlayer.getDuration()));
            playbackDuration.setText(formatPlaybackTime(preparedPlayer.getDuration()));
            setPlaybackButton(true);
            preparedPlayer.start();
            mainHandler.removeCallbacks(playbackUpdateRunnable);
            mainHandler.post(playbackUpdateRunnable);
        });
        player.setOnVideoSizeChangedListener((changedPlayer, width, height) -> {
            videoWidth = width;
            videoHeight = height;
            applyVideoTransform();
        });
        player.setOnCompletionListener(completedPlayer -> {
            setPlaybackButton(false);
            mainHandler.removeCallbacks(playbackUpdateRunnable);
            updatePlaybackUi();
        });
        player.setOnErrorListener((errorPlayer, what, extra) -> {
            Log.e(TAG, "Video playback failed: what=" + what + ", extra=" + extra
                    + ", path=" + pendingVideoPath);
            return false;
        });

        try {
            player.setDataSource(this, Uri.fromFile(new File(pendingVideoPath)));
            player.setSurface(mediaSurface);
            player.setScreenOnWhilePlaying(true);
            player.prepareAsync();
        } catch (Exception error) {
            Log.e(TAG, "Unable to prepare video: " + pendingVideoPath, error);
            releaseVideoPlayer();
        }
    }

    private void applyVideoTransform() {
        if (mediaVideo == null || videoWidth <= 0 || videoHeight <= 0
                || mediaVideo.getWidth() <= 0 || mediaVideo.getHeight() <= 0) {
            return;
        }
        float viewWidth = mediaVideo.getWidth();
        float viewHeight = mediaVideo.getHeight();
        float scale = Math.min(viewWidth / videoWidth, viewHeight / videoHeight);
        float scaledWidth = videoWidth * scale;
        float scaledHeight = videoHeight * scale;
        Matrix transform = new Matrix();
        transform.setScale(scaledWidth / viewWidth, scaledHeight / viewHeight,
                viewWidth / 2f, viewHeight / 2f);
        mediaVideo.setTransform(transform);
    }

    private void releaseVideoPlayer() {
        releaseVideoPlayer(true);
    }

    private void releaseVideoPlayer(boolean clearPendingPath) {
        if (clearPendingPath) {
            pendingVideoPath = null;
        }
        mainHandler.removeCallbacks(playbackUpdateRunnable);
        videoPrepared = false;
        videoWidth = 0;
        videoHeight = 0;
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) {
            player.release();
        }
        if (mediaSurface != null) {
            mediaSurface.release();
            mediaSurface = null;
        }
        if (mediaVideo != null) {
            mediaVideo.setTransform(new Matrix());
        }
        if (playbackControls != null) {
            playbackControls.setVisibility(View.GONE);
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || !videoPrepared) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            setPlaybackButton(false);
            mainHandler.removeCallbacks(playbackUpdateRunnable);
        } else {
            mediaPlayer.start();
            setPlaybackButton(true);
            mainHandler.post(playbackUpdateRunnable);
        }
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || !videoPrepared) return;
        int duration = mediaPlayer.getDuration();
        int position = Math.max(0, Math.min(duration, mediaPlayer.getCurrentPosition() + deltaMs));
        mediaPlayer.seekTo(position);
        updatePlaybackUi();
    }

    private void updatePlaybackUi() {
        if (mediaPlayer == null || !videoPrepared) return;
        int duration = mediaPlayer.getDuration();
        int position = mediaPlayer.getCurrentPosition();
        if (!playbackUserSeeking) {
            playbackSeekBar.setMax(Math.max(1, duration));
            playbackSeekBar.setProgress(Math.min(position, playbackSeekBar.getMax()));
            playbackCurrentTime.setText(formatPlaybackTime(position));
        }
        playbackDuration.setText(formatPlaybackTime(duration));
        if (mediaPlayer.isPlaying()) {
            mainHandler.postDelayed(playbackUpdateRunnable, 250);
        }
    }

    private void setPlaybackButton(boolean playing) {
        if (playButton == null) return;
        playButton.setImageResource(playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        playButton.setContentDescription(playing ? "Pause" : "Play");
    }

    private String formatPlaybackTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
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
