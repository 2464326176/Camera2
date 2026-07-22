package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 九宫格辅助线覆盖层。
 * 仿 Google Camera 风格：细白线，半透明。
 * 支持三分法和黄金比例两种模式。
 */
public class GridOverlayView extends View {

    public static final int GRID_THIRDS = 0;       // 三分法
    public static final int GRID_GOLDEN = 1;        // 黄金比例
    public static final int GRID_DIAGONAL = 2;      // 对角线

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
        gridPaint.setColor(Color.parseColor("#33FFFFFF")); // 半透明白色
    }

    public void setGridMode(int mode) {
        this.gridMode = mode;
        invalidate();
    }

    public int getGridMode() {
        return gridMode;
    }

    /**
     * 循环切换网格模式。
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
     * 三分法网格。
     */
    private void drawThirdsGrid(Canvas canvas, int w, int h) {
        float thirdW = w / 3f;
        float thirdH = h / 3f;

        // 竖线
        canvas.drawLine(thirdW, 0, thirdW, h, gridPaint);
        canvas.drawLine(thirdW * 2, 0, thirdW * 2, h, gridPaint);

        // 横线
        canvas.drawLine(0, thirdH, w, thirdH, gridPaint);
        canvas.drawLine(0, thirdH * 2, w, thirdH * 2, gridPaint);
    }

    /**
     * 黄金比例网格 (φ ≈ 1.618)。
     */
    private void drawGoldenGrid(Canvas canvas, int w, int h) {
        float phi = 1.618033988749895f;
        float x1 = w / phi;
        float x2 = w - x1;
        float y1 = h / phi;
        float y2 = h - y1;

        // 稍微改变颜色以示区分
        Paint goldenPaint = new Paint(gridPaint);
        goldenPaint.setColor(Color.parseColor("#44FFD700")); // 金色半透明

        canvas.drawLine(x1, 0, x1, h, goldenPaint);
        canvas.drawLine(x2, 0, x2, h, goldenPaint);
        canvas.drawLine(0, y1, w, y1, goldenPaint);
        canvas.drawLine(0, y2, w, y2, goldenPaint);
    }

    /**
     * 对角线 + 中心十字。
     */
    private void drawDiagonalGrid(Canvas canvas, int w, int h) {
        Paint diagPaint = new Paint(gridPaint);
        diagPaint.setColor(Color.parseColor("#22FFFFFF"));

        // 对角线
        canvas.drawLine(0, 0, w, h, diagPaint);
        canvas.drawLine(w, 0, 0, h, diagPaint);

        // 中心十字（更细更淡）
        Paint centerPaint = new Paint(gridPaint);
        centerPaint.setStrokeWidth(0.5f);
        centerPaint.setColor(Color.parseColor("#44FFFFFF"));

        canvas.drawLine(w / 2f, 0, w / 2f, h, centerPaint);
        canvas.drawLine(0, h / 2f, w, h / 2f, centerPaint);
    }
}