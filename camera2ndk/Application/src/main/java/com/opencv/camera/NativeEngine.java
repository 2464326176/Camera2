package com.opencv.camera;

import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;

/**
 * JNI bridge for the C++ image processing engine.
 * All core image processing (face detection, denoising, sharpening, JPEG encoding, etc.)
 * is done in the C++ layer. The Java layer is only responsible for UI business logic
 * and Camera2 API lifecycle management.
 *
 * Frames are passed to the C++ layer via HardwareBuffer for zero-copy.
 * Fallback path (API < 26) uses DirectByteBuffer.
 */
public class NativeEngine {

    private static final String TAG = "NativeEngine";

    private static volatile NativeEngine sInstance;

    static {
        try {
            System.loadLibrary("opencv_java4");
            System.loadLibrary("camera_engine");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private NativeEngine() {}

    public static NativeEngine getInstance() {
        if (sInstance == null) {
            synchronized (NativeEngine.class) {
                if (sInstance == null) {
                    sInstance = new NativeEngine();
                }
            }
        }
        return sInstance;
    }

    // ==================== Engine lifecycle ====================

    /** Initialize the engine (load models, allocate resources). Returns engine handle. */
    public native long nativeCreateEngine(String modelDir);

    /** Destroy the engine. */
    public native void nativeDestroyEngine(long engineHandle);

    // ==================== Pipeline creation ====================

    /** Create a preview pipeline. */
    public native long nativeCreatePreviewPipeline(long engineHandle,
            int width, int height, int format, int maxFaces);

    /** Create a capture pipeline. */
    public native long nativeCreateCapturePipeline(long engineHandle,
            int width, int height, int format);

    /** Destroy a pipeline. */
    public native void nativeDestroyPipeline(long engineHandle, long pipelineHandle);

    // ==================== Frame processing ====================

    /**
     * Process a preview frame (zero-copy, via HardwareBuffer).
     * @return face detection results as float[], 15 values per face: [x, y, w, h, confidence, 10 landmarks]
     */
    public native float[] nativeProcessPreviewFrame(long pipelineHandle,
            HardwareBuffer buffer, FrameMetadata meta);

    /**
     * Process burst capture frames, returns JPEG bytes.
     * This is the algorithm post-processing step: raw HAL frames → denoising/sharpening/HDR → JPEG.
     * @param buffers multi-frame HardwareBuffer array
     * @param metas   corresponding frame metadata
     * @param jpegQuality JPEG quality (1-100)
     * @return JPEG byte data of the post-processed result
     */
    public native byte[] nativeProcessCapture(long pipelineHandle,
            HardwareBuffer[] buffers, FrameMetadata[] metas, int jpegQuality);

    // ==================== Algorithm control ====================

    /** Enable/disable an algorithm. */
    public native void nativeEnableAlgorithm(long pipelineHandle,
            int algorithmId, boolean enable);

    /** Set an algorithm parameter. */
    public native void nativeSetAlgorithmParam(long pipelineHandle,
            int algorithmId, String paramKey, float paramValue);

    // ==================== Utility methods ====================

    /** Check whether the device supports HardwareBuffer zero-copy transfer. */
    public static boolean supportsHardwareBuffer() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // ==================== Algorithm ID constants ====================

    public static final int ALGO_FACE_DETECT = 0;
    public static final int ALGO_DENOISE = 1;
    public static final int ALGO_SHARPEN = 2;
    public static final int ALGO_BOKEH = 3;
    public static final int ALGO_HDR = 4;
    public static final int ALGO_CLAHE = 5;
    public static final int ALGO_SATURATION = 6;

    // ==================== YUV format constants ====================

    public static final int FORMAT_NV21 = 0;
    public static final int FORMAT_NV12 = 1;
    public static final int FORMAT_I420 = 2;
}