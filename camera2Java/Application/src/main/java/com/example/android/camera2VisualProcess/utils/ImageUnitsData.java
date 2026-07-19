package com.example.android.camera2VisualProcess.utils;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

// 图像数据封装类和处理工具类
public class

{
    private static final String TAG = "CameraImageUtils";

    // 图像数据封装内部类
    public static class ImageData {
        private final ByteBuffer data;
        private final int format;
        private final int width;
        private final int height;

        public ImageData(ByteBuffer data, int format, int width, int height) {
            this.data = data;
            this.format = format;
            this.width = width;
            this.height = height;
        }

        // 添加getter方法
        public ByteBuffer getData() {
            return data;
        }

        public int getFormat() {
            return format;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    // 将方法改为静态方法，供其他类调用
    public static ImageData copyImageUnitsData(Image image) {
        if (image == null) return null;

        try {
            int format = image.getFormat();
            int width = image.getWidth();
            int height = image.getHeight();

            switch (format) {
                case ImageFormat.JPEG:
                    Image.Plane[] planes = image.getPlanes();
                    if (planes.length > 0) {
                        ByteBuffer buffer = planes[0].getBuffer();
                        ByteBuffer clonedBuffer = ByteBuffer.allocate(buffer.remaining());
                        clonedBuffer.put(buffer);
                        clonedBuffer.flip();
                        return new ImageData(clonedBuffer, format, width, height);
                    }
                    break;

                case ImageFormat.YUV_420_888:
                    return copyYuv420Data(image);

                default:
                    Log.w(TAG, "Unsupported image format: " + format);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying image data", e);
        }

        return null;
    }

    // 复制YUV_420_888数据的静态方法
    public static ImageData copyYuv420Data(Image image) {
        try {
            final Image.Plane[] planes = image.getPlanes();

            // 计算总大小
            int totalSize = 0;
            for (Image.Plane plane : planes) {
                totalSize += plane.getBuffer().remaining();
            }

            // 创建缓冲区存储所有平面数据
            ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);

            // 复制每个平面的数据
            for (Image.Plane plane : planes) {
                ByteBuffer buffer = plane.getBuffer();
                combinedBuffer.put(buffer);
            }

            combinedBuffer.flip();
            return new ImageData(combinedBuffer, image.getFormat(),
                    image.getWidth(), image.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "Error copying YUV_420_888 data", e);
            return null;
        }
    }
}
