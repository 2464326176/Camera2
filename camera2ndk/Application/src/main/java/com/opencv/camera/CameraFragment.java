package com.opencv.camera;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Photo / video dual-mode camera UI.
 * Preview: OpenCV YuNet face detection
 * Capture: ISO-adaptive single / 3–6 frame OpenCV denoising
 */
public class CameraFragment extends Fragment implements CameraEngine.CameraCallback {

    private static final String TAG = "CameraFragment";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final long FACE_DETECT_INTERVAL_MS = 180;

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    private AutoFitTextureView textureView;
    private CameraOverlayView cameraOverlay;
    private ImageView focusRing;
    private View captureFlash;
    private TextView countdownText;
    private ImageView btnShutter;
    private ImageView btnSwitchCamera;
    private ImageView thumbnail;
    private ProgressBar saveProgress;
    private View processingIndicator;
    private TextView processingText;
    private ImageView btnFlash;
    private TextView flashLabel;
    private ImageView btnTimer;
    private TextView timerLabel;
    private TextView btnAi;
    private TextView aspectRatioButton;
    private ImageView btnSettings;
    private TextView isoLabel;
    private TextView zoomLabel;
    private TextView modePhoto;
    private TextView modeVideo;
    private View modeIndicator;
    private LinearLayout recordingIndicator;
    private TextView recordingTime;
    private ConstraintLayout topBar;
    private ConstraintLayout bottomBar;
    private View modeContainer;

    private CameraEngine cameraEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean faceBusy = new AtomicBoolean(false);
    private MediaActionSound shutterSound;

    private static final int ASPECT_FULL = 0;
    private static final int ASPECT_1_1 = 1;
    private static final int ASPECT_16_9 = 2;
    private static final int ASPECT_4_3 = 3;

    private boolean isCapturing = false;
    private int timerSeconds = 0;
    private int aspectMode = ASPECT_FULL;
    private boolean isAiEnabled = false;
    private boolean isGridVisible = false;
    private boolean faceDetectEnabled = true;
    private boolean shutterSoundEnabled = true;
    private int uiMode = CameraEngine.MODE_PHOTO;
    private int flashMode = CameraEngine.FLASH_OFF;
    private final CameraMediaStore cameraMediaStore = new CameraMediaStore();
    private long activePhotoCaptureId = 0L;

    // NativeEngine handles
    private long mEngineHandle = 0;
    private long mPreviewPipeline = 0;
    private long mCapturePipeline = 0;

    private int topBarPadL, topBarPadT, topBarPadR, topBarPadB;
    private int bottomBarPadL, bottomBarPadT, bottomBarPadR, bottomBarPadB;

    private long lastFaceDetectTime = 0;
    private float currentZoom = 1.0f;
    private long recordingStartElapsed = 0;
    private final Runnable recordingTick = new Runnable() {
        @Override
        public void run() {
            if (cameraEngine == null || !cameraEngine.isRecording()) return;
            long sec = (SystemClock.elapsedRealtime() - recordingStartElapsed) / 1000;
            long m = sec / 60;
            long s = sec % 60;
            if (recordingTime != null) {
                recordingTime.setText(String.format(Locale.US, "%02d:%02d", m, s));
            }
            mainHandler.postDelayed(this, 500);
        }
    };

    private final Runnable hideZoomLabel = () -> {
        if (zoomLabel != null) zoomLabel.setVisibility(View.GONE);
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        initViews(view);
        applyWindowInsets(view);
        applyPreferences();
        initCamera();
        initNativeEngine();
        setupGestureDetectors();
        setupModeSelector();
        updateModeUi();
        updateFlashUi();

        getParentFragmentManager().addOnBackStackChangedListener(() -> {
            if (!isAdded()) return;
            if (getParentFragmentManager().getBackStackEntryCount() == 0) {
                applyPreferences();
            }
        });
    }

    private void initViews(View root) {
        textureView = root.findViewById(R.id.texture_view);
        cameraOverlay = root.findViewById(R.id.camera_overlay);
        focusRing = root.findViewById(R.id.focus_ring);
        captureFlash = root.findViewById(R.id.capture_flash);
        countdownText = root.findViewById(R.id.countdown_text);

        btnShutter = root.findViewById(R.id.btn_shutter);
        btnSwitchCamera = root.findViewById(R.id.btn_switch_camera);
        thumbnail = root.findViewById(R.id.thumbnail);
        saveProgress = root.findViewById(R.id.save_progress);
        processingIndicator = root.findViewById(R.id.processing_indicator);
        processingText = root.findViewById(R.id.processing_text);

        btnFlash = root.findViewById(R.id.btn_flash);
        flashLabel = root.findViewById(R.id.flash_label);
        btnTimer = root.findViewById(R.id.btn_timer);
        timerLabel = root.findViewById(R.id.timer_label);
        btnAi = root.findViewById(R.id.btn_ai);
        aspectRatioButton = root.findViewById(R.id.aspect_ratio_button);
        btnSettings = root.findViewById(R.id.btn_settings);
        isoLabel = root.findViewById(R.id.iso_label);
        zoomLabel = root.findViewById(R.id.zoom_label);

        modePhoto = root.findViewById(R.id.mode_photo);
        modeVideo = root.findViewById(R.id.mode_video);
        modeIndicator = root.findViewById(R.id.mode_indicator);
        recordingIndicator = root.findViewById(R.id.recording_indicator);
        recordingTime = root.findViewById(R.id.recording_time);

        topBar = root.findViewById(R.id.top_bar);
        bottomBar = root.findViewById(R.id.bottom_bar);
        modeContainer = root.findViewById(R.id.mode_container);

        topBarPadL = topBar.getPaddingLeft();
        topBarPadT = topBar.getPaddingTop();
        topBarPadR = topBar.getPaddingRight();
        topBarPadB = topBar.getPaddingBottom();
        bottomBarPadL = bottomBar.getPaddingLeft();
        bottomBarPadT = bottomBar.getPaddingTop();
        bottomBarPadR = bottomBar.getPaddingRight();
        bottomBarPadB = bottomBar.getPaddingBottom();

        btnShutter.setOnClickListener(v -> onShutterClick());
        btnSwitchCamera.setOnClickListener(v -> onSwitchCamera());
        thumbnail.setOnClickListener(v -> onThumbnailClicked());
        btnFlash.setOnClickListener(v -> cycleFlash());
        btnTimer.setOnClickListener(v -> cycleTimer());
        btnAi.setOnClickListener(v -> toggleAiMode());
        aspectRatioButton.setOnClickListener(v -> cycleAspectRatio());
        btnSettings.setOnClickListener(v -> openSettings());
        updateAiModeUi();
        updateAspectRatioUi();
    }

    private void applyWindowInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            if (topBar != null) {
                topBar.setPadding(topBarPadL, topBarPadT + bars.top,
                        topBarPadR, topBarPadB);
            }
            if (bottomBar != null) {
                bottomBar.setPadding(bottomBarPadL, bottomBarPadT,
                        bottomBarPadR, bottomBarPadB + nav.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void applyPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        faceDetectEnabled = prefs.getBoolean(SettingsFragment.KEY_FACE_DETECT, true);
        shutterSoundEnabled = prefs.getBoolean(SettingsFragment.KEY_SOUND, true);
        boolean gridDefault = prefs.getBoolean(SettingsFragment.KEY_GRID_DEFAULT, false);
        isGridVisible = gridDefault;
        if (cameraOverlay != null) {
            cameraOverlay.setGridVisible(isGridVisible);
        }
        if (!faceDetectEnabled && cameraOverlay != null) {
            cameraOverlay.clearFaces();
        }
        if (cameraEngine != null) {
            cameraEngine.setFaceDetectEnabled(faceDetectEnabled);
            cameraEngine.setFlashMode(flashMode);
        }
    }

    private void initCamera() {
        cameraEngine = new CameraEngine(requireContext(), previewImageListener);
        cameraEngine.setCallback(this);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openCameraWithPermission();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                configureTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (cameraEngine != null) {
                    cameraEngine.stopCamera();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
    }

    private void initNativeEngine() {
        processExecutor.execute(() -> {
            String modelDir = requireContext().getApplicationContext().getFilesDir().getAbsolutePath();
            mEngineHandle = NativeEngine.getInstance().nativeCreateEngine(modelDir);
            mPreviewPipeline = NativeEngine.getInstance().nativeCreatePreviewPipeline(
                    mEngineHandle, 1280, 720, NativeEngine.FORMAT_NV21, 10);
            mCapturePipeline = NativeEngine.getInstance().nativeCreateCapturePipeline(
                    mEngineHandle, 1280, 720, NativeEngine.FORMAT_NV21);
            Log.i(TAG, "Native engine initialized: engine=" + mEngineHandle
                    + " preview=" + mPreviewPipeline + " capture=" + mCapturePipeline);
        });
    }

    private void openCameraWithPermission() {
        String[] needed = requiredPermissions();
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(requireContext(), p)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + missing);
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startCameraSession();
    }

    private String[] requiredPermissions() {
        if (uiMode == CameraEngine.MODE_VIDEO) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        }
        return new String[]{Manifest.permission.CAMERA};
    }

    private void startCameraSession() {
        if (cameraEngine == null || textureView == null) return;
        Log.d(TAG, "startCameraSession mode=" + uiMode);
        cameraEngine.setCaptureMode(uiMode == CameraEngine.MODE_VIDEO
                ? CameraEngine.MODE_VIDEO
                : CameraEngine.MODE_PHOTO);
        cameraEngine.setFlashMode(flashMode);
        cameraEngine.setFaceDetectEnabled(faceDetectEnabled);
        cameraEngine.startCamera();
        if (textureView.isAvailable()) {
            cameraEngine.createPreviewSession(textureView.getSurfaceTexture());
        }
    }

    // ---------- CameraCallback ----------

    @Override
    public void onCameraOpened(Size previewSize) {
        Log.i(TAG, "Camera opened, preview=" + previewSize);
        mainHandler.post(() -> {
            if (!isAdded() || previewSize == null) return;
            // Keep TextureView filling parent; transform applies aspect-preserving center crop.
            textureView.setAspectRatio(0, 0);
            cameraOverlay.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
            cameraOverlay.setCameraInfo(
                    cameraEngine.getSensorOrientation(),
                    cameraEngine.isFrontCamera());
            configureTransform(textureView.getWidth(), textureView.getHeight());
            animateControlsIn();
        });
    }

    @Override
    public void onCameraClosed() {
        Log.i(TAG, "Camera closed");
    }

    @Override
    public void onCameraError(String message) {
        mainHandler.post(() -> {
            Log.e(TAG, "Camera error: " + message);
            if (isAdded()) {
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                        .setAction("Retry", v -> openCameraWithPermission())
                        .show();
            }
        });
    }

    @Override
    public void onAutoFocusComplete(boolean success) {
        mainHandler.post(() -> {
            if (focusRing.getVisibility() == View.VISIBLE) {
                focusRing.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .setStartDelay(400)
                        .withEndAction(() -> focusRing.setVisibility(View.GONE))
                        .start();
            }
        });
    }

    @Override
    public void onIsoUpdated(int iso) {
        mainHandler.post(() -> {
            if (isoLabel == null) return;
            if (uiMode == CameraEngine.MODE_PHOTO) {
                int frames = cameraEngine.frameCountForIso(iso);
                isoLabel.setText(String.format(Locale.US, "ISO %d · %df", iso, frames));
            } else {
                isoLabel.setText(String.format(Locale.US, "ISO %d", iso));
            }
        });
    }

    @Override
    public void onVideoRecordingStarted(File file) {
        mainHandler.post(() -> {
            recordingStartElapsed = SystemClock.elapsedRealtime();
            recordingIndicator.setVisibility(View.VISIBLE);
            btnShutter.setBackgroundResource(R.drawable.bg_shutter_recording);
            modeContainer.setAlpha(0.35f);
            modeContainer.setEnabled(false);
            btnSwitchCamera.setEnabled(false);
            mainHandler.removeCallbacks(recordingTick);
            mainHandler.post(recordingTick);
        });
    }

    @Override
    public void onVideoRecordingStopped(File file, boolean success) {
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(recordingTick);
            recordingIndicator.setVisibility(View.GONE);
            btnShutter.setBackgroundResource(R.drawable.selector_shutter);
            modeContainer.setAlpha(1f);
            modeContainer.setEnabled(true);
            btnSwitchCamera.setEnabled(true);

            if (success && file != null) {
                final File videoSrc = file;
                processExecutor.execute(() -> {
                    Bitmap thumb = CameraMediaStore.createVideoThumbnail(videoSrc);
                    Uri uri = CameraMediaStore.saveVideoToGallery(
                            requireContext().getApplicationContext(), videoSrc);
                    mainHandler.post(() -> {
                        if (uri != null) {
                            Bitmap thumbnailBitmap = thumb != null ? createThumbnailBitmap(thumb) : null;
                            renderThumbnailState(cameraMediaStore.setVideoSaved(uri, thumbnailBitmap), true);
                            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
                            showSuccess(getString(R.string.video_saved));
                        } else {
                            showError(getString(R.string.video_save_failed));
                        }
                    });
                });
            } else {
                showError(getString(R.string.video_save_failed));
            }
        });
    }

    // ---------- Preview face detect ----------

    private final ImageReader.OnImageAvailableListener previewImageListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            if (uiMode != CameraEngine.MODE_PHOTO || !faceDetectEnabled) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastFaceDetectTime < FACE_DETECT_INTERVAL_MS) {
                return;
            }
            if (!faceBusy.compareAndSet(false, true)) {
                return;
            }
            lastFaceDetectTime = now;

            // New architecture: zero-copy transfer via HardwareBuffer
            HardwareBuffer hwBuf = null;
            FrameMetadata meta = null;
            if (NativeEngine.supportsHardwareBuffer()) {
                hwBuf = image.getHardwareBuffer();
                meta = cameraEngine.extractMetadata(cameraEngine.getLastCaptureResult());
            }

            final int imgW = image.getWidth();
            final int imgH = image.getHeight();
            image.close();
            image = null;

            if (hwBuf == null) {
                faceBusy.set(false);
                return;
            }

            final HardwareBuffer finalBuf = hwBuf;
            final FrameMetadata finalMeta = meta;
            processExecutor.execute(() -> {
                try {
                    float[] faceData = NativeEngine.getInstance().nativeProcessPreviewFrame(
                            mPreviewPipeline, finalBuf, finalMeta);
                    mainHandler.post(() -> {
                        if (!isAdded() || cameraOverlay == null) return;
                        if (!faceDetectEnabled || uiMode != CameraEngine.MODE_PHOTO) {
                            cameraOverlay.clearFaces();
                            return;
                        }
                        cameraOverlay.setPreviewSize(imgW, imgH);
                        cameraOverlay.setFaces(parseFaceResults(faceData));
                    });
                } finally {
                    finalBuf.close();
                    faceBusy.set(false);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "preview face detect error", e);
            faceBusy.set(false);
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    };

    // ---------- Shutter / Capture ----------

    private void onShutterClick() {
        Log.d(TAG, "Shutter clicked, mode=" + (uiMode == CameraEngine.MODE_VIDEO ? "VIDEO" : "PHOTO"));
        if (uiMode == CameraEngine.MODE_VIDEO) {
            toggleVideoRecording();
            return;
        }

        if (isCapturing) return;
        btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        animateShutterPress();

        if (timerSeconds > 0) {
            startCountdown(timerSeconds);
        } else {
            doCapture();
        }
    }

    private void toggleVideoRecording() {
        if (cameraEngine == null) return;
        btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (cameraEngine.isRecording()) {
            Log.i(TAG, "Stopping video recording");
            cameraEngine.stopRecording();
        } else {
            // Ensure mic permission
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
                return;
            }
            Log.i(TAG, "Starting video recording");
            cameraEngine.startRecording();
        }
    }

    private void doCapture() {
        if (cameraEngine == null || isCapturing) return;
        isCapturing = true;
        saveProgress.setVisibility(View.VISIBLE);
        animateCaptureFlash();
        if (shutterSoundEnabled && shutterSound != null) {
            try {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK);
            } catch (Exception ignored) {
            }
        }

        int iso = cameraEngine.getCurrentIso();
        int frames = cameraEngine.frameCountForIso(iso);
        activePhotoCaptureId = cameraMediaStore.beginPhotoCapture();
        renderThumbnailState(cameraMediaStore.getCurrentState(), false);
        Log.i(TAG, "Capture started: iso=" + iso + " frames=" + frames + " captureId=" + activePhotoCaptureId);

        // Stage 1: Capture preview frame as instant thumbnail
        capturePreviewAsThumbnail(activePhotoCaptureId);

        processingText.setText(frames <= 1
                ? getString(R.string.capture_processing)
                : getString(R.string.capture_frames, frames));
        processingIndicator.setVisibility(View.VISIBLE);

        cameraEngine.setFrameCallback(new CameraEngine.FrameCallback() {
            @Override
            public void onFirstCaptureFrame(byte[] nv21Data, int width, int height, FrameMetadata metadata) {
                // Stage 2: First capture frame arrived — update thumbnail
                Log.d(TAG, "First capture frame: " + width + "x" + height + " iso=" + metadata.iso);
                long captureId = activePhotoCaptureId;
                processExecutor.execute(() -> updateThumbnailFromCaptureFrame(captureId, nv21Data, width, height));
            }

            @Override
            public void onBurstComplete(List<HardwareBuffer> buffers, List<FrameMetadata> metadataList) {
                // Stage 3: All frames ready — run algorithm post-processing
                Log.i(TAG, "Burst complete: " + buffers.size() + " frames, starting post-processing");
                long captureId = activePhotoCaptureId;
                processExecutor.execute(() -> processAndSave(captureId, buffers, metadataList));
            }
        });

        cameraEngine.captureStillBurst(new CameraEngine.BurstCallback() {
            @Override
            public void onBurstComplete(List<byte[]> nv21Frames, int width, int height, int captureIso) {
                // Legacy fallback: only used when HardwareBuffer is unavailable
                if (!NativeEngine.supportsHardwareBuffer()) {
                    Log.d(TAG, "Falling back to legacy NV21 path");
                    long captureId = activePhotoCaptureId;
                    processExecutor.execute(() -> processAndSaveLegacy(captureId, nv21Frames, width, height, captureIso));
                }
            }

            @Override
            public void onBurstFailed(String reason) {
                Log.e(TAG, "Burst failed: " + reason);
                mainHandler.post(() -> {
                    isCapturing = false;
                    saveProgress.setVisibility(View.GONE);
                    processingIndicator.setVisibility(View.GONE);
                    renderThumbnailState(cameraMediaStore.setPhotoSaveFailed(activePhotoCaptureId, null), false);
                    showError(getString(R.string.error_capture_failed) + ": " + reason);
                });
            }
        });
    }

    private void capturePreviewAsThumbnail(long captureId) {
        // Stage 1: Instant thumbnail from current preview frame
        if (textureView == null) return;
        try {
            Bitmap previewBmp = textureView.getBitmap();
            if (previewBmp != null) {
                Bitmap thumbnailBitmap = createThumbnailBitmap(previewBmp);
                renderThumbnailState(cameraMediaStore.setTemporaryPhotoThumbnail(captureId, thumbnailBitmap), true);
                previewBmp.recycle();
                Log.d(TAG, "Preview frame captured as instant thumbnail");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture preview thumbnail", e);
        }
    }

    /**
     * Stage 2: Render the first capture frame as a temporary thumbnail.
     * The temporary frame is not persisted and cannot be opened from the thumbnail.
     */
    private void updateThumbnailFromCaptureFrame(long captureId, byte[] nv21Data, int width, int height) {
        try {
            Log.d(TAG, "Stage 2: rendering first capture frame thumbnail: " + width + "x" + height);
            YuvImage yuv = new YuvImage(nv21Data, ImageFormat.NV21, width, height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 92, out);
            byte[] jpeg = out.toByteArray();

            int orientation = cameraEngine != null ? cameraEngine.getJpegOrientation() : 90;
            Bitmap thumb = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (thumb != null && orientation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(orientation);
                Bitmap rotated = Bitmap.createBitmap(thumb, 0, 0,
                        thumb.getWidth(), thumb.getHeight(), matrix, true);
                if (rotated != thumb) {
                    thumb.recycle();
                    thumb = rotated;
                }
            }
            if (thumb != null) {
                Bitmap thumbnailBitmap = createThumbnailBitmap(thumb);
                thumb.recycle();
                mainHandler.post(() -> {
                    renderThumbnailState(cameraMediaStore.setTemporaryPhotoThumbnail(captureId, thumbnailBitmap), true);
                    Log.d(TAG, "Stage 2: capture frame rendered as temporary thumbnail");
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save capture frame thumbnail", e);
        }
    }

    private void processAndSave(long captureId, List<HardwareBuffer> buffers, List<FrameMetadata> metadataList) {
        try {
            Log.i(TAG, "Stage 3: post-processing " + buffers.size() + " frames via HardwareBuffer");
            byte[] jpeg = NativeEngine.getInstance().nativeProcessCapture(
                    mCapturePipeline,
                    buffers.toArray(new HardwareBuffer[0]),
                    metadataList.toArray(new FrameMetadata[0]),
                    95);
            if (jpeg == null) {
                Log.e(TAG, "Stage 3: nativeProcessCapture returned null");
                mainHandler.post(() -> {
                    isCapturing = false;
                    saveProgress.setVisibility(View.GONE);
                    processingIndicator.setVisibility(View.GONE);
                    showError("Image processing failed");
                });
                return;
            }

            int orientation = cameraEngine != null ? cameraEngine.getJpegOrientation() : 90;

            // Stage 3 saves the final processed image. Temporary thumbnails are not persisted.

            Uri uri = CameraMediaStore.saveJpegToGallery(
                    requireContext().getApplicationContext(), jpeg, "OPENCV", orientation);

            Bitmap thumb = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (thumb != null && orientation != 0) {
                Matrix m = new Matrix();
                m.postRotate(orientation);
                Bitmap rotated = Bitmap.createBitmap(thumb, 0, 0,
                        thumb.getWidth(), thumb.getHeight(), m, true);
                if (rotated != thumb) {
                    thumb.recycle();
                    thumb = rotated;
                }
            }

            Bitmap finalThumb = thumb;
            int frameCount = buffers.size();
            mainHandler.post(() -> {
                isCapturing = false;
                saveProgress.setVisibility(View.GONE);
                processingIndicator.setVisibility(View.GONE);
                if (uri != null) {
                    Bitmap thumbnailBitmap = finalThumb != null ? createThumbnailBitmap(finalThumb) : null;
                    renderThumbnailState(cameraMediaStore.setPhotoSaved(captureId, thumbnailBitmap, uri), false);
                    if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                    Log.i(TAG, "Stage 3: final processed image saved, uri=" + uri);
                    showSuccess(getString(R.string.photo_saved)
                            + " · " + frameCount + " frames");
                } else {
                    Log.e(TAG, "Stage 3: save failed");
                    renderThumbnailState(cameraMediaStore.setPhotoSaveFailed(captureId, finalThumb), false);
                    showError("Save failed");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Stage 3: processAndSave failed", e);
            mainHandler.post(() -> {
                isCapturing = false;
                saveProgress.setVisibility(View.GONE);
                processingIndicator.setVisibility(View.GONE);
                showError(getString(R.string.error_process_exception) + ": " + e.getMessage());
            });
        } finally {
            // Close all HardwareBuffers to prevent resource leaks
            for (HardwareBuffer buf : buffers) {
                try { buf.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Legacy fallback: process via NV21 byte[] when HardwareBuffer is unavailable.
     */
    private void processAndSaveLegacy(long captureId, List<byte[]> frames, int width, int height, int iso) {
        try {
            Log.i(TAG, "Post-processing " + frames.size() + " frames (legacy) @ " + width + "x" + height
                    + " iso=" + iso);
            // Use NativeEngine's fallback path (via DirectByteBuffer)
            // Since NativeEngine doesn't expose a direct NV21 byte[] interface,
            // fall back to Android YuvImage encoding here
            byte[] jpeg = null;
            if (!frames.isEmpty()) {
                YuvImage yuv = new YuvImage(frames.get(0), ImageFormat.NV21, width, height, null);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuv.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 95, out);
                jpeg = out.toByteArray();
            }
            if (jpeg == null) {
                mainHandler.post(() -> {
                    isCapturing = false;
                    saveProgress.setVisibility(View.GONE);
                    processingIndicator.setVisibility(View.GONE);
                    showError("Image processing failed");
                });
                return;
            }

            int orientation = cameraEngine != null ? cameraEngine.getJpegOrientation() : 90;
            Uri uri = CameraMediaStore.saveJpegToGallery(
                    requireContext().getApplicationContext(), jpeg, "OPENCV", orientation);

            Bitmap thumb = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (thumb != null && orientation != 0) {
                Matrix m = new Matrix();
                m.postRotate(orientation);
                Bitmap rotated = Bitmap.createBitmap(thumb, 0, 0,
                        thumb.getWidth(), thumb.getHeight(), m, true);
                if (rotated != thumb) {
                    thumb.recycle();
                    thumb = rotated;
                }
            }

            Bitmap finalThumb = thumb;
            mainHandler.post(() -> {
                isCapturing = false;
                saveProgress.setVisibility(View.GONE);
                processingIndicator.setVisibility(View.GONE);
                if (uri != null) {
                    Bitmap thumbnailBitmap = finalThumb != null ? createThumbnailBitmap(finalThumb) : null;
                    renderThumbnailState(cameraMediaStore.setPhotoSaved(captureId, thumbnailBitmap, uri), false);
                    if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                    showSuccess(getString(R.string.photo_saved)
                            + " · " + frames.size() + " frames ISO" + iso);
                } else {
                    renderThumbnailState(cameraMediaStore.setPhotoSaveFailed(captureId, finalThumb), false);
                    showError("Save failed");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processAndSaveLegacy failed", e);
            mainHandler.post(() -> {
                isCapturing = false;
                saveProgress.setVisibility(View.GONE);
                processingIndicator.setVisibility(View.GONE);
                showError(getString(R.string.error_process_exception) + ": " + e.getMessage());
            });
        }
    }

    private RectF[] parseFaceResults(float[] faceData) {
        if (faceData == null || faceData.length < 15) return new RectF[0];
        int count = faceData.length / 15;
        RectF[] faces = new RectF[count];
        for (int i = 0; i < count; i++) {
            int offset = i * 15;
            float x = faceData[offset];
            float y = faceData[offset + 1];
            float w = faceData[offset + 2];
            float h = faceData[offset + 3];
            faces[i] = new RectF(x, y, x + w, y + h);
        }
        return faces;
    }

    private void startCountdown(int seconds) {
        countdownText.setVisibility(View.VISIBLE);
        countdownText.setText(String.valueOf(seconds));
        countdownText.setAlpha(1f);

        ObjectAnimator scaleAnim = ObjectAnimator.ofFloat(countdownText, "scaleX", 1.5f, 1f);
        scaleAnim.setDuration(800);
        scaleAnim.start();
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(countdownText, "scaleY", 1.5f, 1f);
        scaleY.setDuration(800);
        scaleY.start();

        if (seconds > 1) {
            mainHandler.postDelayed(() -> startCountdown(seconds - 1), 1000);
        } else {
            mainHandler.postDelayed(() -> {
                countdownText.setVisibility(View.GONE);
                doCapture();
            }, 800);
        }
    }

    // ---------- UI controls ----------

    private void onSwitchCamera() {
        if (cameraEngine == null || cameraEngine.isRecording() || isCapturing) return;
        Log.i(TAG, "Switching camera");
        btnSwitchCamera.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        btnSwitchCamera.animate()
                .rotationBy(180)
                .setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        cameraOverlay.clearFaces();
        textureView.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction(() -> {
                    cameraEngine.switchCamera(textureView.getSurfaceTexture());
                    textureView.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .setStartDelay(180)
                            .start();
                })
                .start();
    }

    private void cycleFlash() {
        if (cameraEngine == null) return;
        flashMode = (flashMode + 1) % 3;
        cameraEngine.setFlashMode(flashMode);
        updateFlashUi();
        bounce(btnFlash);
    }

    private void updateFlashUi() {
        switch (flashMode) {
            case CameraEngine.FLASH_ON:
                btnFlash.setImageResource(R.drawable.ic_flash_on);
                flashLabel.setText(R.string.flash_on);
                break;
            case CameraEngine.FLASH_AUTO:
                btnFlash.setImageResource(R.drawable.ic_flash_auto);
                flashLabel.setText(R.string.flash_auto);
                break;
            case CameraEngine.FLASH_OFF:
            default:
                btnFlash.setImageResource(R.drawable.ic_flash_off);
                flashLabel.setText(R.string.flash_off);
                break;
        }
    }

    private void openSettings() {
        if (cameraEngine != null && cameraEngine.isRecording()) return;
        getParentFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .add(R.id.container, SettingsFragment.newInstance())
                .addToBackStack("settings")
                .commit();
    }

    private void cycleTimer() {
        if (timerSeconds == 0) timerSeconds = 3;
        else if (timerSeconds == 3) timerSeconds = 10;
        else timerSeconds = 0;

        if (timerSeconds == 0) timerLabel.setText(R.string.timer_off);
        else if (timerSeconds == 3) timerLabel.setText(R.string.timer_3s);
        else timerLabel.setText(R.string.timer_10s);
        bounce(btnTimer);
    }

    private void toggleAiMode() {
        isAiEnabled = !isAiEnabled;
        updateAiModeUi();
        Log.d(TAG, "AI mode toggled: " + (isAiEnabled ? "open" : "close"));
        bounce(btnAi);
    }

    private void updateAiModeUi() {
        if (btnAi == null) return;
        btnAi.setText(isAiEnabled ? "AI ON" : "AI");
        btnAi.setAlpha(isAiEnabled ? 1f : 0.82f);
    }

    private void cycleAspectRatio() {
        switch (aspectMode) {
            case ASPECT_FULL:
                aspectMode = ASPECT_1_1;
                break;
            case ASPECT_1_1:
                aspectMode = ASPECT_16_9;
                break;
            case ASPECT_16_9:
                aspectMode = ASPECT_4_3;
                break;
            case ASPECT_4_3:
            default:
                aspectMode = ASPECT_FULL;
                break;
        }
        updateAspectRatioUi();
        if (textureView != null) {
            configureTransform(textureView.getWidth(), textureView.getHeight());
        }
        bounce(aspectRatioButton);
    }

    private void updateAspectRatioUi() {
        if (aspectRatioButton == null) return;
        aspectRatioButton.setText(getAspectRatioLabel());
        if (cameraOverlay != null) {
            cameraOverlay.setTargetAspectRatio(getTargetAspectRatio());
        }
    }

    private String getAspectRatioLabel() {
        switch (aspectMode) {
            case ASPECT_1_1:
                return "1:1";
            case ASPECT_16_9:
                return "16:9";
            case ASPECT_4_3:
                return "4:3";
            case ASPECT_FULL:
            default:
                return "FULL";
        }
    }

    private float getTargetAspectRatio() {
        switch (aspectMode) {
            case ASPECT_1_1:
                return 1f;
            case ASPECT_16_9:
                return 16f / 9f;
            case ASPECT_4_3:
                return 4f / 3f;
            case ASPECT_FULL:
            default:
                return 0f;
        }
    }

    private void bounce(View v) {
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                .start();
    }

    private void onThumbnailClicked() {
        CameraMediaStore.ThumbnailState state = cameraMediaStore.getCurrentState();
        Log.d(TAG, "Thumbnail clicked: status=" + state.status + " uri=" + state.uri);
        cameraMediaStore.openCurrentMedia(requireContext());
    }

    private void renderThumbnailState(CameraMediaStore.ThumbnailState state, boolean animate) {
        if (state == null || state.bitmap == null || thumbnail == null) return;
        recycleCurrentThumbnailBitmap(state.bitmap);
        thumbnail.setImageBitmap(state.bitmap);
        if (animate) {
            animateThumbnailUpdate();
        }
    }

    private Bitmap createThumbnailBitmap(Bitmap bitmap) {
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int x = (bitmap.getWidth() - size) / 2;
        int y = (bitmap.getHeight() - size) / 2;
        Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, size, size);
        Bitmap thumbBitmap = Bitmap.createScaledBitmap(cropped, 128, 128, true);
        if (cropped != bitmap) cropped.recycle();
        return thumbBitmap;
    }

    private void recycleCurrentThumbnailBitmap(Bitmap nextBitmap) {
        if (thumbnail.getDrawable() instanceof BitmapDrawable) {
            Bitmap oldBitmap = ((BitmapDrawable) thumbnail.getDrawable()).getBitmap();
            if (oldBitmap != null && oldBitmap != nextBitmap && !oldBitmap.isRecycled()) {
                oldBitmap.recycle();
            }
        }
    }

    private void animateThumbnailUpdate() {
        thumbnail.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .setDuration(90)
                .withEndAction(() -> thumbnail.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new OvershootInterpolator(2f))
                        .start())
                .start();
    }

    private void setupGestureDetectors() {
        GestureDetector gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        showFocusRing(e.getX(), e.getY());
                        if (cameraEngine != null) {
                            cameraEngine.focusOnPoint(
                                    e.getX(), e.getY(),
                                    textureView.getWidth(), textureView.getHeight());
                        }
                        return true;
                    }
                });

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (cameraEngine == null) return false;
                        currentZoom *= detector.getScaleFactor();
                        currentZoom = Math.max(1f,
                                Math.min(currentZoom, cameraEngine.getMaxZoom()));
                        cameraEngine.setZoom(currentZoom);
                        zoomLabel.setVisibility(View.VISIBLE);
                        zoomLabel.setText(String.format(Locale.US, "%.1fx", currentZoom));
                        mainHandler.removeCallbacks(hideZoomLabel);
                        mainHandler.postDelayed(hideZoomLabel, 1200);
                        return true;
                    }
                });

        textureView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);
            return true;
        });
    }

    private void showFocusRing(float x, float y) {
        focusRing.setVisibility(View.VISIBLE);
        focusRing.setAlpha(1f);
        focusRing.setX(x - focusRing.getWidth() / 2f);
        focusRing.setY(y - focusRing.getHeight() / 2f);
        focusRing.setScaleX(1.4f);
        focusRing.setScaleY(1.4f);
        focusRing.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(250)
                .setInterpolator(new OvershootInterpolator(2f))
                .start();
    }

    private void setupModeSelector() {
        modePhoto.setOnClickListener(v -> selectMode(CameraEngine.MODE_PHOTO));
        modeVideo.setOnClickListener(v -> selectMode(CameraEngine.MODE_VIDEO));
    }

    private void selectMode(int mode) {
        if (uiMode == mode) return;
        if (cameraEngine != null && cameraEngine.isRecording()) return;
        if (isCapturing) return;

        Log.i(TAG, "Mode switch: " + (mode == CameraEngine.MODE_PHOTO ? "PHOTO" : "VIDEO"));
        uiMode = mode;
        updateModeUi();

        if (mode == CameraEngine.MODE_VIDEO) {
            cameraOverlay.clearFaces();
            // Request mic early when entering video mode
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            }
        }

        // Rebuild camera session for mode surfaces
        if (cameraEngine != null && textureView.isAvailable()) {
            cameraEngine.stopCamera();
            startCameraSession();
        }
    }

    private void updateModeUi() {
        if (modePhoto == null) return;
        if (uiMode == CameraEngine.MODE_PHOTO) {
            modePhoto.setTextAppearance(R.style.ModeSelectorText_Selected);
            modeVideo.setTextAppearance(R.style.ModeSelectorText);
            btnShutter.setBackgroundResource(R.drawable.selector_shutter);
            btnTimer.setVisibility(View.VISIBLE);
            timerLabel.setVisibility(View.VISIBLE);
            modeIndicator.post(() -> {
                ConstraintLayout.LayoutParams lp =
                        (ConstraintLayout.LayoutParams) modeIndicator.getLayoutParams();
                lp.startToStart = modePhoto.getId();
                lp.endToEnd = modePhoto.getId();
                modeIndicator.setLayoutParams(lp);
            });
        } else {
            modeVideo.setTextAppearance(R.style.ModeSelectorText_Selected);
            modePhoto.setTextAppearance(R.style.ModeSelectorText);
            btnShutter.setBackgroundResource(R.drawable.selector_shutter);
            btnTimer.setVisibility(View.GONE);
            timerLabel.setVisibility(View.GONE);
            modeIndicator.post(() -> {
                ConstraintLayout.LayoutParams lp =
                        (ConstraintLayout.LayoutParams) modeIndicator.getLayoutParams();
                lp.startToStart = modeVideo.getId();
                lp.endToEnd = modeVideo.getId();
                modeIndicator.setLayoutParams(lp);
            });
        }
        if (cameraEngine != null) {
            onIsoUpdated(cameraEngine.getCurrentIso());
        }
    }

    private void animateControlsIn() {
        if (topBar == null) return;
        topBar.setAlpha(0f);
        bottomBar.setAlpha(0f);
        modeContainer.setAlpha(0f);
        topBar.setTranslationY(-40);
        bottomBar.setTranslationY(40);
        topBar.animate().alpha(1f).translationY(0).setDuration(350).setStartDelay(80).start();
        bottomBar.animate().alpha(1f).translationY(0).setDuration(350).setStartDelay(140).start();
        modeContainer.animate().alpha(1f).setDuration(280).setStartDelay(200).start();
    }

    private void animateShutterPress() {
        btnShutter.animate()
                .scaleX(0.88f).scaleY(0.88f)
                .setDuration(90)
                .withEndAction(() -> btnShutter.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new OvershootInterpolator(2f))
                        .start())
                .start();
    }

    private void animateCaptureFlash() {
        captureFlash.setVisibility(View.VISIBLE);
        captureFlash.setAlpha(0.65f);
        captureFlash.animate()
                .alpha(0f)
                .setDuration(180)
                .setStartDelay(30)
                .withEndAction(() -> captureFlash.setVisibility(View.GONE))
                .start();
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || cameraEngine == null
                || cameraEngine.getPreviewSize() == null) return;
        if (viewWidth == 0 || viewHeight == 0) return;

        Log.d(TAG, "configureTransform: view=" + viewWidth + "x" + viewHeight + " preview=" + cameraEngine.getPreviewSize());
        int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
        Size previewSize = cameraEngine.getPreviewSize();
        Matrix matrix = CameraUtils.configureTransform(
                viewWidth, viewHeight,
                previewSize.getWidth(), previewSize.getHeight(),
                rotation,
                cameraEngine.getSensorOrientation(),
                cameraEngine.isFrontCamera(),
                getTargetAspectRatio());
        textureView.setTransform(matrix);
        cameraOverlay.setCameraInfo(
                cameraEngine.getSensorOrientation(),
                cameraEngine.isFrontCamera());
        cameraOverlay.setTargetAspectRatio(getTargetAspectRatio());
        cameraOverlay.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
    }

    private void showError(String msg) {
        if (isAdded()) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSuccess(String msg) {
        if (isAdded()) {
            Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        applyPreferences();
        if (textureView != null && textureView.isAvailable()) {
            openCameraWithPermission();
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        if (cameraEngine != null) {
            if (cameraEngine.isRecording()) {
                cameraEngine.stopRecording();
            }
            cameraEngine.stopCamera();
        }
        mainHandler.removeCallbacks(recordingTick);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView, releasing native resources");
        if (mPreviewPipeline != 0 && mEngineHandle != 0) {
            NativeEngine.getInstance().nativeDestroyPipeline(mEngineHandle, mPreviewPipeline);
        }
        if (mCapturePipeline != 0 && mEngineHandle != 0) {
            NativeEngine.getInstance().nativeDestroyPipeline(mEngineHandle, mCapturePipeline);
        }
        if (mEngineHandle != 0) {
            NativeEngine.getInstance().nativeDestroyEngine(mEngineHandle);
        }
        if (shutterSound != null) {
            shutterSound.release();
            shutterSound = null;
        }
        processExecutor.shutdownNow();
        super.onDestroyView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        boolean cameraOk = true;
        boolean audioOk = true;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            if (Manifest.permission.CAMERA.equals(permissions[i]) && !granted) {
                cameraOk = false;
            }
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && !granted) {
                audioOk = false;
            }
        }

        if (!cameraOk) {
            showError(getString(R.string.permission_camera_rationale));
            if (isAdded()) {
                Snackbar.make(requireView(), R.string.permission_denied, Snackbar.LENGTH_LONG)
                        .setAction(R.string.open_settings, v -> {
                            Intent intent = new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.fromParts("package",
                                    requireContext().getPackageName(), null));
                            startActivity(intent);
                        })
                        .show();
            }
            return;
        }

        if (uiMode == CameraEngine.MODE_VIDEO && !audioOk) {
            showError(getString(R.string.permission_mic_rationale));
            // Stay in video UI but cannot record until granted; still open camera preview
        }

        if (textureView != null && textureView.isAvailable()) {
            startCameraSession();
        }
    }
}
