/*
 * Pseudo camera algorithm interface layer.
 *
 * This is NOT a real algorithm implementation - it only defines the data-flow
 * contracts (input -> output) for every capture path in the app, so a real
 * algorithm can be dropped in later by replacing the Mock* classes.
 *
 * Five stages are modeled to match the UI:
 *   - PREVIEW     : per-frame algorithm on the live preview stream
 *   - STILL_SINGLE: single-frame algorithm on a captured JPEG
 *   - STILL_HDR   : multi-frame HDR (exposure bracketing [-4,0,+4,+8] EV)
 *   - STILL_DENOISE: multi-frame noise reduction (N identical frames averaged)
 *   - VIDEO       : per-frame algorithm on the recorded video stream
 *
 * Calling convention:
 *   - PREVIEW / VIDEO run synchronously inside the frame callback (do not block).
 *   - STILL_SINGLE runs right after the JPEG is flushed to disk (background thread).
 *   - STILL_HDR / STILL_DENOISE run after the whole burst is captured + saved
 *     (background thread, once all frames are collected).
 */

package com.example.android.camera2all;

import android.graphics.Bitmap;
import android.media.Image;

import java.util.List;

public final class CameraAlgorithm {

    private CameraAlgorithm() {
    }

    // ------------------------------------------------------------------
    // Stage / mode enums
    // ------------------------------------------------------------------

    /** Where in the capture pipeline the algorithm is invoked. */
    public enum AlgorithmStage {
        PREVIEW,
        STILL_SINGLE,
        STILL_HDR,
        STILL_DENOISE,
        VIDEO
    }

    /** Still-capture capture mode selected by the user. */
    public enum CaptureMode {
        /** One JPEG, processed by the single-frame still algorithm. */
        SINGLE(AlgorithmStage.STILL_SINGLE, 1),
        /** Exposure-bracketing burst, merged by the HDR algorithm. */
        HDR(AlgorithmStage.STILL_HDR, HDR_EXPOSURE_VALUES.length),
        /** Identical-frame burst, merged by the denoise algorithm. */
        DENOISE(AlgorithmStage.STILL_DENOISE, DENOISE_FRAME_COUNT);

        public final AlgorithmStage stage;
        /** Number of frames that must be collected before the algorithm runs. */
        public final int frameCount;

        CaptureMode(AlgorithmStage stage, int frameCount) {
            this.stage = stage;
            this.frameCount = frameCount;
        }
    }

    // ------------------------------------------------------------------
    // Capture-mode constants (expose the exact recipes you asked for)
    // ------------------------------------------------------------------

    /** HDR exposure-compensation recipe: [+4, 0, -4, -8] EV, in capture order. */
    public static final int[] HDR_EXPOSURE_VALUES = {4, 0, -4, -8};

    /** Denoise burst size: 6 identical-exposure frames. */
    public static final int DENOISE_FRAME_COUNT = 6;

    // ------------------------------------------------------------------
    // Algorithm interfaces (data-flow contracts)
    // ------------------------------------------------------------------

    /**
     * Live preview frame algorithm. Invoked per preview frame, on the frame-callback
     * thread. Must be cheap; returning null means "leave the frame untouched".
     *
     * @param nv21   the YUV420 (NV21) preview frame
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param tsNs   frame timestamp in nanoseconds
     * @return an optional processed Bitmap (same dimensions), or null to skip
     */
    public interface PreviewAlgorithm {
        Bitmap processFrame(byte[] nv21, int width, int height, long tsNs);
    }

    /**
     * Single captured still algorithm. Invoked once, after the JPEG is saved to disk.
     *
     * @param jpeg the in-memory JPEG bytes of the captured photo
     * @return a processed Bitmap (placeholder in mock), or null
     */
    public interface StillSingleAlgorithm {
        Bitmap processStill(byte[] jpeg);
    }

    /**
     * HDR multi-frame algorithm. Input is the list of burst JPEG frames captured with the
     * exposure recipe {@link #HDR_EXPOSURE_VALUES}; output is the merged result.
     *
     * @param frames JPEG bytes of each bracketed frame, ordered like HDR_EXPOSURE_VALUES
     * @return the merged HDR Bitmap (placeholder in mock), or null
     */
    public interface StillHdrAlgorithm {
        Bitmap processHdr(List<byte[]> frames);
    }

    /**
     * Denoise multi-frame algorithm. Input is N identical-exposure JPEG frames
     * ({@link #DENOISE_FRAME_COUNT}); output is the noise-reduced result.
     *
     * @param frames JPEG bytes of each captured frame
     * @return the denoised Bitmap (placeholder in mock), or null
     */
    public interface StillDenoiseAlgorithm {
        Bitmap processDenoise(List<byte[]> frames);
    }

    /**
     * Video frame algorithm. Invoked per recorded frame, on the recording thread.
     * Must be cheap; returning null means "leave the frame untouched".
     *
     * @param nv21   the YUV420 (NV21) video frame
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param tsNs   frame timestamp in nanoseconds
     * @return an optional processed Bitmap (same dimensions), or null to skip
     */
    public interface VideoAlgorithm {
        Bitmap processFrame(byte[] nv21, int width, int height, long tsNs);
    }

    // ------------------------------------------------------------------
    // Mock implementations (swap these out for real algorithms later)
    // ------------------------------------------------------------------

    public static class MockPreviewAlgorithm implements PreviewAlgorithm {
        @Override
        public Bitmap processFrame(byte[] nv21, int width, int height, long tsNs) {
            // TODO: real preview algorithm. Returning null keeps the pipeline unchanged.
            return null;
        }
    }

    public static class MockStillSingleAlgorithm implements StillSingleAlgorithm {
        @Override
        public Bitmap processStill(byte[] jpeg) {
            // TODO: real single-frame still algorithm.
            return null;
        }
    }

    public static class MockStillHdrAlgorithm implements StillHdrAlgorithm {
        @Override
        public Bitmap processHdr(List<byte[]> frames) {
            // TODO: real HDR merge of frames captured with HDR_EXPOSURE_VALUES.
            return null;
        }
    }

    public static class MockStillDenoiseAlgorithm implements StillDenoiseAlgorithm {
        @Override
        public Bitmap processDenoise(List<byte[]> frames) {
            // TODO: real multi-frame denoise / averaging of DENOISE_FRAME_COUNT frames.
            return null;
        }
    }

    public static class MockVideoAlgorithm implements VideoAlgorithm {
        @Override
        public Bitmap processFrame(byte[] nv21, int width, int height, long tsNs) {
            // TODO: real video-frame algorithm (e.g. stabilization / segmentation).
            return null;
        }
    }
}
