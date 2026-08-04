/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2all;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A {@link Runnable} that saves a {@link Image} (JPEG or RAW_SENSOR) to a {@link File} and
 * updates {@link android.provider.MediaStore} with the resulting file.
 *
 * <p>Instances are constructed through {@link ImageSaverBuilder} as the necessary image and
 * result information becomes available.</p>
 */
public class ImageSaver implements Runnable {

    // Log tag for the image-saver task.
    private static final String TAG = "ImageSaver";

    // The captured image buffer to be saved.
    private final Image mImage;
    // Destination file the captured image will be written to.
    private final File mFile;
    // Capture metadata needed to build the output filename / orientation / DNG.
    private final CaptureResult mCaptureResult;
    // Camera characteristics required to write RAW (DNG) files.
    private final CameraCharacteristics mCharacteristics;
    // Context used to register the saved file with the media scanner.
    private final Context mContext;
    private final RefCountedAutoCloseable<android.media.ImageReader> mReader;

    private ImageSaver(Image image, File file, CaptureResult result,
                       CameraCharacteristics characteristics, Context context,
                       RefCountedAutoCloseable<android.media.ImageReader> reader) {
        mImage = image;
        mFile = file;
        mCaptureResult = result;
        mCharacteristics = characteristics;
        mContext = context;
        mReader = reader;
    }

    @Override
    public void run() {
        boolean success = false;
        try {
            int format = mImage.getFormat();
            switch (format) {
                case ImageFormat.JPEG: {
                    ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(mFile);
                        output.write(bytes);
                        success = true;
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        mImage.close();
                        closeOutput(output);
                    }
                    break;
                }
                case ImageFormat.RAW_SENSOR: {
                    DngCreator dngCreator = new DngCreator(mCharacteristics, mCaptureResult);
                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(mFile);
                        dngCreator.writeImage(output, mImage);
                        success = true;
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (Exception e) {
                        // DngCreator/writeImage may throw non-IO exceptions (e.g.
                        // IllegalArgumentException on certain devices, or OOM on large RAW
                        // frames). Catch everything so this background thread never crashes the
                        // whole process.
                        Log.e(TAG, "Failed to write DNG: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        mImage.close();
                        closeOutput(output);
                    }
                    break;
                }
                default: {
                    Log.e(TAG, "Cannot save image, unexpected image format:" + format);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error while saving image: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Decrement reference count to allow the ImageReader to be closed to free up
            // resources. Guard against any exception thrown by the ref-counted close so this
            // runnable never propagates an uncaught exception off the background thread.
            try {
                mReader.close();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing ImageReader reference: " + e.getMessage());
            }
        }

        // If saving succeeded, update MediaStore so the file is visible in the gallery.
        if (success) {
            MediaScannerConnection.scanFile(mContext, new String[]{mFile.getPath()},
                    null, new MediaScannerConnection.MediaScannerConnectionClient() {
                        @Override
                        public void onMediaScannerConnected() {
                            // Do nothing
                        }

                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            Log.i(TAG, "Scanned " + path + ":");
                            Log.i(TAG, "-> uri=" + uri);
                        }
                    });
        }
    }

    /**
     * Builder for constructing {@link ImageSaver} instances.
     */
    public static class ImageSaverBuilder {
        // The captured image buffer to be saved.
        private Image mImage;
        // Destination file the captured image will be written to.
        private File mFile;
        // Capture metadata needed to build the output filename / orientation / DNG.
        private CaptureResult mCaptureResult;
        // Camera characteristics required to write RAW (DNG) files.
        private CameraCharacteristics mCharacteristics;
        // Context used to register the saved file with the media scanner.
        private Context mContext;
        // Reference-counted reader owning the image; released once the image is consumed.
        private RefCountedAutoCloseable<android.media.ImageReader> mReader;

        public ImageSaverBuilder(final Context context) {
            mContext = context;
        }

        public synchronized ImageSaverBuilder setRefCountedReader(
                RefCountedAutoCloseable<android.media.ImageReader> reader) {
            if (reader == null) throw new NullPointerException();
            mReader = reader;
            return this;
        }

        public synchronized ImageSaverBuilder setImage(final Image image) {
            if (image == null) throw new NullPointerException();
            mImage = image;
            return this;
        }

        public synchronized ImageSaverBuilder setFile(final File file) {
            if (file == null) throw new NullPointerException();
            mFile = file;
            return this;
        }

        public synchronized ImageSaverBuilder setResult(final CaptureResult result) {
            if (result == null) throw new NullPointerException();
            mCaptureResult = result;
            return this;
        }

        public synchronized ImageSaverBuilder setCharacteristics(
                final CameraCharacteristics characteristics) {
            if (characteristics == null) throw new NullPointerException();
            mCharacteristics = characteristics;
            return this;
        }

        public synchronized ImageSaver buildIfComplete() {
            if (!isComplete()) {
                return null;
            }
            return new ImageSaver(mImage, mFile, mCaptureResult, mCharacteristics, mContext,
                    mReader);
        }

        public synchronized String getSaveLocation() {
            return (mFile == null) ? "Unknown" : mFile.toString();
        }

        private boolean isComplete() {
            return mImage != null && mFile != null && mCaptureResult != null
                    && mCharacteristics != null;
        }
    }

    // Execute the saver on a background thread pool.
    public static void execute(ImageSaver saver) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(saver);
    }

    private static void closeOutput(OutputStream outputStream) {
        if (null != outputStream) {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
