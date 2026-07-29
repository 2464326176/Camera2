package com.opencv.camera;

import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;

/** Internal JNI transport. Product code should use {@link CameraAlgoSdk}. */
public final class NativeEngine {
    private static final String TAG = "NativeEngine";
    private static volatile NativeEngine instance;
    private static volatile boolean librariesLoaded = false;

    static {
        try {
            System.loadLibrary("opencv_java4");
            System.loadLibrary("camera_engine");
            librariesLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
            librariesLoaded = false;
        }
    }

    /**
     * Whether the native libraries were successfully loaded. Product code must
     * check this before creating the engine; otherwise the first native call
     * will throw an {@link UnsatisfiedLinkError} that is NOT catchable as a
     * regular {@link Exception}.
     */
    public static boolean isLoaded() {
        return librariesLoaded;
    }

    private NativeEngine() {}

    public static NativeEngine getInstance() {
        if (instance == null) {
            synchronized (NativeEngine.class) {
                if (instance == null) instance = new NativeEngine();
            }
        }
        return instance;
    }

    public native long nativeCreateEngine(String modelDir);
    public native void nativeDestroyEngine(long engineHandle);
    public native long nativeCreatePreviewPipeline(
            long engineHandle, int width, int height, int format, int maxFaces);
    public native long nativeCreateCapturePipeline(
            long engineHandle, int width, int height, int format);
    public native void nativeDestroyPipeline(long engineHandle, long pipelineHandle);
    public native float[] nativeProcessPreviewFrame(
            long pipelineHandle, HardwareBuffer buffer, FrameMetadata metadata);
    public native byte[] nativeProcessCapture(
            long pipelineHandle, HardwareBuffer[] buffers,
            FrameMetadata[] metadata, int jpegQuality);
    public native byte[] nativeProcessCaptureNv21(
            long pipelineHandle, byte[][] frames, FrameMetadata[] metadata,
            int width, int height, int jpegQuality);
    public native void nativeEnableAlgorithm(
            long pipelineHandle, int algorithmId, boolean enable);
    public native void nativeSetAlgorithmParam(
            long pipelineHandle, int algorithmId, String paramKey, float paramValue);
    public native void nativeUpdateSessionControl(
            long engineHandle, SessionControl control);
    public native int nativeAdviseCaptureFrameCount(
            long engineHandle, FrameMetadata metadata);
    public native int nativeGetLastStatus();

    public static boolean supportsHardwareBuffer() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static final int ALGO_DENOISE = 1;
    public static final int ALGO_SHARPEN = 2;
    public static final int ALGO_HDR = 3;
    public static final int ALGO_CLAHE = 4;
    public static final int ALGO_SATURATION = 5;
    public static final int ALGO_FACE_DETECT = 6;
    public static final int ALGO_BOKEH = 7;

    public static final int STATUS_OK = 0;
    public static final int STATUS_SKIPPED = 1;
    public static final int STATUS_NOT_READY = 2;

    public static final int FORMAT_NV21 = 0;
    public static final int FORMAT_NV12 = 1;
    public static final int FORMAT_I420 = 2;
}
