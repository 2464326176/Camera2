/*
 * Copyright 2026 (pseudo algorithm integration example).
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

import android.graphics.Bitmap;

import java.util.List;

/**
 * Pseudo algorithm interface collection for camera features.
 *
 * <p>This file defines only the <b>interface contracts and mock implementations</b>;
 * no real algorithm is integrated. Every {@code process*} method returns simulated data
 * (e.g. a solid-color / placeholder Bitmap, or a timestamp-watermark log) so that hook
 * points can be reserved in preview, capture and recording without a real backend. The
 * call timing, data format and threading model are documented per interface.</p>
 *
 * <p>To integrate a real algorithm, replace the corresponding {@code Mock...Algorithm}
 * implementation (or add a new implementation of the interfaces) without touching callers.</p>
 */
public final class CameraAlgorithm {

    private CameraAlgorithm() { /* Utility class, do not instantiate. */ }

    /**
     * Single-frame algorithm interface.
     *
     * <p>Call timing: per preview frame callback. Processing finishes before the UI render;
     * input is the current preview frame in YUV/NV21, output is a simulated processed frame
     * (e.g. a Bitmap with a mock filter effect).</p>
     *
     * <p>Input format: NV21 byte array (width {@code w}, height {@code h}, size = w*h*3/2).
     * Output format: {@link Bitmap} (processed preview frame; can carry filter/beauty, etc.).</p>
     *
     * <p>Threading: decided by the caller, normally on the camera background thread
     * ({@code mBackgroundHandler}) so it completes synchronously before the UI render
     * (TextureView update).</p>
     */
    public interface SingleFrameAlgorithm {
        /**
         * Process one preview frame.
         *
         * @param nv21   raw preview frame in NV21 (YUV420SP)
         * @param width  frame width
         * @param height frame height
         * @return simulated processed frame (mock returns a placeholder Bitmap)
         */
        Bitmap processFrame(byte[] nv21, int width, int height);
    }

    /**
     * Multi-frame (burst / batch) algorithm interface.
     *
     * <p>Call timing: post-capture processing stage ({@code onPictureTaken} or after
     * {@link ImageSaver} flushes to disk). Input is a list of burst frames, output is a
     * simulated batch result (e.g. a synthesized HDR preview Bitmap).</p>
     *
     * <p>Input format: a list of {@link Bitmap} (same resolution, same scene burst frames).
     * Output format: {@link Bitmap} (simulated composite, e.g. an HDR preview).</p>
     *
     * <p>Threading: MUST run on a <strong>background thread</strong> to avoid blocking the
     * main thread; typically driven by {@code AsyncTask.THREAD_POOL_EXECUTOR} or
     * {@code mBackgroundHandler}.</p>
     */
    public interface MultiFrameAlgorithm {
        /**
         * Process a batch of burst frames.
         *
         * @param frames burst frames captured in order
         * @return simulated batch result (mock returns a placeholder Bitmap)
         */
        Bitmap processBurst(List<Bitmap> frames);
    }

    /**
     * Video-frame algorithm interface.
     *
     * <p>Call timing: during video playback/encoding, at {@code SurfaceTexture#onFrameAvailable}
     * or after MediaCodec decodes a frame. Input is raw video frame data, output is a simulated
     * processed frame (e.g. time watermark or mock beauty effect).</p>
     *
     * <p>Input format: NV21 byte array + timestamp (microseconds).
     * Output format: {@link Bitmap} (processed video frame with watermark/beauty).</p>
     *
     * <p>Threading: runs on a <strong>dedicated thread</strong> (before video render, e.g. before
     * the OpenGL texture update) so it never blocks the main thread or the render pipeline.</p>
     */
    public interface VideoFrameAlgorithm {
        /**
         * Process one video frame.
         *
         * @param nv21        raw video frame in NV21
         * @param width       frame width
         * @param height      frame height
         * @param timestampUs frame timestamp in microseconds
         * @return simulated processed frame (mock returns a placeholder Bitmap)
         */
        Bitmap processVideoFrame(byte[] nv21, int width, int height, long timestampUs);
    }

    // ------------------------------------------------------------------
    // Mock implementations: return simulated data so hooks can be verified
    // without a real algorithm.
    // ------------------------------------------------------------------

    /**
     * Mock single-frame algorithm: builds a placeholder Bitmap to simulate a "processed" preview
     * frame. It does not read actual nv21 pixels; it only demonstrates the contract and data flow.
     */
    public static class MockSingleFrameAlgorithm implements SingleFrameAlgorithm {
        @Override
        public Bitmap processFrame(byte[] nv21, int width, int height) {
            // Mock: return a solid-color placeholder Bitmap to simulate a filtered frame.
            // A real algorithm would parse nv21 here, process the image and return the result.
            android.util.Log.d("CameraAlgorithm",
                    "SingleFrameAlgorithm.processFrame mock: " + width + "x" + height);
            return Bitmap.createBitmap(
                    Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        }
    }

    /**
     * Mock multi-frame algorithm: returns a placeholder Bitmap to simulate an HDR composite.
     * It does not read actual frame pixels; it only demonstrates the contract.
     */
    public static class MockMultiFrameAlgorithm implements MultiFrameAlgorithm {
        @Override
        public Bitmap processBurst(List<Bitmap> frames) {
            android.util.Log.d("CameraAlgorithm",
                    "MultiFrameAlgorithm.processBurst mock: frames=" + frames.size());
            // Mock: return a placeholder Bitmap sized from the first frame, simulating an HDR preview.
            int w = frames.isEmpty() ? 1 : frames.get(0).getWidth();
            int h = frames.isEmpty() ? 1 : frames.get(0).getHeight();
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
    }

    /**
     * Mock video-frame algorithm: returns a placeholder Bitmap to simulate a frame with a
     * time watermark applied.
     */
    public static class MockVideoFrameAlgorithm implements VideoFrameAlgorithm {
        @Override
        public Bitmap processVideoFrame(byte[] nv21, int width, int height, long timestampUs) {
            android.util.Log.d("CameraAlgorithm",
                    "VideoFrameAlgorithm.processVideoFrame mock: " + width + "x" + height
                            + " ts=" + timestampUs);
            // Mock: return a placeholder Bitmap simulating a watermarked/beautified video frame.
            return Bitmap.createBitmap(
                    Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        }
    }
}
