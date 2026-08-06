/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.integration.hdr;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Java-based merger for an HDR viewfinder.
 *
 * <p>The original sample used a RenderScript kernel (hdr_merge.rs) to fuse the latest two YUV
 * frames and convert YUV->RGB. Because the RenderScript compiler was removed in newer Android
 * Gradle Plugin versions, this class reimplements the whole pipeline in plain Java: the camera
 * writes frames into two {@link ImageReader} surfaces (HDR / normal), a background thread reads
 * the YUV_420_888 planes, fuses/splits them, converts YUV->RGB, and draws the result onto the
 * output {@link Surface} (the preview SurfaceView) using a {@link android.graphics.Canvas}.</p>
 */
public class ViewfinderProcessor {

    private ImageReader mInputHdrReader;
    private ImageReader mInputNormalReader;

    private Handler mProcessingHandler;

    public ProcessingTask mHdrTask;
    public ProcessingTask mNormalTask;

    private int mMode;

    public final static int MODE_NORMAL = 0;
    public final static int MODE_HDR = 2;

    // Scratch buffers reused across frames to avoid per-frame allocations.
    private ByteBuffer mHdrYuv;
    private ByteBuffer mNormalYuv;
    private byte[] mPrevRgb; // previous frame RGBA_8888
    private byte[] mCurRgb;  // current frame RGBA_8888
    private byte[] mOutRgb;  // output RGBA_8888

    private int mFrameCounter = 0;
    private int mWidth;
    private int mHeight;

    public ViewfinderProcessor(Size dimensions) {
        mWidth = dimensions.getWidth();
        mHeight = dimensions.getHeight();
        int yuvSize = mWidth * mHeight * 3 / 2; // YUV_420_888
        mHdrYuv = ByteBuffer.allocate(yuvSize);
        mNormalYuv = ByteBuffer.allocate(yuvSize);
        mPrevRgb = new byte[mWidth * mHeight * 4];
        mCurRgb = new byte[mWidth * mHeight * 4];
        mOutRgb = new byte[mWidth * mHeight * 4];

        mInputHdrReader = ImageReader.newInstance(mWidth, mHeight, ImageFormat.YUV_420_888, 3);
        mInputNormalReader = ImageReader.newInstance(mWidth, mHeight, ImageFormat.YUV_420_888, 3);

        HandlerThread processingThread = new HandlerThread("ViewfinderProcessor");
        processingThread.start();
        mProcessingHandler = new Handler(processingThread.getLooper());

        mHdrTask = new ProcessingTask(mInputHdrReader, mWidth / 2, true);
        mNormalTask = new ProcessingTask(mInputNormalReader, 0, false);

        setRenderMode(MODE_NORMAL);
    }

    public Surface getInputHdrSurface() {
        return mInputHdrReader.getSurface();
    }

    public Surface getInputNormalSurface() {
        return mInputNormalReader.getSurface();
    }

    public void setOutputSurface(Surface output) {
        mHdrTask.setOutputSurface(output);
        mNormalTask.setOutputSurface(output);
    }

    public void setRenderMode(int mode) {
        mMode = mode;
    }

    /**
     * Equivalent of the original RenderScript {@code mergeHdrFrames} kernel, executed in Java.
     */
    private void mergeHdrFrames(byte[] input, byte[] prevRgb, byte[] outRgb,
            int cutX, int doMerge, int frameCounter) {
        int w = mWidth;
        int h = mHeight;
        int ySize = w * h;
        int uvSize = ySize / 4;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int yIdx = y * w + x;
                int uvIdx = (y / 2) * (w / 2) + (x / 2);
                int Y = input[yIdx] & 0xFF;
                int U = input[ySize + uvIdx] & 0xFF;
                int V = input[ySize + uvSize + uvIdx] & 0xFF;

                int pOff = yIdx * 4;
                int pr = prevRgb[pOff] & 0xFF;
                int pg = prevRgb[pOff + 1] & 0xFF;
                int pb = prevRgb[pOff + 2] & 0xFF;

                int mr, mg, mb;
                if (doMerge == 1) {
                    mr = (Y / 2 + pr / 2);
                    mg = (U / 2 + pg / 2);
                    mb = (V / 2 + pb / 2);
                } else if (cutX > 0) {
                    boolean useCurrent = ((x < cutX) ^ ((frameCounter & 0x1) != 0));
                    if (useCurrent) {
                        mr = Y; mg = U; mb = V;
                    } else {
                        mr = pr; mg = pg; mb = pb;
                    }
                } else {
                    mr = Y; mg = U; mb = V;
                }

                int r = clamp(mr + mb * 1436 / 1024 - 179);
                int g = clamp(mr - mg * 46549 / 131072 + 44 - mb * 93604 / 131072 + 91);
                int b = clamp(mr + mg * 1814 / 1024 - 227);

                int o = yIdx * 4;
                outRgb[o] = (byte) r;
                outRgb[o + 1] = (byte) g;
                outRgb[o + 2] = (byte) b;
                outRgb[o + 3] = (byte) 255;

                prevRgb[pOff] = (byte) Y;
                prevRgb[pOff + 1] = (byte) U;
                prevRgb[pOff + 2] = (byte) V;
                prevRgb[pOff + 3] = (byte) 255;
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * Copy the YUV_420_888 planes of an Image into a compact planar buffer.
     */
    private static void imageToYuv(Image image, ByteBuffer dst) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        int ySize = width * height;
        int uvSize = ySize / 4;

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        dst.rewind();
        // Y plane (row stride may include padding)
        copyPlane(yBuf, planes[0].getRowStride(), width, height, dst);
        // U plane (pixel stride is 1 for YUV_420_888 with this layout)
        copyPlane(uBuf, planes[1].getRowStride(), width / 2, height / 2, dst);
        // V plane
        copyPlane(vBuf, planes[2].getRowStride(), width / 2, height / 2, dst);
        dst.rewind();
    }

    private static void copyPlane(ByteBuffer src, int rowStride, int w, int h, ByteBuffer dst) {
        if (rowStride == w) {
            dst.put(src);
        } else {
            for (int row = 0; row < h; row++) {
                int len = Math.min(w, src.remaining());
                dst.put(src.array(), src.arrayOffset() + src.position(), len);
                src.position(src.position() + rowStride);
            }
        }
    }

    /**
     * Simple class to keep track of incoming frame count,
     * and to process the newest one in the processing thread
     */
    class ProcessingTask implements ImageReader.OnImageAvailableListener {
        private int mPendingFrames = 0;
        private int mFrameCounter = 0;
        private int mCutPointX;
        private boolean mCheckMerge;

        private ImageReader mInputReader;
        private ByteBuffer mYuvBuffer;
        private Surface mOutputSurface;

        public ProcessingTask(ImageReader input, int cutPointX, boolean checkMerge) {
            mInputReader = input;
            mCutPointX = cutPointX;
            mCheckMerge = checkMerge;
            mYuvBuffer = (input == mInputHdrReader) ? mHdrYuv : mNormalYuv;
            mInputReader.setOnImageAvailableListener(this, mProcessingHandler);
        }

        public void setOutputSurface(Surface surface) {
            mOutputSurface = surface;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            synchronized (this) {
                mPendingFrames++;
            }
            mProcessingHandler.post(this::processLatest);
        }

        private void processLatest() {
            int pendingFrames;
            synchronized (this) {
                pendingFrames = mPendingFrames;
                mPendingFrames = 0;
            }

            Image image = null;
            try {
                // Get to newest input
                for (int i = 0; i < pendingFrames; i++) {
                    Image img = mInputReader.acquireLatestImage();
                    if (img != null) {
                        if (image != null) image.close();
                        image = img;
                    }
                }
                if (image == null) return;

                imageToYuv(image, mYuvBuffer);

                int doMerge = (mCheckMerge && mMode == MODE_HDR) ? 1 : 0;
                synchronized (ViewfinderProcessor.this) {
                    mergeHdrFrames(mYuvBuffer.array(), mPrevRgb, mOutRgb, mCutPointX, doMerge,
                            mFrameCounter++);
                }

                if (mOutputSurface != null) {
                    drawRgbToSurface(mOutputSurface, mOutRgb);
                }
            } finally {
                if (image != null) image.close();
            }
        }

        private void drawRgbToSurface(Surface surface, byte[] rgb) {
            android.graphics.Canvas canvas = null;
            try {
                canvas = surface.lockCanvas(null);
                if (canvas == null) return;
                int w = mWidth;
                int h = mHeight;
                int[] pixels = new int[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    int o = i * 4;
                    int r = rgb[o] & 0xFF;
                    int g = rgb[o + 1] & 0xFF;
                    int b = rgb[o + 2] & 0xFF;
                    pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
                canvas.drawBitmap(bmp, 0, 0, null);
                bmp.recycle();
            } catch (Exception e) {
                // Surface may not be ready; ignore.
            } finally {
                if (canvas != null) surface.unlockCanvasAndPost(canvas);
            }
        }
    }
}
