package com.android.mycamera.focus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class FocusView extends View {

    private Paint paint;
    private RectF focusRect;
    private boolean isFocusing;
    private long focusStartTime;
    private static final int FOCUS_DURATION = 1000; // 1秒
    private static final int FOCUS_RECT_SIZE = 100; // 聚焦框大小

    public FocusView(Context context) {
        super(context);
        init();
    }

    public FocusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FocusView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        
        focusRect = new RectF();
        isFocusing = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (isFocusing) {
            // 绘制聚焦框
            canvas.drawRect(focusRect, paint);
            
            // 绘制四个角的小线条
            float cornerLength = 30;
            float cornerWidth = 3;
            
            // 左上角
            canvas.drawLine(focusRect.left, focusRect.top, focusRect.left + cornerLength, focusRect.top, paint);
            canvas.drawLine(focusRect.left, focusRect.top, focusRect.left, focusRect.top + cornerLength, paint);
            
            // 右上角
            canvas.drawLine(focusRect.right - cornerLength, focusRect.top, focusRect.right, focusRect.top, paint);
            canvas.drawLine(focusRect.right, focusRect.top, focusRect.right, focusRect.top + cornerLength, paint);
            
            // 左下角
            canvas.drawLine(focusRect.left, focusRect.bottom - cornerLength, focusRect.left, focusRect.bottom, paint);
            canvas.drawLine(focusRect.left, focusRect.bottom, focusRect.left + cornerLength, focusRect.bottom, paint);
            
            // 右下角
            canvas.drawLine(focusRect.right - cornerLength, focusRect.bottom, focusRect.right, focusRect.bottom, paint);
            canvas.drawLine(focusRect.right, focusRect.bottom - cornerLength, focusRect.right, focusRect.bottom, paint);
            
            // 检查是否需要隐藏聚焦框
            if (System.currentTimeMillis() - focusStartTime > FOCUS_DURATION) {
                isFocusing = false;
                invalidate();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // 在点击位置显示聚焦框
            float x = event.getX();
            float y = event.getY();
            
            focusRect.set(
                x - FOCUS_RECT_SIZE / 2,
                y - FOCUS_RECT_SIZE / 2,
                x + FOCUS_RECT_SIZE / 2,
                y + FOCUS_RECT_SIZE / 2
            );
            
            isFocusing = true;
            focusStartTime = System.currentTimeMillis();
            invalidate();
            
            // 通知监听器进行聚焦
            if (focusListener != null) {
                focusListener.onFocusRequested(x, y);
            }
            
            return true;
        }
        return super.onTouchEvent(event);
    }

    public interface OnFocusListener {
        void onFocusRequested(float x, float y);
    }

    private OnFocusListener focusListener;

    public void setOnFocusListener(OnFocusListener listener) {
        this.focusListener = listener;
    }
}