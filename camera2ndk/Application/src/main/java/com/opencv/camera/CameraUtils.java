package com.opencv.camera;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
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

        Log.d(TAG, "Saving JPEG: " + jpeg.length + " bytes, orientation=" + orientation);

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

            // Write JPEG bytes directly to avoid double encoding quality loss
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                os.write(jpeg);
            }

            // Write rotation via ExifInterface to avoid re-encoding
            if (orientation != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    try (ParcelFileDescriptor pfd = context.getContentResolver()
                            .openFileDescriptor(uri, "rw")) {
                        if (pfd != null) {
                            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                            int exifOrientation;
                            switch (orientation) {
                                case 90:  exifOrientation = ExifInterface.ORIENTATION_ROTATE_90;  break;
                                case 180: exifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                                case 270: exifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                                default:  exifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
                            }
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
                            exif.saveAttributes();
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set EXIF orientation, using fallback", e);
                    // Fallback: decode → rotate → re-encode (only when EXIF write fails)
                    return saveJpegToGalleryWithReencode(context, jpeg, prefix, orientation, uri, values);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveJpegToGallery failed", e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /**
     * Fallback: decode, rotate, then re-encode. Only used when EXIF write fails.
     */
    private static Uri saveJpegToGalleryWithReencode(Context context, byte[] jpeg, String prefix,
                                                      int orientation, Uri existingUri,
                                                      ContentValues existingValues) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
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

        try (OutputStream os = context.getContentResolver().openOutputStream(existingUri)) {
            if (os == null) return null;
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "saveJpegToGalleryWithReencode failed", e);
            return null;
        } finally {
            bitmap.recycle();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            existingValues.clear();
            existingValues.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(existingUri, existingValues, null, null);
        }
        return existingUri;
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

    /** Delete a gallery entry by URI. Does nothing if URI is null. */
    public static void deleteGalleryEntry(Context context, Uri uri) {
        if (uri == null) return;
        try {
            int deleted = context.getContentResolver().delete(uri, null, null);
            Log.d(TAG, "Deleted gallery entry: " + uri + " rows=" + deleted);
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete gallery entry: " + uri, e);
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
     * Extract first frame thumbnail from video file.
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
     * TextureView transform: portrait fullscreen center-crop.
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
