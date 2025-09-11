package com.example.android.camera2VisualProcess;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.Image;
import android.util.Log;

import com.arcsoft.imageutil.ArcSoftImageFormat;
import com.arcsoft.imageutil.ArcSoftImageUtil;
import com.arcsoft.imageutil.ArcSoftImageUtilError;
import com.arcsoft.imageutil.ArcSoftMirrorOrient;
import com.arcsoft.imageutil.ArcSoftRotateDegree;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class ArcSoftImageProcessor {
    private static final String TAG = "ArcSoftProcessor";
    private static final String outPutImageDir = "/sdcard/DCIM/Camera/";
    private static final String outPutImageDir1 = "/storage/emulated/0/Android/data/com.example.android.camera2VisualProcess/files/";

    // 只处理这些常用格式
    private static final List<ArcSoftImageFormat> SUPPORTED_FORMATS = Arrays.asList(
            ArcSoftImageFormat.BGR24,
            ArcSoftImageFormat.NV21,
            ArcSoftImageFormat.NV12,
            ArcSoftImageFormat.I420,
            ArcSoftImageFormat.YV12,
            ArcSoftImageFormat.YUYV,
            ArcSoftImageFormat.GRAY
    );

    public interface ProcessingCallback {
        void onProcessingStarted();
        void onProcessingCompleted();
        void onProcessingFailed(String error);
    }

    public static void processImage(Image image) {
        new Thread(() -> {
            Image mImage = image;
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Log.d(TAG, "processImage image format:" + image.getFormat());
            saveImageData(bytes, outPutImageDir, "test.jpg");


//            for (ArcSoftImageFormat format : SUPPORTED_FORMATS) {
//                doMirrorProcess(bytes, image.getWidth(), image.getHeight(), format, ArcSoftMirrorOrient.HORIZONTAL);
//            }
//            FileOutputStream output = null;
//            File outPutImageFile = new File(outPutImageDir + "test.jpg");
//            try {
//                output = new FileOutputStream(outPutImageFile);
//                output.write(bytes);
//                Log.i(TAG, "write png success");
//            } catch (Exception e) {
//                e.printStackTrace();
//            } finally {
//                if (null != output) {
//                    try {
//                        output.close();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//            Log.e(TAG, "image format:" + image.getFormat());
//            for (ArcSoftImageFormat format : SUPPORTED_FORMATS) {
//                doMirrorProcess(bytes, image.getWidth(), image.getHeight(), format, ArcSoftMirrorOrient.HORIZONTAL);
//            }
        }).start();
    }

    public static void processImage(byte[] bytes) {
//        new Thread(() -> {
//            Image mImage = image;
//            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
//            byte[] bytes = new byte[buffer.remaining()];
//            buffer.get(bytes);

            saveImageData(bytes, outPutImageDir, "test.jpg");

//            FileOutputStream output = null;
//            File outPutImageFile = new File(outPutImageDir + "test.jpg");
//            try {
//                output = new FileOutputStream(outPutImageFile);
//                output.write(bytes);
//                Log.i(TAG, "write png success");
//            } catch (Exception e) {
//                e.printStackTrace();
//            } finally {
//                if (null != output) {
//                    try {
//                        output.close();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//            Log.e(TAG, "image format:" + image.getFormat());
//            for (ArcSoftImageFormat format : SUPPORTED_FORMATS) {
//                doMirrorProcess(bytes, image.getWidth(), image.getHeight(), format, ArcSoftMirrorOrient.HORIZONTAL);
//            }
//        }).start();

        //            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
//            byte[] bytes = new byte[buffer.remaining()];
//            buffer.get(bytes);
//        ArcSoftImageProcessor.processImage(mImage);
//            ArcSoftImageProcessor.saveImageData(bytes, "/sdcard/DCIM/Camera/", "1.jpg");
//            FileOutputStream output = null;
//            try {
//                output = new FileOutputStream(mFile);
//                output.write(bytes);
//                ArcSoftImageProcessor.processImage(mImage);
//            } catch (Exception e) {
//                e.printStackTrace();
//            } finally {
//                mImage.close();
//                if (null != output) {
//                    try {
//                        output.close();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
    }

    public static void processImage(String imagePath, ProcessingCallback callback) {
        new Thread(() -> {
            if (callback != null) callback.onProcessingStarted();

            try {
                // 读取图片文件
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2; // 缩小图片以减少内存占用
                Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);

                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode image: " + imagePath);
                    if (callback != null) callback.onProcessingFailed("Failed to decode image");
                    return;
                }

                // 获取输出目录
                File imageFile = new File(imagePath);
                String outputDir = imageFile.getParent();

                // 处理图像
                processImage(bitmap, outputDir, imageFile.getName());

                if (callback != null) callback.onProcessingCompleted();
            } catch (Exception e) {
                Log.e(TAG, "Image processing error", e);
                if (callback != null) callback.onProcessingFailed(e.getMessage());
            }
        }).start();
    }

    private static void processImage(Bitmap bitmap, String outputDir, String originalFilename) {
        Log.i(TAG, "Processing image: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        // 创建输出目录
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
            Log.e(TAG, "Failed to create output directory");
            return;
        }

        // 只处理支持的格式
        for (ArcSoftImageFormat format : SUPPORTED_FORMATS) {
            try {
                // 创建图像数据缓冲区
                byte[] data = ArcSoftImageUtil.createImageData(
                        bitmap.getWidth(), bitmap.getHeight(), format);

                // 转换Bitmap到指定格式
                int code = ArcSoftImageUtil.bitmapToImageData(bitmap, data, format);
                if (code != ArcSoftImageUtilError.CODE_SUCCESS) {
                    Log.w(TAG, format + " conversion failed: " + code);
                    continue;
                }

                // 保存原始格式
                String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
                String formatExt = format.name().toLowerCase();

                // 原始格式
                saveImageData(data, outputDir, baseName + "_origin." + formatExt);

                // 水平镜像
                byte[] mirrorData = new byte[data.length];
                code = ArcSoftImageUtil.mirrorImage(
                        data, mirrorData,
                        bitmap.getWidth(), bitmap.getHeight(),
                        ArcSoftMirrorOrient.HORIZONTAL, format);
                if (code == ArcSoftImageUtilError.CODE_SUCCESS) {
                    saveImageData(mirrorData, outputDir, baseName + "_mirror." + formatExt);
                }

                // 旋转90度
                byte[] rotateData = ArcSoftImageUtil.createImageData(
                        bitmap.getHeight(), bitmap.getWidth(), format);
                code = ArcSoftImageUtil.rotateImage(
                        data, rotateData,
                        bitmap.getWidth(), bitmap.getHeight(),
                        ArcSoftRotateDegree.DEGREE_90, format);
                if (code == ArcSoftImageUtilError.CODE_SUCCESS) {
                    saveImageData(rotateData, outputDir, baseName + "_rotate90." + formatExt);
                }

                // 裁剪图像 (中心区域)
                Rect cropArea = new Rect(
                        bitmap.getWidth()/4,
                        bitmap.getHeight()/4,
                        3*bitmap.getWidth()/4,
                        3*bitmap.getHeight()/4);
                byte[] cropData = ArcSoftImageUtil.createImageData(
                        cropArea.width(), cropArea.height(), format);
                code = ArcSoftImageUtil.cropImage(
                        data, cropData,
                        bitmap.getWidth(), bitmap.getHeight(),
                        cropArea, format);
                if (code == ArcSoftImageUtilError.CODE_SUCCESS) {
                    saveImageData(cropData, outputDir, baseName + "_crop." + formatExt);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing " + format, e);
            }
        }
    }

    private static void doRotateProcess(byte[] srcData, int width, int height,
                                               ArcSoftImageFormat format, ArcSoftRotateDegree degree) {
        try {
            byte[] rotateData = ArcSoftImageUtil.createImageData(
                    width, height, format);
            int code = ArcSoftImageUtil.rotateImage(
                    srcData, rotateData, width, height,
                    degree, format);

            if (code == ArcSoftImageUtilError.CODE_SUCCESS) {
                saveImageData(rotateData, outPutImageDir, (new Date()).getTime() + "_rotate." + format.name().toLowerCase());
                Log.i(TAG, "Rotate operation success with code: " + code);
            } else {
                Log.w(TAG, "Rotate operation failed with code: " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error performing Rotate operation", e);
        }
    }

    private static void doMirrorProcess(byte[] srcData, int width, int height,
                                        ArcSoftImageFormat format, ArcSoftMirrorOrient orient) {
        try {
            byte[] mirrorData = new byte[srcData.length];
            int code = ArcSoftImageUtil.mirrorImage(
                    srcData, mirrorData,
                    width, height,
                    orient, format);
            if (code == ArcSoftImageUtilError.CODE_SUCCESS) {
                saveImageData(mirrorData, outPutImageDir, (new Date()).getTime() + "_rotate." + format.name().toLowerCase());
                Log.i(TAG, "Mirror operation success with code: " + code);
            } else {
                Log.w(TAG, "Mirror operation failed with code: " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error performing mirror operation", e);
        }
    }

    public static void saveImageData(byte[] data, String outputDir, String filename) {
        File file = new File(outputDir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            Log.d(TAG, "Saved: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving " + filename, e);
        }
    }
}