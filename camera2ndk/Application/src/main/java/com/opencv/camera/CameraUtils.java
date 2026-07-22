package com.opencv.camera;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CameraUtils {
    private static final String TAG = "CameraUtils";
    private static final String ALBUM = "OpenCVCamera";

    public static Uri saveJpegToGallery(Context context, byte[] jpeg, String prefix, int orientation) {
        if (jpeg == null || jpeg.length == 0) return null;

        // Apply orientation by decoding if needed
        Bitmap bitmap = ImageProcessor.jpegToBitmap(jpeg);
        if (bitmap == null) return null;

        if (orientation != 0) {
            Matrix m = new Matrix();
            m.postRotate(orientation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) {
                bitmap.recycle();
                bitmap = rotated;
            }
        }
        return saveBitmapToGallery(context, bitmap, prefix);
    }

    public static Uri saveBitmapToGallery(Context context, Bitmap bitmap, String prefix) {
        if (bitmap == null) return null;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String fileName = prefix + "_" + timestamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                    return null;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveBitmapToGallery failed", e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    public static Uri saveVideoToGallery(Context context, File videoFile) {
        if (videoFile == null || !videoFile.exists()) return null;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String fileName = "VID_" + timestamp + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(videoFile)) {
                if (os == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                MediaScannerConnection.scanFile(context,
                        new String[]{videoFile.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);
            }
            // cleanup cache
            //noinspection ResultOfMethodCallIgnored
            videoFile.delete();
            return uri;
        } catch (IOException e) {
            Log.e(TAG, "saveVideoToGallery failed", e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    public static void openGallery(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        intent.setDataAndType(uri, "image/*");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Intent pickerIntent = new Intent(Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try {
                pickerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(pickerIntent);
            } catch (Exception ex) {
                Log.e(TAG, "openGallery failed", ex);
            }
        }
    }

    public static void openLatestPhoto(Context context, Uri photoUri) {
        if (photoUri == null) {
            openGallery(context);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(photoUri, "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            openGallery(context);
        }
    }

    /**
     * 从视频文件提取首帧缩略图。
     */
    public static Bitmap createVideoThumbnail(File videoFile) {
        if (videoFile == null || !videoFile.exists()) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            Log.e(TAG, "createVideoThumbnail failed", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * TextureView transform：竖屏全屏 center-crop。
     */
    public static Matrix configureTransform(int viewWidth, int viewHeight,
                                             int previewWidth, int previewHeight,
                                             int rotation, int sensorOrientation,
                                             boolean frontCamera) {
        Matrix matrix = new Matrix();
        if (viewWidth == 0 || viewHeight == 0 || previewWidth == 0 || previewHeight == 0) {
            return matrix;
        }

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);

        // Buffer after sensor rotation to display orientation
        boolean swap = (sensorOrientation % 180 != 0);
        float bufferW = swap ? previewHeight : previewWidth;
        float bufferH = swap ? previewWidth : previewHeight;

        float scale = Math.max(viewWidth / bufferW, viewHeight / bufferH);
        float scaledW = bufferW * scale;
        float scaledH = bufferH * scale;
        float dx = (viewWidth - scaledW) / 2f;
        float dy = (viewHeight - scaledH) / 2f;

        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);

        // Additional display rotation correction if device not portrait
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            matrix.postRotate(90 * (rotation == Surface.ROTATION_90 ? 1 : -1),
                    viewRect.centerX(), viewRect.centerY());
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, viewRect.centerX(), viewRect.centerY());
        }

        if (frontCamera) {
            // Mirror horizontally for natural selfie preview
            matrix.postScale(-1f, 1f, viewRect.centerX(), viewRect.centerY());
        }

        return matrix;
    }
}
