package com.android.mycamera.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ZoomDialView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float minZoom = 1f;
    private float maxZoom = 10f;
    private float zoom = 1f;

    public ZoomDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setZoom(float minZoom, float maxZoom, float zoom) {
        this.minZoom = minZoom;
        this.maxZoom = Math.max(minZoom, maxZoom);
        this.zoom = Math.max(minZoom, Math.min(zoom, maxZoom));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2f;
        float radius = width * 0.55f;
        float centerY = height + radius * 0.25f;
        RectF arc = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        linePaint.setColor(0xAAFFFFFF);
        linePaint.setStrokeWidth(2f);
        canvas.drawArc(arc, 205f, 130f, false, linePaint);

        float progress = (zoom - minZoom) / Math.max(0.01f, maxZoom - minZoom);
        for (int i = 0; i <= 30; i++) {
            float angle = 205f + i * (130f / 30f);
            double radians = Math.toRadians(angle);
            float tickLength = i % 5 == 0 ? 14f : 7f;
            float outerX = centerX + (float) Math.cos(radians) * radius;
            float outerY = centerY + (float) Math.sin(radians) * radius;
            float innerX = centerX + (float) Math.cos(radians) * (radius - tickLength);
            float innerY = centerY + (float) Math.sin(radians) * (radius - tickLength);
            linePaint.setColor(i / 30f <= progress ? 0xFFFFD400 : 0x99FFFFFF);
            linePaint.setStrokeWidth(i % 5 == 0 ? 2.5f : 1.2f);
            canvas.drawLine(innerX, innerY, outerX, outerY, linePaint);
        }

        float indicatorAngle = 205f + progress * 130f;
        double indicatorRadians = Math.toRadians(indicatorAngle);
        float indicatorX = centerX + (float) Math.cos(indicatorRadians) * (radius - 18f);
        float indicatorY = centerY + (float) Math.sin(indicatorRadians) * (radius - 18f);
        linePaint.setColor(0xFFFFD400);
        linePaint.setStrokeWidth(5f);
        canvas.drawCircle(indicatorX, indicatorY, 4f, linePaint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        for (int i = 0; i < 4; i++) {
            float value = minZoom + (maxZoom - minZoom) * i / 3f;
            float labelAngle = 205f + i * (130f / 3f);
            double radians = Math.toRadians(labelAngle);
            float x = centerX + (float) Math.cos(radians) * (radius - 32f);
            float y = centerY + (float) Math.sin(radians) * (radius - 32f) + 5f;
            canvas.drawText(format(value), x, y, textPaint);
        }
        textPaint.setColor(0xFFFFD400);
        textPaint.setTextSize(22f * getResources().getDisplayMetrics().scaledDensity);
        float zoomTextY = centerY - radius * 0.60f;
        canvas.drawText(format(zoom), centerX, zoomTextY, textPaint);
    }

    private String format(float value) {
        return value < 10f ? String.format(java.util.Locale.US, "%.1fx", value) : String.format(java.util.Locale.US, "%.0fx", value);
    }
}
