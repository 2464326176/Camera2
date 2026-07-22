package com.opencv.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 人脸框覆盖层。
 * 输入为预览帧坐标系（传感器输出方向，通常横向），内部做旋转/镜像/缩放映射到 View。
 */
public class FaceOverlayView extends View {

    private final Paint facePaint;
    private final Paint cornerPaint;
    private final List<RectF> faceRects = new ArrayList<>();

    private int imageWidth;
    private int imageHeight;
    private int sensorOrientation = 90;
    private boolean frontCamera = false;
    private boolean displayMirror = false;

    private final Matrix transform = new Matrix();
    private final RectF mapped = new RectF();

    public FaceOverlayView(Context context) {
        this(context, null);
    }

    public FaceOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FaceOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeWidth(3f);
        facePaint.setColor(Color.parseColor("#4CAF50"));

        cornerPaint = new Paint(facePaint);
        cornerPaint.setStrokeWidth(5f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * @param width  预览帧宽（Image.getWidth）
     * @param height 预览帧高
     */
    public void setPreviewSize(int width, int height) {
        this.imageWidth = width;
        this.imageHeight = height;
        rebuildTransform();
    }

    public void setCameraInfo(int sensorOrientation, boolean frontCamera) {
        this.sensorOrientation = sensorOrientation;
        this.frontCamera = frontCamera;
        // Front camera preview is typically mirrored on screen
        this.displayMirror = frontCamera;
        rebuildTransform();
    }

    /**
     * 设置人脸框（图像坐标系：left,top,right,bottom）。
     */
    public void setFaces(RectF[] faces) {
        faceRects.clear();
        if (faces != null) {
            for (RectF f : faces) {
                if (f != null && f.width() > 1 && f.height() > 1) {
                    faceRects.add(new RectF(f));
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

    private void rebuildTransform() {
        transform.reset();
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        int viewW = getWidth();
        int viewH = getHeight();

        // After rotation, buffer dimensions swap for 90/270
        boolean rotated = (sensorOrientation % 180 != 0);
        float bufferW = rotated ? imageHeight : imageWidth;
        float bufferH = rotated ? imageWidth : imageHeight;

        // Center-crop scale (match TextureView FILL behavior)
        float scale = Math.max(viewW / bufferW, viewH / bufferH);
        float scaledW = bufferW * scale;
        float scaledH = bufferH * scale;
        float dx = (viewW - scaledW) / 2f;
        float dy = (viewH - scaledH) / 2f;

        // Map image coords → rotated upright buffer → view
        // 1) optional mirror on image X (front camera)
        if (displayMirror) {
            transform.postScale(-1f, 1f, imageWidth / 2f, imageHeight / 2f);
        }

        // 2) rotate around origin then translate into positive quadrant
        transform.postRotate(sensorOrientation);
        if (sensorOrientation == 90) {
            transform.postTranslate(imageHeight, 0);
        } else if (sensorOrientation == 180) {
            transform.postTranslate(imageWidth, imageHeight);
        } else if (sensorOrientation == 270) {
            transform.postTranslate(0, imageWidth);
        }

        // 3) scale + center
        transform.postScale(scale, scale);
        transform.postTranslate(dx, dy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faceRects.isEmpty() || imageWidth <= 0) return;

        for (RectF src : faceRects) {
            mapped.set(src);
            transform.mapRect(mapped);

            float corner = Math.min(mapped.width(), mapped.height()) * 0.12f;
            canvas.drawRoundRect(mapped, corner, corner, facePaint);
            drawCorners(canvas, mapped, corner);
        }
    }

    private void drawCorners(Canvas canvas, RectF rect, float cornerRadius) {
        float lineLen = Math.max(cornerRadius * 1.8f, 16f);

        // TL
        canvas.drawLine(rect.left, rect.top, rect.left + lineLen, rect.top, cornerPaint);
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + lineLen, cornerPaint);
        // TR
        canvas.drawLine(rect.right, rect.top, rect.right - lineLen, rect.top, cornerPaint);
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + lineLen, cornerPaint);
        // BL
        canvas.drawLine(rect.left, rect.bottom, rect.left + lineLen, rect.bottom, cornerPaint);
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - lineLen, cornerPaint);
        // BR
        canvas.drawLine(rect.right, rect.bottom, rect.right - lineLen, rect.bottom, cornerPaint);
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - lineLen, cornerPaint);
    }
}
