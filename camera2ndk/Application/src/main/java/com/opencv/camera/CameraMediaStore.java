package com.opencv.camera;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stores the latest media result and owns camera media persistence/opening helpers.
 */
final class CameraMediaStore {

    private static final String TAG = "CameraMediaStore";
    private static final String ALBUM = "OpenCVCamera";
    private static final String MIME_IMAGE = "image/*";
    private static final String MIME_VIDEO = "video/*";

    enum ThumbnailStatus {
        EMPTY,
        CAPTURING,
        TEMPORARY,
        READY,
        ERROR
    }

    static final class ThumbnailState {
        final ThumbnailStatus status;
        final long captureId;
        final Bitmap bitmap;
        final Uri uri;
        final String mimeType;
        final boolean canOpen;
        final boolean pending;

        private ThumbnailState(
                ThumbnailStatus status,
                long captureId,
                Bitmap bitmap,
                Uri uri,
                String mimeType,
                boolean canOpen,
                boolean pending) {
            this.status = status;
            this.captureId = captureId;
            this.bitmap = bitmap;
            this.uri = uri;
            this.mimeType = mimeType;
            this.canOpen = canOpen;
            this.pending = pending;
        }

        static ThumbnailState empty() {
            return new ThumbnailState(ThumbnailStatus.EMPTY, 0L, null, null, null, false, false);
        }

        static ThumbnailState capturing(long captureId) {
            return new ThumbnailState(ThumbnailStatus.CAPTURING, captureId, null, null, null, false, true);
        }

        static ThumbnailState temporary(long captureId, Bitmap bitmap) {
            return new ThumbnailState(ThumbnailStatus.TEMPORARY, captureId, bitmap, null, null, false, true);
        }

        static ThumbnailState ready(long captureId, Bitmap bitmap, Uri uri, String mimeType) {
            return new ThumbnailState(ThumbnailStatus.READY, captureId, bitmap, uri, mimeType, uri != null, false);
        }

        static ThumbnailState error(long captureId, Bitmap fallbackBitmap) {
            return new ThumbnailState(ThumbnailStatus.ERROR, captureId, fallbackBitmap, null, null, false, false);
        }
    }

    private ThumbnailState currentState = ThumbnailState.empty();
    private Uri currentMediaUri;
    private long nextCaptureId = 1L;

    ThumbnailState getCurrentState() {
        return currentState;
    }

    Uri getCurrentMediaUri() {
        return currentMediaUri;
    }

    long beginPhotoCapture() {
        long captureId = nextCaptureId++;
        currentState = ThumbnailState.capturing(captureId);
        return captureId;
    }

    ThumbnailState setTemporaryPhotoThumbnail(long captureId, Bitmap bitmap) {
        if (!isCurrentCapture(captureId) || isTerminalState()) {
            return currentState;
        }
        currentState = ThumbnailState.temporary(captureId, bitmap);
        return currentState;
    }

    ThumbnailState setPhotoSaved(long captureId, Bitmap bitmap, Uri uri) {
        if (!isCurrentCapture(captureId)) {
            return currentState;
        }
        currentMediaUri = uri;
        currentState = ThumbnailState.ready(captureId, bitmap, uri, MIME_IMAGE);
        return currentState;
    }

    ThumbnailState setPhotoSaveFailed(long captureId, Bitmap fallbackBitmap) {
        if (!isCurrentCapture(captureId)) {
            return currentState;
        }
        currentState = ThumbnailState.error(captureId, fallbackBitmap);
        return currentState;
    }

    ThumbnailState setVideoSaved(Uri uri, Bitmap bitmap) {
        currentMediaUri = uri;
        currentState = ThumbnailState.ready(0L, bitmap, uri, MIME_VIDEO);
        return currentState;
    }

    boolean openCurrentMedia(Context context) {
        if (currentState == null || !currentState.canOpen || currentState.uri == null) {
            Toast.makeText(context, "Media is still processing", Toast.LENGTH_SHORT).show();
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(currentState.uri, currentState.mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(intent);
            return true;
        } catch (Exception firstError) {
            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, currentState.uri);
            fallbackIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(fallbackIntent);
                return true;
            } catch (Exception secondError) {
                Toast.makeText(context, "No app can open this media", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    static Uri saveJpegToGallery(Context context, byte[] jpeg, String prefix, int orientation) {
        if (jpeg == null || jpeg.length == 0) return null;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = prefix + "_" + timestamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                os.write(jpeg);
            }

            if (orientation != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                        if (pfd != null) {
                            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(toExifOrientation(orientation)));
                            exif.saveAttributes();
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set EXIF orientation, using re-encode fallback", e);
                    return saveJpegToGalleryWithReencode(context, jpeg, prefix, orientation, uri, values);
                }
            }

            markReady(context, uri, values, true);
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveJpegToGallery failed", e);
            deleteGalleryEntry(context, uri);
            return null;
        }
    }

    private static Uri saveJpegToGalleryWithReencode(
            Context context,
            byte[] jpeg,
            String prefix,
            int orientation,
            Uri failedUri,
            ContentValues failedValues) {
        deleteGalleryEntry(context, failedUri);

        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (decoded == null) return null;

        Bitmap output = decoded;
        if (orientation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(orientation);
            output = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = prefix + "_" + timestamp + "_rotated.jpg";
        ContentValues values = new ContentValues(failedValues);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                output.compress(Bitmap.CompressFormat.JPEG, 95, os);
            }
            markReady(context, uri, values, true);
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveJpegToGalleryWithReencode failed", e);
            deleteGalleryEntry(context, uri);
            return null;
        } finally {
            if (output != decoded) output.recycle();
            decoded.recycle();
        }
    }

    static Uri saveVideoToGallery(Context context, File videoFile) {
        return saveVideoToGallery(context, videoFile, "VID");
    }

    static Uri saveVideoToGallery(Context context, File videoFile, String prefix) {
        if (videoFile == null || !videoFile.exists()) return null;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = prefix + "_" + timestamp + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 FileInputStream fis = new FileInputStream(videoFile)) {
                if (os == null) return null;
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }

            markReady(context, uri, values, false);
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "saveVideoToGallery failed", e);
            deleteGalleryEntry(context, uri);
            return null;
        }
    }

    static Bitmap createVideoThumbnail(File file) {
        return file == null ? null : createVideoThumbnail(file.getAbsolutePath());
    }

    static Bitmap createVideoThumbnail(String path) {
        if (path == null) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            return retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            Log.e(TAG, "createVideoThumbnail failed", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.w(TAG, "Failed to release MediaMetadataRetriever", e);
            }
        }
    }

    static void deleteGalleryEntry(Context context, Uri uri) {
        if (uri == null) return;
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete gallery entry: " + uri, e);
        }
    }

    private boolean isCurrentCapture(long captureId) {
        return currentState != null && currentState.captureId == captureId;
    }

    private boolean isTerminalState() {
        return currentState != null
                && (currentState.status == ThumbnailStatus.READY
                || currentState.status == ThumbnailStatus.ERROR);
    }

    private static void markReady(Context context, Uri uri, ContentValues values, boolean image) {
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            if (image) {
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
            } else {
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
            }
            context.getContentResolver().update(uri, values, null, null);
        } else {
            MediaScannerConnection.scanFile(context, new String[]{uri.getPath()}, null, null);
        }
    }

    private static int toExifOrientation(int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            case 0:
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}
