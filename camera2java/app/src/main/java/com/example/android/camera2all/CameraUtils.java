package com.example.android.camera2all;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Shared helpers for the camera fragments: choosing a camera by lens facing,
 * decoding a downscaled thumbnail off the UI thread, and opening a captured
 * file with a FileProvider-backed intent.
 */
public final class CameraUtils {

    // Authority declared in the manifest <provider> element.
    public static final String FILE_PROVIDER_AUTHORITY =
            "com.example.android.camera2all.fileprovider";

    private CameraUtils() {
    }

    // Returns the id of the first camera whose lens faces the requested direction, or the first
    // available camera as a fallback (so a device with only one facing still works).
    public static String chooseCameraId(CameraManager manager, int facing)
            throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer f = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facing) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    // Copies the encoded JPEG bytes out of a JPEG-format Image WITHOUT closing it, so the caller
    // can still hand the Image to an ImageSaver afterwards. Returns null for non-JPEG images.
    public static byte[] imageToJpegBytes(Image image) {
        if (image == null || image.getFormat() != android.graphics.ImageFormat.JPEG) {
            return null;
        }
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    // Decodes a downscaled JPEG on the background thread, then sets it on the thumbnail button (UI thread).
    public static void updateImageThumbnail(final File file, final ImageButton thumb,
                                            final Handler bg, final Handler ui) {
        if (bg == null || thumb == null) return;
        bg.post(() -> {
            final Bitmap bmp = decodeSampled(file, 256);
            if (ui != null && bmp != null) {
                ui.post(() -> thumb.setImageBitmap(bmp));
            }
        });
    }

    // Extracts the first frame of a video on the background thread, then sets it on the thumbnail button.
    public static void updateVideoThumbnail(final File file, final ImageButton thumb,
                                            final Handler bg, final Handler ui) {
        if (bg == null || thumb == null) return;
        bg.post(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
            final Bitmap finalBmp = bmp;
            if (ui != null) {
                ui.post(() -> {
                    if (finalBmp != null) {
                        thumb.setImageBitmap(finalBmp);
                    }
                });
            }
        });
    }

    // Opens a captured image/video in an external viewer via a FileProvider grant.
    public static void openMedia(Context context, File file, boolean isVideo) {
        if (context == null || file == null || !file.exists()) {
            return;
        }
        Uri uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file);
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(uri, isVideo ? "video/mp4" : "image/jpeg");
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    // Loads a bitmap downscaled so its largest dimension is ~target px (cheap memory footprint).
    private static Bitmap decodeSampled(File file, int target) {
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return null;
        }
        int inSample = 1;
        int halfW = opts.outWidth / 2;
        int halfH = opts.outHeight / 2;
        while (halfW / inSample >= target && halfH / inSample >= target) {
            inSample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = inSample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
