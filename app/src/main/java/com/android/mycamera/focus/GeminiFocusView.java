package com.android.mycamera.focus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

public class GeminiFocusView extends View {

    private Paint paint;
    private float cx, cy;
    private float currentRadius;
    private boolean isFocusing;
    private ValueAnimator animator;
    private Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable = () -> {
        isFocusing = false;
        invalidate();
    };

    private static final int BASE_RADIUS = 26;
    private static final int HIDE_TIMEOUT = 1000; // 3 seconds

    public GeminiFocusView(Context context) {
        super(context);
        init();
    }

    public GeminiFocusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GeminiFocusView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.WHITE);
        isFocusing = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isFocusing) {
            canvas.drawCircle(cx, cy, currentRadius, paint);
        }
    }

    public void showFocusRing(float x, float y) {
        this.cx = x;
        this.cy = y;
        isFocusing = true;

        // Cancel any previous hide timer
        hideHandler.removeCallbacks(hideRunnable);

        if (animator != null) {
            animator.cancel();
        }
        
        animator = ValueAnimator.ofFloat(BASE_RADIUS * 1.2f, BASE_RADIUS);
        animator.setDuration(300);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            currentRadius = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
        
        // Start a timer to hide the view
        hideHandler.postDelayed(hideRunnable, HIDE_TIMEOUT);
        
        invalidate();
    }
}
