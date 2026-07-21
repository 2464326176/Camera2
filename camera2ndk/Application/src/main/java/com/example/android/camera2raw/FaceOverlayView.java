package com.example.android.camera2raw;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class FaceOverlayView extends View {
    private final Paint paint = new Paint();
    private RectF[] faces = new RectF[0];

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(5f);
    }

    public void setFaces(RectF[] faces) {
        this.faces = faces;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (RectF face : faces) {
            if (face != null) canvas.drawRect(face, paint);
        }
    }
}