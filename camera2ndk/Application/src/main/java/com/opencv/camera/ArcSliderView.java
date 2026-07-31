package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Curved (concave) vertical slider. Touch is mapped by vertical position; the
 * thumb is placed on a quadratic-bezier arc so motion feels curved while the
 * hit-testing stays robust. Supports linear or logarithmic value mapping.
 */
public class ArcSliderView extends View {

    public interface ValueFormatter {
        String format(double value);
    }

    public interface OnArcChangeListener {
        void onValueChanged(double value, boolean fromUser);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double min = 0;
    private double max = 1;
    private double value = 0;
    private boolean log = false;
    private boolean enabled = true;

    private ValueFormatter formatter;
    private OnArcChangeListener listener;

    private static final float PAD_DP = 16f;
    private static final float STROKE_DP = 6f;
    private static final float THUMB_DP = 11f;
    private float pad;
    private float stroke;
    private float thumbR;

    public ArcSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        pad = PAD_DP * d;
        stroke = STROKE_DP * d;
        thumbR = THUMB_DP * d;

        trackPaint.setColor(0x33FFFFFF);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint.setColor(0xFF6EA8FE);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        thumbPaint.setColor(0xFF6EA8FE);
        thumbStroke.setColor(0xFFFFFFFF);
        thumbStroke.setStyle(Paint.Style.STROKE);
        thumbStroke.setStrokeWidth(2f * d);
    }

    public void setRange(double min, double max) {
        this.min = min;
        this.max = max;
        clampValue();
        invalidate();
    }

    public void setLogarithmic(boolean log) {
        this.log = log;
        clampValue();
        invalidate();
    }

    public void setFormatter(ValueFormatter f) {
        this.formatter = f;
    }

    public void setOnArcChangeListener(OnArcChangeListener l) {
        this.listener = l;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        invalidate();
    }

    public void setValue(double v) {
        this.value = v;
        clampValue();
        invalidate();
    }

    public double getValue() {
        return value;
    }

    public String getFormattedValue() {
        return formatter != null ? formatter.format(value) : String.valueOf(value);
    }

    private void clampValue() {
        if (value < min) value = min;
        if (value > max) value = max;
    }

    private float fraction() {
        if (max <= min) return 0f;
        if (log && min > 0 && max > 0) {
            double lmin = Math.log(min);
            double lmax = Math.log(max);
            double lv = Math.log(Math.max(min, value));
            return (float) ((lv - lmin) / (lmax - lmin));
        }
        return (float) ((value - min) / (max - min));
    }

    private void setFraction(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (log && min > 0 && max > 0) {
            double lmin = Math.log(min);
            double lmax = Math.log(max);
            value = Math.exp(lmin + (lmax - lmin) * f);
        } else {
            value = min + (max - min) * f;
        }
        clampValue();
    }

    // Quadratic bezier anchors: bottom-center -> control right -> top-center (bow right).
    private float px0() { return getWidth() * 0.5f; }
    private float py0() { return getHeight() - pad; }
    private float px1() { return getWidth() * 0.5f; }
    private float py1() { return pad; }
    private float cx() { return getWidth() - pad; }
    private float cy() { return getHeight() * 0.5f; }

    private void bez(float t, float[] out) {
        float u = 1f - t;
        out[0] = u * u * px0() + 2 * u * t * cx() + t * t * px1();
        out[1] = u * u * py0() + 2 * u * t * cy() + t * t * py1();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float[] p = new float[2];

        // Track
        Path track = new Path();
        bez(0f, p); track.moveTo(p[0], p[1]);
        for (int i = 1; i <= 32; i++) {
            bez(i / 32f, p); track.lineTo(p[0], p[1]);
        }
        canvas.drawPath(track, trackPaint);

        // Progress
        float f = fraction();
        Path prog = new Path();
        bez(0f, p); prog.moveTo(p[0], p[1]);
        int steps = Math.max(1, (int) (f * 32));
        for (int i = 1; i <= steps; i++) {
            bez((i / 32f) * f, p); prog.lineTo(p[0], p[1]);
        }
        bez(f, p); prog.lineTo(p[0], p[1]);
        canvas.drawPath(prog, progressPaint);

        // Thumb
        bez(f, p);
        if (!enabled) {
            thumbPaint.setColor(0x55666666);
        } else {
            thumbPaint.setColor(0xFF6EA8FE);
        }
        canvas.drawCircle(p[0], p[1], thumbR, thumbPaint);
        canvas.drawCircle(p[0], p[1], thumbR, thumbStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_MOVE) {
            float usable = getHeight() - 2 * pad;
            if (usable <= 0) return false;
            float f = (getHeight() - pad - event.getY()) / usable;
            setFraction(f);
            invalidate();
            if (listener != null) listener.onValueChanged(value, true);
            return true;
        }
        return super.onTouchEvent(event);
    }
}
