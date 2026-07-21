package com.example.android.camera2raw;

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

public class ImageProcessor {
    private static final String TAG = "ImageProcessor";
    private static final String MODEL_FILE = "face_detection_yunet_2023mar.onnx";

    private static boolean sLibsLoaded = false;
    private static boolean sDetectorReady = false;

    static {
        System.loadLibrary("opencv_java4");
        System.loadLibrary("native-lib");
        sLibsLoaded = true;
    }

    public static synchronized boolean initFaceDetector(Context context) {
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
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare YuNet model", e);
            return false;
        }
    }

    public static synchronized void releaseFaceDetector() {
        if (!sLibsLoaded) return;
        nativeReleaseFaceDetector();
        sDetectorReady = false;
    }

    /**
     * 将 YUV_420_888 Image 转换为 NV21 字节数组。
     * 正确处理 pixel stride 和 row stride。
     */
    public static byte[] imageToNv21(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) return null;

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

        // 复制 Y
        int pos = 0;
        if (yPixelStride == 1) {
            // 连续直接复制
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

        // 复制 VU (NV21 顺序: V, U)
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

    public static RectF[] detectFaces(Image image) {
        if (!sDetectorReady) return new RectF[0];
        byte[] nv21 = imageToNv21(image);
        if (nv21 == null) return new RectF[0];

        float[] boxes = nativeDetectFaces(nv21, image.getWidth(), image.getHeight());
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

    public static byte[] denoiseAndEncode(List<byte[]> nv21Frames, int width, int height,
                                          boolean isMultiFrame) {
        if (nv21Frames == null || nv21Frames.isEmpty()) return null;
        if (isMultiFrame) {
            return nativeDenoiseMulti(nv21Frames.toArray(new byte[0][]),
                    nv21Frames.size(), width, height);
        } else {
            return nativeDenoiseSingle(nv21Frames.get(0), width, height);
        }
    }

    /** 将 NV21 转为 Bitmap（用于缩略图），质量 80 可平衡速度与画质 */
    public static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), 80, out);
            byte[] jpegData = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        } catch (Exception e) {
            Log.e(TAG, "nv21ToBitmap failed", e);
            return null;
        }
    }

    // JNI
    private static native boolean nativeInitFaceDetector(String modelPath);
    private static native void nativeReleaseFaceDetector();
    private static native float[] nativeDetectFaces(byte[] nv21, int width, int height);
    private static native byte[] nativeDenoiseSingle(byte[] nv21, int width, int height);
    private static native byte[] nativeDenoiseMulti(byte[][] frames, int count, int width, int height);

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