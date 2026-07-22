package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Rule-of-thirds grid overlay.
 * Google Camera style: thin white lines, semi-transparent.
 * Supports thirds and golden ratio modes.
 */
public class GridOverlayView extends View {

    public static final int GRID_THIRDS = 0;       // Rule of thirds
    public static final int GRID_GOLDEN = 1;        // Golden ratio
    public static final int GRID_DIAGONAL = 2;      // Diagonal

    private final Paint gridPaint;
    private int gridMode = GRID_THIRDS;

    public GridOverlayView(Context context) {
        this(context, null);
    }

    public GridOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GridOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.0f);
        gridPaint.setColor(Color.parseColor("#33FFFFFF")); // Semi-transparent white
    }

    public void setGridMode(int mode) {
        this.gridMode = mode;
        invalidate();
    }

    public int getGridMode() {
        return gridMode;
    }

    /**
     * Cycle through grid modes.
     */
    public int cycleGridMode() {
        gridMode = (gridMode + 1) % 3;
        invalidate();
        return gridMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        if (w == 0 || h == 0) return;

        switch (gridMode) {
            case GRID_THIRDS:
                drawThirdsGrid(canvas, w, h);
                break;
            case GRID_GOLDEN:
                drawGoldenGrid(canvas, w, h);
                break;
            case GRID_DIAGONAL:
                drawDiagonalGrid(canvas, w, h);
                break;
        }
    }

    /**
     * Rule-of-thirds grid.
     */
    private void drawThirdsGrid(Canvas canvas, int w, int h) {
        float thirdW = w / 3f;
        float thirdH = h / 3f;

        // Vertical lines
        canvas.drawLine(thirdW, 0, thirdW, h, gridPaint);
        canvas.drawLine(thirdW * 2, 0, thirdW * 2, h, gridPaint);

        // Horizontal lines
        canvas.drawLine(0, thirdH, w, thirdH, gridPaint);
        canvas.drawLine(0, thirdH * 2, w, thirdH * 2, gridPaint);
    }

    /**
     * Golden ratio grid (phi ≈ 1.618).
     */
    private void drawGoldenGrid(Canvas canvas, int w, int h) {
        float phi = 1.618033988749895f;
        float x1 = w / phi;
        float x2 = w - x1;
        float y1 = h / phi;
        float y2 = h - y1;

        // Slightly different color for distinction
        Paint goldenPaint = new Paint(gridPaint);
        goldenPaint.setColor(Color.parseColor("#44FFD700")); // Gold semi-transparent

        canvas.drawLine(x1, 0, x1, h, goldenPaint);
        canvas.drawLine(x2, 0, x2, h, goldenPaint);
        canvas.drawLine(0, y1, w, y1, goldenPaint);
        canvas.drawLine(0, y2, w, y2, goldenPaint);
    }

    /**
     * Diagonal grid + center crosshair.
     */
    private void drawDiagonalGrid(Canvas canvas, int w, int h) {
        Paint diagPaint = new Paint(gridPaint);
        diagPaint.setColor(Color.parseColor("#22FFFFFF"));

        // Diagonals
        canvas.drawLine(0, 0, w, h, diagPaint);
        canvas.drawLine(w, 0, 0, h, diagPaint);

        // Center crosshair (thinner and lighter)
        Paint centerPaint = new Paint(gridPaint);
        centerPaint.setStrokeWidth(0.5f);
        centerPaint.setColor(Color.parseColor("#44FFFFFF"));

        canvas.drawLine(w / 2f, 0, w / 2f, h, centerPaint);
        canvas.drawLine(0, h / 2f, w, h / 2f, centerPaint);
    }
}