package com.opencv.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2 wrapper: photo (ISO-adaptive multi-frame YUV) + video recording.
 */
public class CameraEngine {
    private static final String TAG = "CameraEngine";
    private static final int PREVIEW_TARGET_W = 1920;
    private static final int PREVIEW_TARGET_H = 1080;
    private static final int CAPTURE_MAX_PIXELS = 12_000_000; // ~12MP cap for NR performance
    private static final int MAX_BURST = 6;
    private static final int BURST_TIMEOUT_MS = 8000;
    private static final int CAMERA_LOCK_TIMEOUT_MS = 3000;
    private static final int OPEN_LOCK_TIMEOUT_MS = 2500;

    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public static final int MODE_PHOTO = 0;
    public static final int MODE_VIDEO = 1;

    public static final int FLASH_OFF = 0;
    public static final int FLASH_ON = 1;
    public static final int FLASH_AUTO = 2;

    public static final int STATE_IDLE = 0;
    public static final int STATE_PREVIEW = 1;
    public static final int STATE_WAITING_LOCK = 2;
    public static final int STATE_WAITING_PRECAPTURE = 3;
    public static final int STATE_WAITING_NON_PRECAPTURE = 4;
    public static final int STATE_CAPTURING = 5;
    public static final int STATE_RECORDING = 6;

    private static final long AF_LOCK_TIMEOUT_MS = 800;
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;

    private final Context context;
    private final ImageReader.OnImageAvailableListener previewListener;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private HandlerThread imageProcessingThread;
    private Handler imageProcessingHandler;

    private String cameraId;
    private Size previewSize;
    private Size captureSize;
    private Size videoSize;
    private ImageReader previewReader;
    private ImageReader captureReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;

    private int mCurrentFacing = CameraCharacteristics.LENS_FACING_BACK;
    private SurfaceTexture mPendingTexture;
    private CameraCharacteristics cameraCharacteristics;
    private int sensorOrientation;
    private int maxFaceDetectMode;

    private int currentState = STATE_IDLE;
    private int captureMode = MODE_PHOTO;
    private int flashMode = FLASH_OFF;
    private boolean faceDetectEnabled = true;
    private boolean isSessionReady = false;
    private int pendingBurstFrames = 1;
    private final Runnable afLockTimeoutRunnable = this::onAfLockTimeout;
    private final Runnable precaptureTimeoutRunnable = this::onPrecaptureTimeout;

    private volatile int currentIso = 100;
    private volatile long currentExposureNs = 0;
    private float maxDigitalZoom = 1f;
    private float currentZoom = 1f;
    private android.graphics.Rect sensorArraySize;

    // Multi-frame capture
    private final Object burstLock = new Object();
    private final List<byte[]> burstFrames = new ArrayList<>();
    private int burstTarget = 1;
    private volatile int burstIso = 100;
    private int burstWidth;
    private int burstHeight;
    private final AtomicInteger burstReceived = new AtomicInteger(0);
    private boolean burstActive = false;
    private BurstCallback burstCallback;

    // HardwareBuffer / FrameCallback
    private FrameCallback frameCallback;
    private final List<HardwareBuffer> burstHwBuffers = new ArrayList<>();
    private final List<FrameMetadata> burstMetas = new ArrayList<>();
    private volatile CaptureResult lastCaptureResult;

    // Video
    private MediaRecorder mediaRecorder;
    private File videoFile;
    private Surface recorderSurface;
    private boolean isRecording = false;

    private CameraCallback callback;

    public interface CameraCallback {
        void onCameraOpened(Size previewSize);
        void onCameraClosed();
        void onCameraError(String message);
        void onAutoFocusComplete(boolean success);
        void onIsoUpdated(int iso);
        void onVideoRecordingStarted(File file);
        void onVideoRecordingStopped(File file, boolean success);
    }

    public interface BurstCallback {
        void onBurstComplete(List<byte[]> nv21Frames, int width, int height, int iso);
        void onBurstFailed(String reason);
    }

    /**
     * Frame callback interface — passes HardwareBuffer + FrameMetadata to NativeEngine.
     * The capture flow follows a 3-stage thumbnail pipeline:
     *   1. Preview frame → instant thumbnail (on shutter click)
     *   2. First capture frame → thumbnail update (when HAL delivers)
     *   3. Final post-processed JPEG → thumbnail + gallery save (after algorithm processing)
     */
    public interface FrameCallback {
        /** Preview frame ready */
        default void onPreviewFrame(HardwareBuffer buffer, FrameMetadata metadata) {}

        /** First capture frame arrived (NV21 data for instant thumbnail update) */
        default void onFirstCaptureFrame(byte[] nv21Data, int width, int height, FrameMetadata metadata) {}

        /** Burst capture complete — all frames ready for post-processing */
        default void onBurstComplete(List<HardwareBuffer> buffers, List<FrameMetadata> metadataList) {}
    }

    public CameraEngine(@NonNull Context context,
                            @NonNull ImageReader.OnImageAvailableListener previewListener) {
        this.context = context.getApplicationContext();
        this.previewListener = previewListener;
    }

    public void setCallback(CameraCallback callback) {
        this.callback = callback;
    }

    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }

    public void startCamera() {
        startBackgroundThreads();
        openCamera();
    }

    public void stopCamera() {
        if (isRecording) {
            stopRecordingInternal(false);
        }
        closeCamera();
        stopBackgroundThreads();
    }

    public void switchCamera(SurfaceTexture texture) {
        if (backgroundHandler == null) return;
        if (isRecording) return;
        backgroundHandler.post(() -> {
            mPendingTexture = texture;
            mCurrentFacing = (mCurrentFacing == CameraCharacteristics.LENS_FACING_BACK)
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            closeCameraInternal();
            openCamera();
        });
    }

    /**
     * Set capture mode (photo/video). Rebuild session via stopCamera + createPreviewSession.
     */
    public void setCaptureMode(int mode) {
        if (captureMode == mode) return;
        if (isRecording) return;
        captureMode = mode;
    }

    public int getCaptureMode() {
        return captureMode;
    }

    public void createPreviewSession(SurfaceTexture texture) {
        if (cameraDevice == null) {
            mPendingTexture = texture;
            return;
        }
        if (texture == null || previewSize == null) return;
        mPendingTexture = texture;

        Log.d(TAG, "Creating preview session, mode=" + (captureMode == MODE_VIDEO ? "VIDEO" : "PHOTO")
                + " preview=" + previewSize + " capture=" + captureSize);
        try {
            closeSessionOnly();

            // Clean up old captureReader to avoid resource leak
            if (captureReader != null) {
                captureReader.close();
                captureReader = null;
            }

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);

            if (captureMode == MODE_VIDEO) {
                setupMediaRecorder();
                List<Surface> outputs = new ArrayList<>();
                outputs.add(previewSurface);
                outputs.add(previewReader.getSurface());
                if (recorderSurface != null) {
                    outputs.add(recorderSurface);
                }

                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                previewRequestBuilder.addTarget(previewSurface);
                previewRequestBuilder.addTarget(previewReader.getSurface());
                if (recorderSurface != null) {
                    previewRequestBuilder.addTarget(recorderSurface);
                }
                applyCommonPreviewControls();

                cameraDevice.createCaptureSession(outputs, sessionCallback, backgroundHandler);
            } else {
                // Photo mode: preview + YUV analysis + YUV still capture
                captureReader = ImageReader.newInstance(
                        captureSize.getWidth(), captureSize.getHeight(),
                        ImageFormat.YUV_420_888, MAX_BURST + 2);
                captureReader.setOnImageAvailableListener(captureImageListener, imageProcessingHandler);

                List<Surface> outputs = Arrays.asList(
                        previewSurface,
                        previewReader.getSurface(),
                        captureReader.getSurface());

                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequestBuilder.addTarget(previewSurface);
                previewRequestBuilder.addTarget(previewReader.getSurface());
                applyCommonPreviewControls();

                cameraDevice.createCaptureSession(outputs, sessionCallback, backgroundHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, "createPreviewSession failed", e);
            if (callback != null) callback.onCameraError(context.getString(R.string.error_preview_config_failed) + ": " + e.getMessage());
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    isSessionReady = true;
                    currentState = STATE_PREVIEW;
                    Log.i(TAG, "Session configured, starting preview");
                    try {
                        previewRequest = previewRequestBuilder.build();
                        session.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
                        if (callback != null && previewSize != null) {
                            callback.onCameraOpened(previewSize);
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "setRepeatingRequest failed", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Capture session configure failed");
                    if (callback != null) callback.onCameraError("Preview configuration failed");
                }
            };

    private void applyCommonPreviewControls() {
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
        previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
        updateFlashModeLocked();
        updateFaceDetectModeLocked();
        applyZoomLocked();
    }

    /**
     * Capture: determine frame count by ISO; AF convergence + AE precapture/lock then burst YUV.
     */
    public void captureStillBurst(BurstCallback cb) {
        if (cameraDevice == null || !isSessionReady || captureMode != MODE_PHOTO) {
            if (cb != null) cb.onBurstFailed("Camera not ready");
            return;
        }
        if (burstActive || currentState == STATE_WAITING_LOCK
                || currentState == STATE_WAITING_PRECAPTURE
                || currentState == STATE_WAITING_NON_PRECAPTURE
                || currentState == STATE_CAPTURING) {
            if (cb != null) cb.onBurstFailed("Capture in progress");
            return;
        }

        this.burstCallback = cb;
        int iso = Math.max(50, currentIso);
        int frames = this.frameCountForIso(iso);
        frames = Math.min(frames, MAX_BURST);
        pendingBurstFrames = frames;

        synchronized (burstLock) {
            burstFrames.clear();
            burstTarget = frames;
            burstIso = iso;
            burstWidth = captureSize.getWidth();
            burstHeight = captureSize.getHeight();
            burstReceived.set(0);
            burstActive = true;
        }

        Log.i(TAG, "Start burst capture: frames=" + frames + " iso=" + iso
                + " size=" + captureSize + " flash=" + flashMode);
        lockFocusThenCapture();
    }

    private void lockFocusThenCapture() {
        try {
            if (previewRequestBuilder == null || captureSession == null) {
                failBurst("preview not ready");
                return;
            }
            Log.d(TAG, "Locking AF, state → WAITING_LOCK");
            currentState = STATE_WAITING_LOCK;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            applyFlashToBuilder(previewRequestBuilder, false);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);

            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacks(afLockTimeoutRunnable);
                backgroundHandler.postDelayed(afLockTimeoutRunnable, AF_LOCK_TIMEOUT_MS);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "lockFocusThenCapture: " + e.getMessage());
            afterAfLocked();
        }
    }

    private void onAfLockTimeout() {
        if (currentState == STATE_WAITING_LOCK) {
            Log.w(TAG, "AF lock timeout, proceed");
            afterAfLocked();
        }
    }

    private void afterAfLocked() {
        if (currentState != STATE_WAITING_LOCK) return;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(afLockTimeoutRunnable);
        }
        Log.d(TAG, "AF locked, proceeding to AE pre-capture");
        // Flash On/Auto needs precapture; Off can skip if AE already converged
        if (flashMode == FLASH_ON || flashMode == FLASH_AUTO) {
            runPrecaptureSequence();
        } else {
            lockAeAndCapture();
        }
    }

    private void runPrecaptureSequence() {
        if (currentState != STATE_WAITING_LOCK
                && currentState != STATE_WAITING_PRECAPTURE) {
            return;
        }
        try {
            if (previewRequestBuilder == null || captureSession == null) {
                fireBurstCapture(pendingBurstFrames);
                return;
            }
            Log.d(TAG, "Running AE pre-capture sequence, state → WAITING_PRECAPTURE");
            currentState = STATE_WAITING_PRECAPTURE;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            applyFlashToBuilder(previewRequestBuilder, false);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);

            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacks(precaptureTimeoutRunnable);
                backgroundHandler.postDelayed(precaptureTimeoutRunnable, PRECAPTURE_TIMEOUT_MS);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "runPrecaptureSequence: " + e.getMessage());
            lockAeAndCapture();
        }
    }

    private void onPrecaptureTimeout() {
        if (currentState == STATE_WAITING_PRECAPTURE
                || currentState == STATE_WAITING_NON_PRECAPTURE) {
            Log.w(TAG, "Precapture timeout, proceed");
            lockAeAndCapture();
        }
    }

    private void lockAeAndCapture() {
        if (currentState != STATE_WAITING_LOCK
                && currentState != STATE_WAITING_PRECAPTURE
                && currentState != STATE_WAITING_NON_PRECAPTURE) {
            return;
        }
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(precaptureTimeoutRunnable);
            backgroundHandler.removeCallbacks(afLockTimeoutRunnable);
        }
        Log.d(TAG, "Locking AE and firing burst capture, frames=" + pendingBurstFrames);
        try {
            if (previewRequestBuilder != null && captureSession != null) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "AE lock failed: " + e.getMessage());
        }
        fireBurstCapture(pendingBurstFrames);
    }

    private void fireBurstCapture(int frameCount) {
        if (currentState == STATE_CAPTURING) return;
        burstHwBuffers.clear();
        burstMetas.clear();
        lastCaptureResult = null;
        try {
            if (cameraDevice == null || captureReader == null) {
                failBurst("capture reader null");
                unlockFocus();
                return;
            }
            Log.i(TAG, "Firing burst capture: " + frameCount + " frames, state → CAPTURING");
            currentState = STATE_CAPTURING;

            List<CaptureRequest> requests = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                CaptureRequest.Builder builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(captureReader.getSurface());
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                applyFlashToBuilder(builder, true);
                applyZoomToBuilder(builder);
                requests.add(builder.build());
            }

            // Do NOT call stopRepeating() before captureBurst() — on some devices
            // (e.g. MediaTek) the async stop can race with captureBurst, causing the
            // burst to be silently dropped and all frames lost.
            captureSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    lastCaptureResult = result;
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (iso != null) {
                        burstIso = iso;
                        currentIso = iso;
                    }
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                                       int sequenceId, long frameNumber) {
                    if (backgroundHandler != null) {
                        backgroundHandler.postDelayed(CameraEngine.this::unlockFocus, 100);
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    Log.e(TAG, "Burst frame failed: " + failure.getReason());
                }
            }, backgroundHandler);

            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(this::finishBurstIfNeeded, BURST_TIMEOUT_MS);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "fireBurstCapture: " + e.getMessage());
            failBurst(e.getMessage());
            unlockFocus();
        }
    }

    private final ImageReader.OnImageAvailableListener captureImageListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image == null || !burstActive) {
                if (image != null) image.close();
                return;
            }
            byte[] nv21 = imageToNv21(image);
            int w = image.getWidth();
            int h = image.getHeight();

            // Extract HardwareBuffer before closing the image
            HardwareBuffer hwBuf = null;
            if (frameCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hwBuf = image.getHardwareBuffer();
            }

            image.close();
            image = null;

            if (nv21 == null) {
                Log.w(TAG, "Failed to convert capture frame to NV21");
                return;
            }

            // Notify first-frame arrival for instant thumbnail update
            int frameIndex = burstReceived.get();
            if (frameIndex == 0 && frameCallback != null) {
                Log.d(TAG, "First capture frame arrived: " + w + "x" + h);
                frameCallback.onFirstCaptureFrame(nv21, w, h, extractMetadata(lastCaptureResult));
            }

            boolean complete = false;
            List<byte[]> copy = null;
            int iso;
            synchronized (burstLock) {
                if (!burstActive) return;
                burstWidth = w;
                burstHeight = h;
                burstFrames.add(nv21);

                // New architecture: pass HardwareBuffer via FrameCallback
                if (hwBuf != null) {
                    burstHwBuffers.add(hwBuf);
                    burstMetas.add(extractMetadata(lastCaptureResult));
                }
                int got = burstReceived.incrementAndGet();
                if (got >= burstTarget) {
                    complete = true;
                    copy = new ArrayList<>(burstFrames);
                    iso = burstIso;
                    burstActive = false;
                    burstFrames.clear();
                } else {
                    iso = burstIso;
                }
            }

            if (complete) {
                Log.i(TAG, "Burst complete: " + copy.size() + " frames, iso=" + iso);
                BurstCallback cb = burstCallback;
                burstCallback = null;
                if (cb != null) {
                    cb.onBurstComplete(copy, w, h, iso);
                }
                if (frameCallback != null && !burstHwBuffers.isEmpty()) {
                    frameCallback.onBurstComplete(new ArrayList<>(burstHwBuffers), new ArrayList<>(burstMetas));
                }
                // Ownership of HardwareBuffer objects transferred to callback;
                // clear local references (caller will close them after processing).
                burstHwBuffers.clear();
                burstMetas.clear();
            }
        } catch (Exception e) {
            Log.e(TAG, "captureImageListener error", e);
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    };

    private void finishBurstIfNeeded() {
        List<byte[]> copy = null;
        int w, h, iso;
        synchronized (burstLock) {
            if (!burstActive) return;
            if (burstFrames.isEmpty()) {
                burstActive = false;
                // Close any HardwareBuffers that may have leaked
                for (HardwareBuffer buf : burstHwBuffers) {
                    try { buf.close(); } catch (Exception ignored) {}
                }
                burstHwBuffers.clear();
                burstMetas.clear();
                BurstCallback cb = burstCallback;
                burstCallback = null;
                if (cb != null) cb.onBurstFailed("No image frames received");
                return;
            }
            copy = new ArrayList<>(burstFrames);
            w = burstWidth;
            h = burstHeight;
            iso = burstIso;
            burstActive = false;
            burstFrames.clear();
        }
        Log.w(TAG, "Burst timeout, using " + copy.size() + " frames");
        BurstCallback cb = burstCallback;
        burstCallback = null;
        if (cb != null) {
            cb.onBurstComplete(copy, w, h, iso);
        }
    }

    private void failBurst(String reason) {
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(afLockTimeoutRunnable);
            backgroundHandler.removeCallbacks(precaptureTimeoutRunnable);
        }
        synchronized (burstLock) {
            burstActive = false;
            burstFrames.clear();
        }
        if (currentState != STATE_PREVIEW && currentState != STATE_IDLE
                && currentState != STATE_RECORDING) {
            currentState = STATE_PREVIEW;
        }
        BurstCallback cb = burstCallback;
        burstCallback = null;
        if (cb != null) cb.onBurstFailed(reason);
    }

    private void unlockFocus() {
        try {
            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacks(afLockTimeoutRunnable);
                backgroundHandler.removeCallbacks(precaptureTimeoutRunnable);
            }
            if (previewRequestBuilder == null || captureSession == null) return;
            Log.d(TAG, "Unlocking focus, resuming preview");
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            applyFlashToBuilder(previewRequestBuilder, false);
            captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyCommonPreviewControls();
            currentState = STATE_PREVIEW;
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "unlockFocus: " + e.getMessage());
        }
    }

    // ---------- Video ----------

    public boolean isRecording() {
        return isRecording;
    }

    public void startRecording() {
        if (captureMode != MODE_VIDEO || !isSessionReady || isRecording) return;
        if (mediaRecorder == null) {
            if (callback != null) callback.onCameraError("Video recorder not ready");
            return;
        }
        try {
            mediaRecorder.start();
            isRecording = true;
            currentState = STATE_RECORDING;
            if (callback != null) callback.onVideoRecordingStarted(videoFile);
            Log.i(TAG, "Video recording started: " + videoFile);
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            isRecording = false;
            if (callback != null) callback.onCameraError("Failed to start recording");
        }
    }

    public void stopRecording() {
        stopRecordingInternal(true);
    }

    private void stopRecordingInternal(boolean notify) {
        if (!isRecording && mediaRecorder == null) return;
        boolean success = false;
        File out = videoFile;
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder.stop();
                success = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecording failed", e);
            success = false;
        }
        isRecording = false;
        currentState = STATE_PREVIEW;
        releaseMediaRecorder();
        // Rebuild session for next recording
        if (mPendingTexture != null && cameraDevice != null) {
            createPreviewSession(mPendingTexture);
        }
        if (notify && callback != null) {
            callback.onVideoRecordingStopped(out, success && out != null && out.exists());
        }
    }

    private void setupMediaRecorder() throws IOException {
        releaseMediaRecorder();
        videoFile = new File(context.getCacheDir(),
                "VID_" + System.currentTimeMillis() + ".mp4");

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(videoFile.getAbsolutePath());
        mediaRecorder.setVideoEncodingBitRate(8_000_000);
        mediaRecorder.setVideoFrameRate(30);
        Size vs = videoSize != null ? videoSize : previewSize;
        mediaRecorder.setVideoSize(vs.getWidth(), vs.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(128000);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setOrientationHint(getJpegOrientation());
        mediaRecorder.prepare();
        recorderSurface = mediaRecorder.getSurface();
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception ignored) {
            }
            mediaRecorder = null;
        }
        recorderSurface = null;
    }

    // ---------- Focus / Zoom / Flash ----------

    public void focusOnPoint(float x, float y, int previewWidth, int previewHeight) {
        if (cameraCharacteristics == null || previewRequestBuilder == null || sensorArraySize == null) {
            return;
        }
        try {
            // Map view coords to sensor active array (simplified, portrait-aware)
            float nx = x / Math.max(1, previewWidth);
            float ny = y / Math.max(1, previewHeight);
            if (isFrontCamera()) {
                nx = 1f - nx;
            }

            int sensorW = sensorArraySize.width();
            int sensorH = sensorArraySize.height();
            // Sensor is typically landscape; portrait display maps x->y
            int sensorX = (int) (ny * sensorW);
            int sensorY = (int) ((1f - nx) * sensorH);

            int half = Math.min(sensorW, sensorH) / 10;
            int left = clamp(sensorX - half, 0, sensorW - 1);
            int top = clamp(sensorY - half, 0, sensorH - 1);
            int right = clamp(sensorX + half, left + 1, sensorW);
            int bottom = clamp(sensorY + half, top + 1, sensorH);

            MeteringRectangle area = new MeteringRectangle(
                    left, top, right - left, bottom - top,
                    MeteringRectangle.METERING_WEIGHT_MAX);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{area});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{area});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            if (captureSession != null) {
                captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "focusOnPoint: " + e.getMessage());
        }
    }

    public void setZoom(float zoom) {
        currentZoom = Math.max(1f, Math.min(zoom, maxDigitalZoom));
        if (previewRequestBuilder == null || captureSession == null || !isSessionReady) return;
        try {
            applyZoomLocked();
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "setZoom: " + e.getMessage());
        }
    }

    public float getCurrentZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxDigitalZoom;
    }

    private void applyZoomLocked() {
        applyZoomToBuilder(previewRequestBuilder);
    }

    private void applyZoomToBuilder(CaptureRequest.Builder builder) {
        if (builder == null || sensorArraySize == null) return;
        float zoom = Math.max(1f, Math.min(currentZoom, maxDigitalZoom));
        int centerX = sensorArraySize.centerX();
        int centerY = sensorArraySize.centerY();
        int deltaX = (int) ((0.5f * sensorArraySize.width()) / zoom);
        int deltaY = (int) ((0.5f * sensorArraySize.height()) / zoom);
        android.graphics.Rect crop = new android.graphics.Rect(
                centerX - deltaX, centerY - deltaY,
                centerX + deltaX, centerY + deltaY);
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop);
    }

    public void setFlashMode(int mode) {
        if (mode < FLASH_OFF || mode > FLASH_AUTO) mode = FLASH_OFF;
        this.flashMode = mode;
        updateFlashMode();
    }

    public int getFlashMode() {
        return flashMode;
    }

    private void updateFlashMode() {
        if (previewRequestBuilder == null) return;
        updateFlashModeLocked();
        if (captureSession != null && isSessionReady) {
            try {
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "updateFlashMode: " + e.getMessage());
            }
        }
    }

    private void updateFlashModeLocked() {
        applyFlashToBuilder(previewRequestBuilder, false);
    }

    /**
     * @param stillCapture true for still burst requests (ALWAYS_FLASH when ON)
     */
    private void applyFlashToBuilder(CaptureRequest.Builder builder, boolean stillCapture) {
        if (builder == null) return;
        switch (flashMode) {
            case FLASH_ON:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        stillCapture
                                ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                                : CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                break;
            case FLASH_AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
            case FLASH_OFF:
            default:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    public void setFaceDetectEnabled(boolean enabled) {
        this.faceDetectEnabled = enabled;
        updateFaceDetectMode();
    }

    public boolean isFaceDetectEnabled() {
        return faceDetectEnabled;
    }

    private void updateFaceDetectMode() {
        if (previewRequestBuilder == null) return;
        updateFaceDetectModeLocked();
        if (captureSession != null && isSessionReady) {
            try {
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "setFaceDetectEnabled: " + e.getMessage());
            }
        }
    }

    private void updateFaceDetectModeLocked() {
        if (previewRequestBuilder == null) return;
        // OpenCV handles face detect; keep HAL face detect off to save power
        previewRequestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);
    }

    public boolean isFrontCamera() {
        return mCurrentFacing == CameraCharacteristics.LENS_FACING_FRONT;
    }

    public Size getPreviewSize() { return previewSize; }
    public Size getCaptureSize() { return captureSize; }
    public int getSensorOrientation() { return sensorOrientation; }
    public int getCurrentIso() { return currentIso; }

    /**
     * Determine multi-frame denoise frame count by ISO.
     * ISO &lt; 200 → 1 frame / 200–399 → 3 frames / 400–799 → 4 frames
     * 800–1599 → 5 frames / ≥1600 → 6 frames
     */
    public int frameCountForIso(int iso) {
        if (iso < 200) return 1;
        if (iso < 400) return 3;
        if (iso < 800) return 4;
        if (iso < 1600) return 5;
        return 6;
    }

    /** Get the last CaptureResult (for preview frame metadata extraction) */
    public CaptureResult getLastCaptureResult() {
        return lastCaptureResult;
    }

    public int getJpegOrientation() {
        int deviceOrientation = getDeviceOrientationDegrees();
        if (cameraCharacteristics == null) return 90;
        Integer sensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensorOri = sensor != null ? sensor : 90;
        if (isFrontCamera()) {
            return (sensorOri + deviceOrientation) % 360;
        } else {
            return (sensorOri - deviceOrientation + 360) % 360;
        }
    }

    private int getDeviceOrientationDegrees() {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return 0;
            int rotation = wm.getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- callbacks ----------

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "Camera device opened: " + cameraId);
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            if (mPendingTexture != null) {
                createPreviewSession(mPendingTexture);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera device disconnected");
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            if (callback != null) callback.onCameraError(context.getString(R.string.error_camera_disconnected));
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            String errorMsg;
            switch (error) {
                case ERROR_CAMERA_DEVICE: errorMsg = context.getString(R.string.error_camera_device); break;
                case ERROR_CAMERA_DISABLED: errorMsg = context.getString(R.string.error_camera_disabled); break;
                case ERROR_CAMERA_IN_USE: errorMsg = context.getString(R.string.error_camera_in_use); break;
                case ERROR_CAMERA_SERVICE: errorMsg = context.getString(R.string.error_camera_service); break;
                default: errorMsg = context.getString(R.string.error_camera_device) + ": " + error; break;
            }
            Log.e(TAG, "Camera error: " + errorMsg);
            if (callback != null) callback.onCameraError(errorMsg);
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {

                private void processResult(CaptureResult result) {
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (iso != null && iso != currentIso) {
                        currentIso = iso;
                        if (callback != null) callback.onIsoUpdated(iso);
                    }
                    Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    if (exp != null) currentExposureNs = exp;

                    switch (currentState) {
                        case STATE_WAITING_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null
                                    || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                Log.d(TAG, "AF state converged: " + afState);
                                afterAfLocked();
                            }
                            break;
                        }
                        case STATE_WAITING_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                Log.d(TAG, "AE pre-capture state: " + aeState + ", → WAITING_NON_PRECAPTURE");
                                currentState = STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        }
                        case STATE_WAITING_NON_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                Log.d(TAG, "AE converged, locking and capturing");
                                lockAeAndCapture();
                            }
                            break;
                        }
                        case STATE_PREVIEW: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState != null
                                    && (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                                boolean success = afState
                                        == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
                                if (callback != null) callback.onAutoFocusComplete(success);
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    processResult(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    processResult(result);
                }
            };

    // ---------- open / close ----------

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) callback.onCameraError(context.getString(R.string.error_camera_permission));
            return;
        }

        Log.i(TAG, "Opening camera, facing=" + (mCurrentFacing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT"));
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(OPEN_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timeout waiting to lock camera opening.");
            }

            closeReaders();

            cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != mCurrentFacing) continue;

                cameraCharacteristics = chars;
                StreamConfigurationMap map = chars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;

                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = so != null ? so : 90;
                sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                maxDigitalZoom = maxZoom != null ? maxZoom : 1f;
                currentZoom = 1f;

                int[] faceDetectModes = chars.get(
                        CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                maxFaceDetectMode = 0;
                if (faceDetectModes != null) {
                    for (int mode : faceDetectModes) {
                        maxFaceDetectMode = Math.max(maxFaceDetectMode, mode);
                    }
                }

                Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (yuvSizes == null || yuvSizes.length == 0) continue;

                captureSize = chooseCaptureSize(yuvSizes);
                previewSize = chooseOptimalPreviewSize(yuvSizes, PREVIEW_TARGET_W, PREVIEW_TARGET_H, captureSize);

                Size[] mediaSizes = map.getOutputSizes(MediaRecorder.class);
                videoSize = chooseVideoSize(mediaSizes != null ? mediaSizes : yuvSizes);

                Log.i(TAG, "Preview=" + previewSize + " Capture=" + captureSize
                        + " Video=" + videoSize + " sensorOri=" + sensorOrientation);
                cameraId = id;
                break;
            }

            if (cameraId == null) {
                cameraOpenCloseLock.release();
                if (callback != null) callback.onCameraError(context.getString(R.string.error_camera_not_found));
                return;
            }

            previewReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            previewReader.setOnImageAvailableListener(previewListener, imageProcessingHandler);

            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            try { cameraOpenCloseLock.release(); } catch (Exception ignored) {}
            if (callback != null) callback.onCameraError(context.getString(R.string.error_camera_open_failed) + ": " + e.getMessage());
        }
    }

    private void closeCamera() {
        Log.i(TAG, "Closing camera");
        try {
            if (!cameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timeout acquiring camera lock, forcing close");
                closeCameraInternal();
                return;
            }
            closeCameraInternal();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void closeCameraInternal() {
        closeSessionOnly();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        closeReaders();
        releaseMediaRecorder();
        isSessionReady = false;
        currentState = STATE_IDLE;
        if (callback != null) callback.onCameraClosed();
    }

    private void closeSessionOnly() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        isSessionReady = false;
    }

    private void closeReaders() {
        if (previewReader != null) {
            previewReader.close();
            previewReader = null;
        }
        if (captureReader != null) {
            captureReader.close();
            captureReader = null;
        }
    }

    private void startBackgroundThreads() {
        Log.d(TAG, "Starting background threads");
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
        if (imageProcessingThread == null) {
            imageProcessingThread = new HandlerThread("ImageProcessing");
            imageProcessingThread.start();
            imageProcessingHandler = new Handler(imageProcessingThread.getLooper());
        }
    }

    private void stopBackgroundThreads() {
        Log.d(TAG, "Stopping background threads");
        quitThread(backgroundThread);
        backgroundThread = null;
        backgroundHandler = null;

        quitThread(imageProcessingThread);
        imageProcessingThread = null;
        imageProcessingHandler = null;
    }

    private void quitThread(HandlerThread thread) {
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------- size selection ----------

    private static Size chooseCaptureSize(Size[] choices) {
        Size best = null;
        Size best43 = null;
        long bestArea = 0;
        long best43Area = 0;
        for (Size s : choices) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels <= CAPTURE_MAX_PIXELS && s.getWidth() >= 1280) {
                float ratio = (float) s.getWidth() / s.getHeight();
                if (Math.abs(ratio - 4f / 3f) < 0.05f) {
                    if (pixels > best43Area) {
                        best43 = s;
                        best43Area = pixels;
                    }
                }
                if (pixels > bestArea) {
                    best = s;
                    bestArea = pixels;
                }
            }
        }
        if (best43 != null) return best43;
        if (best != null) return best;
        return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
    }

    private static Size chooseOptimalPreviewSize(Size[] choices, int targetW, int targetH, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        float targetRatio = (float) w / h;

        for (Size option : choices) {
            float ratio = (float) option.getWidth() / option.getHeight();
            if (Math.abs(ratio - targetRatio) > 0.1f) continue;
            if (option.getWidth() >= targetW && option.getHeight() >= targetH) {
                bigEnough.add(option);
            } else if (option.getWidth() * option.getHeight() >= 640 * 480) {
                notBigEnough.add(option);
            }
        }

        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            // Fallback: closest to 720p
            Size best = choices[0];
            long bestDiff = Long.MAX_VALUE;
            long target = (long) targetW * targetH;
            for (Size s : choices) {
                long diff = Math.abs((long) s.getWidth() * s.getHeight() - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            return best;
        }
    }

    private static Size chooseVideoSize(Size[] choices) {
        // Prefer 1080p 16:9
        Size best1080 = null;
        Size best720 = null;
        for (Size s : choices) {
            if (s.getWidth() == 1920 && s.getHeight() == 1080) best1080 = s;
            if (s.getWidth() == 1280 && s.getHeight() == 720) best720 = s;
        }
        if (best1080 != null) return best1080;
        if (best720 != null) return best720;
        return chooseOptimalPreviewSize(choices, 1280, 720,
                new Size(16, 9));
    }

    /**
     * Extract FrameMetadata from a CaptureResult.
     */
    public static FrameMetadata extractMetadata(CaptureResult result) {
        FrameMetadata meta = new FrameMetadata();
        if (result == null) return meta;
        meta.timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) != null
                ? result.get(CaptureResult.SENSOR_TIMESTAMP) : 0;
        meta.iso = result.get(CaptureResult.SENSOR_SENSITIVITY) != null
                ? result.get(CaptureResult.SENSOR_SENSITIVITY) : 100;
        meta.exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null
                ? result.get(CaptureResult.SENSOR_EXPOSURE_TIME) : 0;
        meta.flashState = result.get(CaptureResult.FLASH_STATE) != null
                ? result.get(CaptureResult.FLASH_STATE) : 0;
        meta.lensAperture = result.get(CaptureResult.LENS_APERTURE) != null
                ? result.get(CaptureResult.LENS_APERTURE) : 0f;
        meta.aeState = result.get(CaptureResult.CONTROL_AE_STATE) != null
                ? result.get(CaptureResult.CONTROL_AE_STATE) : 0;
        meta.afState = result.get(CaptureResult.CONTROL_AF_STATE) != null
                ? result.get(CaptureResult.CONTROL_AF_STATE) : 0;
        meta.awbState = result.get(CaptureResult.CONTROL_AWB_STATE) != null
                ? result.get(CaptureResult.CONTROL_AWB_STATE) : 0;
        return meta;
    }

    /**
     * YUV_420_888 to NV21 (handles pixelStride / rowStride).
     */
    private static byte[] imageToNv21(Image image) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        java.nio.ByteBuffer yBuffer = planes[0].getBuffer();
        java.nio.ByteBuffer uBuffer = planes[1].getBuffer();
        java.nio.ByteBuffer vBuffer = planes[2].getBuffer();

        int width = image.getWidth();
        int height = image.getHeight();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];
        int pos = 0;

        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, width * height);
            pos = width * height;
        } else if (yPixelStride == 1) {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, pos, width);
                pos += width;
            }
        } else {
            byte[] rowBuf = new byte[yRowStride];
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(rowBuf, 0, Math.min(yRowStride, yBuffer.remaining()));
                for (int col = 0; col < width; col++) {
                    nv21[pos++] = rowBuf[col * yPixelStride];
                }
            }
        }

        if (vPixelStride == 2 && uPixelStride == 2
                && vRowStride == uRowStride
                && planes[1].getBuffer().capacity() > 0) {
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            byte[] rowBuf = new byte[vRowStride];
            int outPos = width * height;
            for (int row = 0; row < uvHeight; row++) {
                vBuffer.position(row * vRowStride);
                int toRead = Math.min(vRowStride, vBuffer.remaining());
                vBuffer.get(rowBuf, 0, toRead);
                for (int col = 0; col < uvWidth; col++) {
                    int idx = col * vPixelStride;
                    nv21[outPos++] = rowBuf[idx];
                    if (idx + 1 < toRead) {
                        nv21[outPos++] = rowBuf[idx + 1];
                    } else {
                        nv21[outPos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                    }
                }
            }
            return nv21;
        }

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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
