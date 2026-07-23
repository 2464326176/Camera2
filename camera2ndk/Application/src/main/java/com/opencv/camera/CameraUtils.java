package com.opencv.camera;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Size;
import android.view.Surface;

/**
 * Pure camera math helpers shared by UI and Camera2 plumbing.
 */
public final class CameraUtils {

    private CameraUtils() {
        // Utility class.
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            int rotation,
            Size previewSize) {
        return configureTransform(viewWidth, viewHeight, previewSize, rotation);
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            int previewWidth,
            int previewHeight,
            int rotation,
            int sensorOrientation,
            boolean frontCamera) {
        return configureTransform(viewWidth, viewHeight, previewWidth, previewHeight,
                rotation, sensorOrientation, frontCamera, 0f);
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            int previewWidth,
            int previewHeight,
            int rotation,
            int sensorOrientation,
            boolean frontCamera,
            float targetAspectRatio) {
        Matrix matrix = configureTransform(
                viewWidth,
                viewHeight,
                new Size(previewWidth, previewHeight),
                rotation,
                sensorOrientation,
                targetAspectRatio);
        if (frontCamera) {
            matrix.postScale(-1f, 1f, viewWidth / 2f, viewHeight / 2f);
        }
        return matrix;
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            Size previewSize,
            int rotation) {
        return configureTransform(viewWidth, viewHeight, previewSize, rotation, 90, 0f);
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            Size previewSize,
            int rotation,
            int sensorOrientation) {
        return configureTransform(viewWidth, viewHeight, previewSize, rotation, sensorOrientation, 0f);
    }

    public static Matrix configureTransform(
            int viewWidth,
            int viewHeight,
            Size previewSize,
            int rotation,
            int sensorOrientation,
            float targetAspectRatio) {
        Matrix matrix = new Matrix();
        if (previewSize == null || viewWidth <= 0 || viewHeight <= 0) {
            return matrix;
        }

        int displayDegrees = displayRotationToDegrees(rotation);
        boolean swapped = areDimensionsSwapped(displayDegrees, sensorOrientation);
        float bufferWidth = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float bufferHeight = swapped ? previewSize.getWidth() : previewSize.getHeight();

        RectF targetRect = createCenteredTargetRect(viewWidth, viewHeight, targetAspectRatio);
        RectF bufferRect = new RectF(0, 0, bufferWidth, bufferHeight);
        float centerX = targetRect.centerX();
        float centerY = targetRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(bufferRect, targetRect, Matrix.ScaleToFit.CENTER);

        float scale = Math.max(targetRect.width() / bufferWidth, targetRect.height() / bufferHeight);
        matrix.postScale(scale, scale, centerX, centerY);

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        return matrix;
    }

    public static RectF createCenteredTargetRect(int viewWidth, int viewHeight, float targetAspectRatio) {
        RectF fullRect = new RectF(0, 0, viewWidth, viewHeight);
        if (targetAspectRatio <= 0f || viewWidth <= 0 || viewHeight <= 0) {
            return fullRect;
        }

        float viewAspect = viewWidth / (float) viewHeight;
        float targetWidth = viewWidth;
        float targetHeight = viewHeight;
        if (viewAspect > targetAspectRatio) {
            targetWidth = viewHeight * targetAspectRatio;
        } else {
            targetHeight = viewWidth / targetAspectRatio;
        }

        float left = (viewWidth - targetWidth) / 2f;
        float top = (viewHeight - targetHeight) / 2f;
        return new RectF(left, top, left + targetWidth, top + targetHeight);
    }

    private static int displayRotationToDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private static boolean areDimensionsSwapped(int displayDegrees, int sensorOrientation) {
        return (sensorOrientation + displayDegrees) % 180 != 0;
    }
}
