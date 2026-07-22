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
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
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
 * Camera2 封装：照片（ISO 自适应多帧 YUV）+ 视频录制。
 */
public class CameraController {
    private static final String TAG = "CameraController";
    private static final int PREVIEW_TARGET_W = 1280;
    private static final int PREVIEW_TARGET_H = 720;
    private static final int CAPTURE_MAX_PIXELS = 12_000_000; // ~12MP cap for NR performance
    private static final int MAX_BURST = 6;

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
    private int burstIso = 100;
    private int burstWidth;
    private int burstHeight;
    private final AtomicInteger burstReceived = new AtomicInteger(0);
    private boolean burstActive = false;
    private BurstCallback burstCallback;

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

    public CameraController(@NonNull Context context,
                            @NonNull ImageReader.OnImageAvailableListener previewListener) {
        this.context = context.getApplicationContext();
        this.previewListener = previewListener;
    }

    public void setCallback(CameraCallback callback) {
        this.callback = callback;
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
     * 设置拍照/录像模式。会话由 UI 通过 stopCamera + createPreviewSession 重建。
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

        try {
            closeSessionOnly();

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
                if (captureReader != null) {
                    captureReader.close();
                }
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
            if (callback != null) callback.onCameraError("预览配置失败: " + e.getMessage());
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
                    if (callback != null) callback.onCameraError("预览配置失败");
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
     * 拍照：根据当前 ISO 决定帧数；AF 收敛 + AE precapture/lock 后 burst 采集 YUV。
     */
    public void captureStillBurst(BurstCallback cb) {
        if (cameraDevice == null || !isSessionReady || captureMode != MODE_PHOTO) {
            if (cb != null) cb.onBurstFailed("相机未就绪");
            return;
        }
        if (burstActive || currentState == STATE_WAITING_LOCK
                || currentState == STATE_WAITING_PRECAPTURE
                || currentState == STATE_WAITING_NON_PRECAPTURE
                || currentState == STATE_CAPTURING) {
            if (cb != null) cb.onBurstFailed("正在拍照");
            return;
        }

        this.burstCallback = cb;
        int iso = Math.max(50, currentIso);
        int frames = ImageProcessor.frameCountForIso(iso);
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
        try {
            if (cameraDevice == null || captureReader == null) {
                failBurst("capture reader null");
                unlockFocus();
                return;
            }
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

            captureSession.stopRepeating();
            captureSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
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
                        backgroundHandler.postDelayed(CameraController.this::unlockFocus, 100);
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
                backgroundHandler.postDelayed(this::finishBurstIfNeeded, 8000);
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
            byte[] nv21 = ImageProcessor.imageToNv21(image);
            int w = image.getWidth();
            int h = image.getHeight();
            image.close();
            image = null;

            if (nv21 == null) {
                Log.w(TAG, "Failed to convert capture frame to NV21");
                return;
            }

            boolean complete = false;
            List<byte[]> copy = null;
            int iso;
            synchronized (burstLock) {
                if (!burstActive) return;
                burstWidth = w;
                burstHeight = h;
                burstFrames.add(nv21);
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
                BurstCallback cb = burstCallback;
                burstCallback = null;
                if (cb != null) cb.onBurstFailed("未收到图像帧");
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
            if (callback != null) callback.onCameraError("录像器未就绪");
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
            if (callback != null) callback.onCameraError("开始录像失败");
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
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            if (mPendingTexture != null) {
                createPreviewSession(mPendingTexture);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            if (callback != null) callback.onCameraError("相机断开连接");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            String errorMsg;
            switch (error) {
                case ERROR_CAMERA_DEVICE: errorMsg = "相机设备错误"; break;
                case ERROR_CAMERA_DISABLED: errorMsg = "相机被禁用"; break;
                case ERROR_CAMERA_IN_USE: errorMsg = "相机被占用"; break;
                case ERROR_CAMERA_SERVICE: errorMsg = "相机服务错误"; break;
                default: errorMsg = "未知相机错误: " + error; break;
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
                                afterAfLocked();
                            }
                            break;
                        }
                        case STATE_WAITING_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                currentState = STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        }
                        case STATE_WAITING_NON_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
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
            if (callback != null) callback.onCameraError("相机权限未授予");
            return;
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
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
                if (callback != null) callback.onCameraError("未找到可用相机");
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
            if (callback != null) callback.onCameraError("打开相机失败: " + e.getMessage());
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
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
        List<Size> candidates = new ArrayList<>();
        for (Size s : choices) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels <= CAPTURE_MAX_PIXELS && s.getWidth() >= 1280) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
        // Prefer 4:3 closest to 8–12MP
        Size best = null;
        for (Size s : candidates) {
            float ratio = (float) s.getWidth() / s.getHeight();
            if (Math.abs(ratio - 4f / 3f) < 0.05f) {
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        if (best != null) return best;
        return Collections.max(candidates, new CompareSizesByArea());
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
