package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Live luminance histogram. Feed a 256-bin count array via {@link #setHistogram(int[])}.
 */
public class HistogramView extends View {

    private int[] hist = new int[256];
    private float[] norm = new float[256];
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HistogramView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setColor(0x336EA8FE);
        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(0xFF6EA8FE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f * context.getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setHistogram(int[] data) {
        if (data == null || data.length != 256) return;
        System.arraycopy(data, 0, hist, 0, 256);
        float max = 1f;
        for (int v : hist) {
            if (v > max) max = v;
        }
        for (int i = 0; i < 256; i++) {
            norm[i] = hist[i] / max;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Path path = new Path();
        path.moveTo(0, h);
        for (int i = 0; i < 256; i++) {
            float x = (i / 255f) * w;
            float y = h - norm[i] * (h - 2) - 1;
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.close();
        canvas.drawPath(path, fillPaint);

        Path line = new Path();
        for (int i = 0; i < 256; i++) {
            float x = (i / 255f) * w;
            float y = h - norm[i] * (h - 2) - 1;
            if (i == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
        }
        canvas.drawPath(line, linePaint);
    }
}
