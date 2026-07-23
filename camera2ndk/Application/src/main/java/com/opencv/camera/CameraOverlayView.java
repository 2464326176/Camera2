package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified camera overlay for composition grid and face detection marks.
 */
public class CameraOverlayView extends View {

    public static final int GRID_THIRDS = 0;
    public static final int GRID_GOLDEN = 1;
    public static final int GRID_DIAGONAL = 2;

    private final Paint gridPaint;
    private final Paint facePaint;
    private final Paint cornerPaint;
    private final Matrix transform = new Matrix();
    private final RectF mapped = new RectF();
    private final List<RectF> faceRects = new ArrayList<>();

    private int gridMode = GRID_THIRDS;
    private boolean gridVisible;

    private int imageWidth;
    private int imageHeight;
    private int sensorOrientation = 90;
    private boolean frontCamera;
    private boolean displayMirror;
    private float targetAspectRatio;

    public CameraOverlayView(Context context) {
        this(context, null);
    }

    public CameraOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.0f);
        gridPaint.setColor(Color.parseColor("#33FFFFFF"));

        facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeWidth(3f);
        facePaint.setColor(Color.parseColor("#4CAF50"));

        cornerPaint = new Paint(facePaint);
        cornerPaint.setStrokeWidth(5f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setGridVisible(boolean visible) {
        if (gridVisible != visible) {
            gridVisible = visible;
            postInvalidateOnAnimation();
        }
    }

    public boolean isGridVisible() {
        return gridVisible;
    }

    public void setGridMode(int mode) {
        gridMode = mode;
        postInvalidateOnAnimation();
    }

    public int getGridMode() {
        return gridMode;
    }

    public int cycleGridMode() {
        gridMode = (gridMode + 1) % 3;
        postInvalidateOnAnimation();
        return gridMode;
    }

    public void setPreviewSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        rebuildTransform();
    }

    public void setCameraInfo(int sensorOrientation, boolean frontCamera) {
        this.sensorOrientation = sensorOrientation;
        this.frontCamera = frontCamera;
        displayMirror = frontCamera;
        rebuildTransform();
    }

    public void setTargetAspectRatio(float targetAspectRatio) {
        this.targetAspectRatio = targetAspectRatio;
        rebuildTransform();
    }

    public boolean isFrontCamera() {
        return frontCamera;
    }

    public void setFaces(RectF[] faces) {
        faceRects.clear();
        if (faces != null) {
            for (RectF face : faces) {
                if (face != null && face.width() > 1 && face.height() > 1) {
                    faceRects.add(new RectF(face));
                }
            }
        }
        postInvalidateOnAnimation();
    }

    public void clearFaces() {
        if (!faceRects.isEmpty()) {
            faceRects.clear();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildTransform();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        if (gridVisible) {
            drawGrid(canvas, width, height);
        }
        drawFaces(canvas);
    }

    private void drawGrid(Canvas canvas, int width, int height) {
        switch (gridMode) {
            case GRID_GOLDEN:
                drawGoldenGrid(canvas, width, height);
                break;
            case GRID_DIAGONAL:
                drawDiagonalGrid(canvas, width, height);
                break;
            case GRID_THIRDS:
            default:
                drawThirdsGrid(canvas, width, height);
                break;
        }
    }

    private void drawThirdsGrid(Canvas canvas, int width, int height) {
        float thirdW = width / 3f;
        float thirdH = height / 3f;
        canvas.drawLine(thirdW, 0, thirdW, height, gridPaint);
        canvas.drawLine(thirdW * 2, 0, thirdW * 2, height, gridPaint);
        canvas.drawLine(0, thirdH, width, thirdH, gridPaint);
        canvas.drawLine(0, thirdH * 2, width, thirdH * 2, gridPaint);
    }

    private void drawGoldenGrid(Canvas canvas, int width, int height) {
        float phi = 1.618033988749895f;
        float x1 = width / phi;
        float x2 = width - x1;
        float y1 = height / phi;
        float y2 = height - y1;

        Paint goldenPaint = new Paint(gridPaint);
        goldenPaint.setColor(Color.parseColor("#44FFD700"));
        canvas.drawLine(x1, 0, x1, height, goldenPaint);
        canvas.drawLine(x2, 0, x2, height, goldenPaint);
        canvas.drawLine(0, y1, width, y1, goldenPaint);
        canvas.drawLine(0, y2, width, y2, goldenPaint);
    }

    private void drawDiagonalGrid(Canvas canvas, int width, int height) {
        Paint diagPaint = new Paint(gridPaint);
        diagPaint.setColor(Color.parseColor("#22FFFFFF"));
        canvas.drawLine(0, 0, width, height, diagPaint);
        canvas.drawLine(width, 0, 0, height, diagPaint);

        Paint centerPaint = new Paint(gridPaint);
        centerPaint.setStrokeWidth(0.5f);
        centerPaint.setColor(Color.parseColor("#44FFFFFF"));
        canvas.drawLine(width / 2f, 0, width / 2f, height, centerPaint);
        canvas.drawLine(0, height / 2f, width, height / 2f, centerPaint);
    }

    private void drawFaces(Canvas canvas) {
        if (faceRects.isEmpty()) {
            return;
        }

        for (RectF face : faceRects) {
            mapped.set(face);
            transform.mapRect(mapped);
            drawFaceCorners(canvas, mapped);
        }
    }

    private void drawFaceCorners(Canvas canvas, RectF rect) {
        float cornerLength = Math.min(rect.width(), rect.height()) * 0.22f;

        canvas.drawRoundRect(rect, 12f, 12f, facePaint);

        canvas.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, cornerPaint);
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, cornerPaint);
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLength, rect.top, cornerPaint);
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, cornerPaint);
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, cornerPaint);
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, cornerPaint);
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLength, rect.bottom, cornerPaint);
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, cornerPaint);
    }

    private void rebuildTransform() {
        transform.reset();
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            postInvalidateOnAnimation();
            return;
        }

        RectF targetRect = CameraUtils.createCenteredTargetRect(
                getWidth(), getHeight(), targetAspectRatio);
        float scale = Math.max(
                targetRect.width() / (float) imageWidth,
                targetRect.height() / (float) imageHeight);
        float scaledWidth = imageWidth * scale;
        float scaledHeight = imageHeight * scale;
        float dx = targetRect.left + (targetRect.width() - scaledWidth) / 2f;
        float dy = targetRect.top + (targetRect.height() - scaledHeight) / 2f;

        transform.postScale(scale, scale);
        transform.postTranslate(dx, dy);

        if (displayMirror) {
            transform.postScale(-1f, 1f, getWidth() / 2f, getHeight() / 2f);
        }

        postInvalidateOnAnimation();
    }
}
