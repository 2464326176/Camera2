package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Minimalist vertical EV slider. Touch is precise (no rotation hack),
 * thumb tracks the finger from bottom (min) to top (max).
 */
public class EvSliderView extends View {

    public interface OnEvChangeListener {
        void onEvChanged(int value);
    }

    private int trackColor = Color.parseColor("#55FFFFFF");
    private int accentColor = Color.parseColor("#4285F4");
    private int min = -12;
    private int max = 12;
    private int progress = 0;
    private float thumbRadius = 9f;

    private final Paint trackPaint;
    private final Paint thumbPaint;
    private OnEvChangeListener listener;

    public EvSliderView(Context context) {
        this(context, null);
    }

    public EvSliderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EvSliderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStrokeWidth(2f);
        trackPaint.setColor(trackColor);
        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(accentColor);
    }

    public void setRange(int min, int max) {
        if (max <= min) return;
        this.min = min;
        this.max = max;
        progress = Math.max(min, Math.min(progress, max));
        invalidate();
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        thumbPaint.setColor(color);
        invalidate();
    }

    public void setProgress(int p) {
        this.progress = Math.max(min, Math.min(p, max));
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setOnEvChangeListener(OnEvChangeListener l) {
        this.listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int pad = (int) (thumbRadius + 8);
        float top = pad;
        float bottom = h - pad;
        float cx = w / 2f;

        float frac = (progress - min) / (float) (max - min);
        float ty = bottom - frac * (bottom - top);

        canvas.drawLine(cx, top, cx, bottom, trackPaint);
        canvas.drawCircle(cx, ty, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int h = getHeight();
        int pad = (int) (thumbRadius + 8);
        float top = pad;
        float bottom = h - pad;
        float y = Math.max(top, Math.min(event.getY(), bottom));

        float frac = (bottom - y) / (bottom - top);
        int value = Math.round(min + frac * (max - min));
        value = Math.max(min, Math.min(value, max));

        if (value != progress) {
            progress = value;
            invalidate();
            if (listener != null) {
                listener.onEvChanged(progress);
            }
        }
        return true;
    }
}
