package com.opencv.camera;

import android.hardware.HardwareBuffer;

import java.util.List;

/**
 * Public Java facade. It transports state and buffers only; all algorithm
 * lifecycle, decisions and processing are owned by C++.
 */
public final class CameraAlgoSdk implements AutoCloseable {
    public static final int STATUS_OK = NativeEngine.STATUS_OK;
    public static final int STATUS_SKIPPED = NativeEngine.STATUS_SKIPPED;
    public static final int STATUS_NOT_READY = NativeEngine.STATUS_NOT_READY;
    public static final int FORMAT_NV21 = NativeEngine.FORMAT_NV21;
    public static final int FORMAT_YUV_420_888 = NativeEngine.FORMAT_I420;

    public static final class PreviewFrameResult {
        public final int status;
        public final float[] faces;

        PreviewFrameResult(int status, float[] faces) {
            this.status = status;
            this.faces = faces == null ? new float[0] : faces;
        }
    }

    private final NativeEngine nativeEngine = NativeEngine.getInstance();
    private long engineHandle;
    private long previewHandle;
    private long captureHandle;

    private CameraAlgoSdk(long engineHandle) {
        this.engineHandle = engineHandle;
    }

    public static CameraAlgoSdk create(String assetDir) {
        NativeEngine nativeEngine = NativeEngine.getInstance();
        long handle = nativeEngine.nativeCreateEngine(assetDir);
        if (handle == 0) {
            throw new IllegalStateException("Failed to create native camera engine");
        }
        return new CameraAlgoSdk(handle);
    }

    public static boolean supportsHardwareBuffer() {
        return NativeEngine.supportsHardwareBuffer();
    }

    public synchronized void configurePreview(
            int width, int height, int format, int maxFaces) {
        ensureOpen();
        previewHandle = nativeEngine.nativeCreatePreviewPipeline(
                engineHandle, width, height, format, maxFaces);
        ensureConfigured(previewHandle, "preview");
    }

    public synchronized void configureCapture(int width, int height, int format) {
        ensureOpen();
        captureHandle = nativeEngine.nativeCreateCapturePipeline(
                engineHandle, width, height, format);
        ensureConfigured(captureHandle, "capture");
    }

    public synchronized void updateSessionControl(SessionControl control) {
        ensureOpen();
        nativeEngine.nativeUpdateSessionControl(engineHandle, control.copy());
    }

    public synchronized int adviseCaptureFrameCount(FrameMetadata metadata) {
        ensureOpen();
        return Math.max(1,
                nativeEngine.nativeAdviseCaptureFrameCount(engineHandle, metadata));
    }

    public synchronized PreviewFrameResult processPreview(
            HardwareBuffer buffer, FrameMetadata metadata) {
        ensureConfigured(previewHandle, "preview");
        float[] faces = nativeEngine.nativeProcessPreviewFrame(
                previewHandle, buffer, metadata);
        return new PreviewFrameResult(nativeEngine.nativeGetLastStatus(), faces);
    }

    public synchronized byte[] processCapture(
            List<HardwareBuffer> buffers,
            List<FrameMetadata> metadata,
            int jpegQuality) {
        ensureConfigured(captureHandle, "capture");
        return nativeEngine.nativeProcessCapture(
                captureHandle,
                buffers.toArray(new HardwareBuffer[0]),
                metadata.toArray(new FrameMetadata[0]),
                jpegQuality);
    }

    public synchronized byte[] processCaptureNv21(
            List<byte[]> frames,
            List<FrameMetadata> metadata,
            int width,
            int height,
            int jpegQuality) {
        ensureConfigured(captureHandle, "capture");
        return nativeEngine.nativeProcessCaptureNv21(
                captureHandle,
                frames.toArray(new byte[0][]),
                metadata.toArray(new FrameMetadata[0]),
                width,
                height,
                jpegQuality);
    }

    public synchronized boolean isReady() {
        return engineHandle != 0 && previewHandle != 0 && captureHandle != 0;
    }

    public synchronized int getLastStatus() {
        return nativeEngine.nativeGetLastStatus();
    }

    @Override
    public synchronized void close() {
        if (engineHandle == 0) return;
        nativeEngine.nativeDestroyPipeline(engineHandle, previewHandle);
        nativeEngine.nativeDestroyPipeline(engineHandle, captureHandle);
        nativeEngine.nativeDestroyEngine(engineHandle);
        previewHandle = 0;
        captureHandle = 0;
        engineHandle = 0;
    }

    private void ensureOpen() {
        if (engineHandle == 0) {
            throw new IllegalStateException("CameraAlgoSdk is closed");
        }
    }

    private void ensureConfigured(long handle, String pipeline) {
        ensureOpen();
        if (handle == 0) {
            throw new IllegalStateException(
                    "Native " + pipeline + " pipeline is not configured");
        }
    }
}
