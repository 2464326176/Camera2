package com.opencv.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * OpenCV NDK 图像处理封装。
 * - 预览：YuNet 人脸检测
 * - 拍照：按 ISO 选择单帧 / 多帧降噪
 */
public class ImageProcessor {
    private static final String TAG = "ImageProcessor";
    private static final String MODEL_FILE = "face_detection_yunet_2023mar.onnx";

    private static boolean sLibsLoaded = false;
    private static boolean sDetectorReady = false;

    /** UI / 业务模式 */
    public static final int MODE_PHOTO = 0;
    public static final int MODE_VIDEO = 1;

    private static int sCurrentMode = MODE_PHOTO;

    static {
        try {
            System.loadLibrary("opencv_java4");
            System.loadLibrary("native-lib");
            sLibsLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
            sLibsLoaded = false;
        }
    }

    public static boolean isLibsLoaded() {
        return sLibsLoaded;
    }

    public static synchronized boolean initFaceDetector(Context context) {
        if (!sLibsLoaded) return false;
        if (sDetectorReady) return true;
        try {
            File modelFile = new File(context.getFilesDir(), MODEL_FILE);
            if (!modelFile.exists() || modelFile.length() == 0) {
                copyRawResource(context, R.raw.face_detection_yunet_2023mar, modelFile);
            }
            sDetectorReady = nativeInitFaceDetector(modelFile.getAbsolutePath());
            if (!sDetectorReady) {
                Log.e(TAG, "nativeInitFaceDetector failed");
            }
            return sDetectorReady;
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare YuNet model", e);
            return false;
        }
    }

    public static synchronized void releaseFaceDetector() {
        if (!sLibsLoaded) return;
        try {
            nativeReleaseFaceDetector();
        } catch (Throwable t) {
            Log.w(TAG, "releaseFaceDetector: " + t.getMessage());
        }
        sDetectorReady = false;
    }

    public static void setProcessMode(int mode) {
        sCurrentMode = mode;
    }

    public static int getProcessMode() {
        return sCurrentMode;
    }

    /**
     * 根据 ISO 决定多帧降噪帧数。
     * ISO &lt; 200 → 1 帧
     * 200–399 → 3 帧
     * 400–799 → 4 帧
     * 800–1599 → 5 帧
     * ≥1600 → 6 帧
     */
    public static int frameCountForIso(int iso) {
        if (iso < 200) return 1;
        if (iso < 400) return 3;
        if (iso < 800) return 4;
        if (iso < 1600) return 5;
        return 6;
    }

    /**
     * YUV_420_888 → NV21（兼容 pixelStride / rowStride）。
     */
    public static byte[] imageToNv21(Image image) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int width = image.getWidth();
        int height = image.getHeight();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];

        int pos = 0;
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, width * height);
            pos = width * height;
        } else if (yPixelStride == 1) {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, pos, width);
                pos += width;
            }
        } else {
            for (int row = 0; row < height; row++) {
                int rowOffset = row * yRowStride;
                for (int col = 0; col < width; col++) {
                    nv21[pos++] = yBuffer.get(rowOffset + col * yPixelStride);
                }
            }
        }

        // Common case: VU interleaved (NV21 layout already in v plane with pixelStride=2)
        if (vPixelStride == 2 && uPixelStride == 2
                && vRowStride == uRowStride
                && planes[1].getBuffer().capacity() > 0) {
            // On most devices plane[2] is V with interleaved U (NV21)
            // and plane[1] is U with interleaved V.
            // Prefer building NV21 from V plane when pixelStride==2.
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            byte[] rowBuf = new byte[vRowStride];
            int outPos = width * height;
            for (int row = 0; row < uvHeight; row++) {
                vBuffer.position(row * vRowStride);
                int toRead = Math.min(vRowStride, vBuffer.remaining());
                vBuffer.get(rowBuf, 0, toRead);
                for (int col = 0; col < uvWidth; col++) {
                    int idx = col * vPixelStride;
                    // V then U for NV21
                    nv21[outPos++] = rowBuf[idx];
                    // U is typically at idx+1 when interleaved
                    if (idx + 1 < toRead) {
                        nv21[outPos++] = rowBuf[idx + 1];
                    } else {
                        nv21[outPos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                    }
                }
            }
            return nv21;
        }

        int uvWidth = width / 2;
        int uvHeight = height / 2;
        for (int row = 0; row < uvHeight; row++) {
            int uRowOffset = row * uRowStride;
            int vRowOffset = row * vRowStride;
            for (int col = 0; col < uvWidth; col++) {
                nv21[pos++] = vBuffer.get(vRowOffset + col * vPixelStride);
                nv21[pos++] = uBuffer.get(uRowOffset + col * uPixelStride);
            }
        }
        return nv21;
    }

    /**
     * 预览人脸检测。返回图像坐标系下的矩形 [left,top,right,bottom]。
     */
    public static RectF[] detectFaces(Image image) {
        if (!sDetectorReady || image == null) return new RectF[0];
        byte[] nv21 = imageToNv21(image);
        if (nv21 == null) return new RectF[0];
        return detectFacesNv21(nv21, image.getWidth(), image.getHeight());
    }

    public static RectF[] detectFacesNv21(byte[] nv21, int width, int height) {
        if (!sDetectorReady || nv21 == null) return new RectF[0];
        float[] boxes;
        try {
            boxes = nativeDetectFaces(nv21, width, height);
        } catch (Throwable t) {
            Log.e(TAG, "detectFaces failed", t);
            return new RectF[0];
        }
        if (boxes == null || boxes.length < 4) return new RectF[0];

        int count = boxes.length / 4;
        RectF[] faces = new RectF[count];
        for (int i = 0; i < count; i++) {
            float x = boxes[i * 4];
            float y = boxes[i * 4 + 1];
            float w = boxes[i * 4 + 2];
            float h = boxes[i * 4 + 3];
            faces[i] = new RectF(x, y, x + w, y + h);
        }
        return faces;
    }

    /**
     * 拍照降噪：按帧数走单帧或多帧 NDK 算法，返回 JPEG 字节。
     */
    public static byte[] denoiseAndEncodeJpeg(List<byte[]> nv21Frames, int width, int height,
                                              int iso, int jpegQuality) {
        if (!sLibsLoaded || nv21Frames == null || nv21Frames.isEmpty()) {
            return null;
        }
        try {
            byte[] denoised;
            if (nv21Frames.size() >= 2) {
                denoised = nativeDenoiseMulti(
                        nv21Frames.toArray(new byte[0][]),
                        nv21Frames.size(), width, height, iso);
            } else {
                denoised = nativeDenoiseSingle(nv21Frames.get(0), width, height, iso);
            }
            if (denoised == null) {
                denoised = nv21Frames.get(0);
            }
            return nativeEncodeNv21Jpeg(denoised, width, height, jpegQuality);
        } catch (Throwable t) {
            Log.e(TAG, "denoiseAndEncodeJpeg failed", t);
            // Fallback: Java YuvImage encode
            return nv21ToJpeg(nv21Frames.get(0), width, height, jpegQuality);
        }
    }

    public static byte[] nv21ToJpeg(byte[] nv21, int width, int height, int quality) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "nv21ToJpeg failed", e);
            return null;
        }
    }

    public static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        byte[] jpeg = nv21ToJpeg(nv21, width, height, 90);
        if (jpeg == null) return null;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    public static Bitmap jpegToBitmap(byte[] jpeg) {
        if (jpeg == null) return null;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    /**
     * 对照片 Bitmap 做轻度后处理（锐化 + 饱和度）。
     */
    public static Bitmap processPhoto(Bitmap input) {
        if (input == null || !sLibsLoaded) return input;

        Bitmap output = input.copy(Bitmap.Config.ARGB_8888, true);
        try {
            nativeSharpen(output, 0.15f, 2.0f);
            nativeAdjustSaturation(output, 1.05f);
            return output;
        } catch (Exception e) {
            Log.e(TAG, "Photo processing error: " + e.getMessage());
            if (output != input) output.recycle();
            return input;
        }
    }

    // ========== JNI ==========

    private static native boolean nativeInitFaceDetector(String modelPath);
    private static native void nativeReleaseFaceDetector();
    private static native float[] nativeDetectFaces(byte[] nv21, int width, int height);
    private static native byte[] nativeDenoiseSingle(byte[] nv21, int width, int height, int iso);
    private static native byte[] nativeDenoiseMulti(byte[][] frames, int count, int width, int height, int iso);
    private static native byte[] nativeEncodeNv21Jpeg(byte[] nv21, int width, int height, int quality);

    private static native void nativeSharpen(Bitmap bitmap, float strength, float radius);
    private static native void nativeAutoContrast(Bitmap bitmap, float clipLimit, int tileGridSize);
    private static native void nativeDenoise(Bitmap bitmap, int filterSize, float sigmaColor, float sigmaSpace);
    private static native void nativeFastNlMeansDenoise(Bitmap bitmap, float h, int templateWindowSize, int searchWindowSize);
    private static native void nativeAdjustSaturation(Bitmap bitmap, float factor);
    private static native void nativeHdrToneMap(Bitmap bitmap, float gamma, float intensity);
    private static native void nativeBokeh(Bitmap bitmap, float centerX, float centerY, float focusRadius, float blurStrength);
    private static native void nativeRelease();

    private static void copyRawResource(Context context, int resId, File outFile) throws IOException {
        try (InputStream in = context.getResources().openRawResource(resId);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
