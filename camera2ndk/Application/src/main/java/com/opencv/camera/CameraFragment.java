package com.opencv.camera;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
 * 照片 / 视频双模式相机 UI。
 * 预览：OpenCV YuNet 人脸框
 * 拍照：按 ISO 单帧 / 3–6 帧 OpenCV 降噪
 */
public class CameraFragment extends Fragment implements CameraController.CameraCallback {

    private static final String TAG = "CameraFragment";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final long FACE_DETECT_INTERVAL_MS = 180;

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    private AutoFitTextureView textureView;
    private FaceOverlayView faceOverlay;
    private GridOverlayView gridOverlay;
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
    private ImageView btnGrid;
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

    private CameraController cameraController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean faceBusy = new AtomicBoolean(false);
    private MediaActionSound shutterSound;

    private boolean isCapturing = false;
    private int timerSeconds = 0;
    private boolean isGridVisible = false;
    private boolean faceDetectEnabled = true;
    private boolean shutterSoundEnabled = true;
    private Uri lastMediaUri = null;
    private int uiMode = ImageProcessor.MODE_PHOTO;
    private int flashMode = CameraController.FLASH_OFF;

    private int topBarPadL, topBarPadT, topBarPadR, topBarPadB;
    private int bottomBarPadL, bottomBarPadT, bottomBarPadR, bottomBarPadB;

    private long lastFaceDetectTime = 0;
    private float currentZoom = 1.0f;
    private long recordingStartElapsed = 0;
    private final Runnable recordingTick = new Runnable() {
        @Override
        public void run() {
            if (cameraController == null || !cameraController.isRecording()) return;
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
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        initViews(view);
        applyWindowInsets(view);
        applyPreferences();
        initCamera();
        initImageProcessor();
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
        faceOverlay = root.findViewById(R.id.face_overlay);
        gridOverlay = root.findViewById(R.id.grid_overlay);
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
        btnGrid = root.findViewById(R.id.btn_grid);
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
        thumbnail.setOnClickListener(v -> onThumbnailClick());
        btnFlash.setOnClickListener(v -> cycleFlash());
        btnTimer.setOnClickListener(v -> cycleTimer());
        btnGrid.setOnClickListener(v -> toggleGrid());
        btnSettings.setOnClickListener(v -> openSettings());
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
        if (gridOverlay != null) {
            gridOverlay.setVisibility(isGridVisible ? View.VISIBLE : View.GONE);
        }
        if (!faceDetectEnabled && faceOverlay != null) {
            faceOverlay.clearFaces();
        }
        if (cameraController != null) {
            cameraController.setFaceDetectEnabled(faceDetectEnabled);
            cameraController.setFlashMode(flashMode);
        }
    }

    private void initCamera() {
        cameraController = new CameraController(requireContext(), previewImageListener);
        cameraController.setCallback(this);

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
                if (cameraController != null) {
                    cameraController.stopCamera();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
    }

    private void initImageProcessor() {
        processExecutor.execute(() -> {
            boolean ok = ImageProcessor.initFaceDetector(requireContext().getApplicationContext());
            Log.i(TAG, "OpenCV face detector init: " + ok);
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
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startCameraSession();
    }

    private String[] requiredPermissions() {
        if (uiMode == ImageProcessor.MODE_VIDEO) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        }
        return new String[]{Manifest.permission.CAMERA};
    }

    private void startCameraSession() {
        if (cameraController == null || textureView == null) return;
        cameraController.setCaptureMode(uiMode == ImageProcessor.MODE_VIDEO
                ? CameraController.MODE_VIDEO
                : CameraController.MODE_PHOTO);
        cameraController.setFlashMode(flashMode);
        cameraController.setFaceDetectEnabled(faceDetectEnabled);
        cameraController.startCamera();
        if (textureView.isAvailable()) {
            cameraController.createPreviewSession(textureView.getSurfaceTexture());
        }
    }

    // ---------- CameraCallback ----------

    @Override
    public void onCameraOpened(Size previewSize) {
        mainHandler.post(() -> {
            if (!isAdded() || previewSize == null) return;
            // Portrait display: swap for aspect if sensor is landscape
            int displayW = previewSize.getHeight();
            int displayH = previewSize.getWidth();
            if (previewSize.getWidth() < previewSize.getHeight()) {
                displayW = previewSize.getWidth();
                displayH = previewSize.getHeight();
            }
            // Full-screen preview — keep TextureView filling parent; transform handles crop
            textureView.setAspectRatio(0, 0);
            faceOverlay.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
            faceOverlay.setCameraInfo(
                    cameraController.getSensorOrientation(),
                    cameraController.isFrontCamera());
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
                        .setAction("重试", v -> openCameraWithPermission())
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
            if (uiMode == ImageProcessor.MODE_PHOTO) {
                int frames = ImageProcessor.frameCountForIso(iso);
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
                    Bitmap thumb = CameraUtils.createVideoThumbnail(videoSrc);
                    Uri uri = CameraUtils.saveVideoToGallery(
                            requireContext().getApplicationContext(), videoSrc);
                    mainHandler.post(() -> {
                        if (uri != null) {
                            lastMediaUri = uri;
                            if (thumb != null) {
                                updateThumbnail(thumb);
                            }
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

            // Photo mode + setting gate only; always close image on early exit
            if (uiMode != ImageProcessor.MODE_PHOTO || !faceDetectEnabled) {
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

            final byte[] nv21 = ImageProcessor.imageToNv21(image);
            final int imgW = image.getWidth();
            final int imgH = image.getHeight();
            image.close();
            image = null;

            if (nv21 == null) {
                faceBusy.set(false);
                return;
            }

            processExecutor.execute(() -> {
                try {
                    RectF[] faces = ImageProcessor.detectFacesNv21(nv21, imgW, imgH);
                    mainHandler.post(() -> {
                        if (!isAdded() || faceOverlay == null) return;
                        if (!faceDetectEnabled || uiMode != ImageProcessor.MODE_PHOTO) {
                            faceOverlay.clearFaces();
                            return;
                        }
                        faceOverlay.setPreviewSize(imgW, imgH);
                        faceOverlay.setFaces(faces);
                    });
                } finally {
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
        if (uiMode == ImageProcessor.MODE_VIDEO) {
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
        if (cameraController == null) return;
        btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (cameraController.isRecording()) {
            cameraController.stopRecording();
        } else {
            // Ensure mic permission
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
                return;
            }
            cameraController.startRecording();
        }
    }

    private void doCapture() {
        if (cameraController == null || isCapturing) return;
        isCapturing = true;
        saveProgress.setVisibility(View.VISIBLE);
        animateCaptureFlash();
        if (shutterSoundEnabled && shutterSound != null) {
            try {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK);
            } catch (Exception ignored) {
            }
        }

        int iso = cameraController.getCurrentIso();
        int frames = ImageProcessor.frameCountForIso(iso);
        processingText.setText(frames <= 1
                ? getString(R.string.capture_processing)
                : getString(R.string.capture_frames, frames));
        processingIndicator.setVisibility(View.VISIBLE);

        cameraController.captureStillBurst(new CameraController.BurstCallback() {
            @Override
            public void onBurstComplete(List<byte[]> nv21Frames, int width, int height, int captureIso) {
                processExecutor.execute(() -> processAndSave(nv21Frames, width, height, captureIso));
            }

            @Override
            public void onBurstFailed(String reason) {
                mainHandler.post(() -> {
                    isCapturing = false;
                    saveProgress.setVisibility(View.GONE);
                    processingIndicator.setVisibility(View.GONE);
                    showError("拍照失败: " + reason);
                });
            }
        });
    }

    private void processAndSave(List<byte[]> frames, int width, int height, int iso) {
        try {
            Log.i(TAG, "Processing " + frames.size() + " frames @ " + width + "x" + height
                    + " iso=" + iso);
            byte[] jpeg = ImageProcessor.denoiseAndEncodeJpeg(frames, width, height, iso, 95);
            if (jpeg == null) {
                mainHandler.post(() -> {
                    isCapturing = false;
                    saveProgress.setVisibility(View.GONE);
                    processingIndicator.setVisibility(View.GONE);
                    showError("图像处理失败");
                });
                return;
            }

            int orientation = cameraController != null ? cameraController.getJpegOrientation() : 90;
            // For front camera mirrored preview, photo usually not mirrored in file
            Uri uri = CameraUtils.saveJpegToGallery(
                    requireContext().getApplicationContext(), jpeg, "OPENCV", orientation);

            Bitmap thumb = ImageProcessor.jpegToBitmap(jpeg);
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
                    lastMediaUri = uri;
                    if (finalThumb != null) {
                        updateThumbnail(finalThumb);
                    }
                    showSuccess(getString(R.string.photo_saved)
                            + " · " + frames.size() + "帧 ISO" + iso);
                } else {
                    showError("保存失败");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processAndSave failed", e);
            mainHandler.post(() -> {
                isCapturing = false;
                saveProgress.setVisibility(View.GONE);
                processingIndicator.setVisibility(View.GONE);
                showError("处理异常: " + e.getMessage());
            });
        }
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
        if (cameraController == null || cameraController.isRecording() || isCapturing) return;
        btnSwitchCamera.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        btnSwitchCamera.animate()
                .rotationBy(180)
                .setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        faceOverlay.clearFaces();
        textureView.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction(() -> {
                    cameraController.switchCamera(textureView.getSurfaceTexture());
                    textureView.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .setStartDelay(180)
                            .start();
                })
                .start();
    }

    private void cycleFlash() {
        if (cameraController == null) return;
        flashMode = (flashMode + 1) % 3;
        cameraController.setFlashMode(flashMode);
        updateFlashUi();
        bounce(btnFlash);
    }

    private void updateFlashUi() {
        switch (flashMode) {
            case CameraController.FLASH_ON:
                btnFlash.setImageResource(R.drawable.ic_flash_on);
                flashLabel.setText(R.string.flash_on);
                break;
            case CameraController.FLASH_AUTO:
                btnFlash.setImageResource(R.drawable.ic_flash_auto);
                flashLabel.setText(R.string.flash_auto);
                break;
            case CameraController.FLASH_OFF:
            default:
                btnFlash.setImageResource(R.drawable.ic_flash_off);
                flashLabel.setText(R.string.flash_off);
                break;
        }
    }

    private void openSettings() {
        if (cameraController != null && cameraController.isRecording()) return;
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

    private void toggleGrid() {
        isGridVisible = !isGridVisible;
        if (isGridVisible) {
            gridOverlay.setVisibility(View.VISIBLE);
            gridOverlay.cycleGridMode();
        } else {
            gridOverlay.setVisibility(View.GONE);
        }
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(SettingsFragment.KEY_GRID_DEFAULT, isGridVisible)
                .apply();
        bounce(btnGrid);
    }

    private void bounce(View v) {
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                .start();
    }

    private void onThumbnailClick() {
        if (lastMediaUri != null) {
            CameraUtils.openLatestPhoto(requireContext(), lastMediaUri);
        } else {
            CameraUtils.openGallery(requireContext());
        }
    }

    private void updateThumbnail(Bitmap bitmap) {
        if (bitmap == null || thumbnail == null) return;
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int x = (bitmap.getWidth() - size) / 2;
        int y = (bitmap.getHeight() - size) / 2;
        Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, size, size);
        Bitmap thumbBitmap = Bitmap.createScaledBitmap(cropped, 128, 128, true);
        thumbnail.setImageBitmap(thumbBitmap);
        if (cropped != bitmap) cropped.recycle();

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
                        if (cameraController != null) {
                            cameraController.focusOnPoint(
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
                        if (cameraController == null) return false;
                        currentZoom *= detector.getScaleFactor();
                        currentZoom = Math.max(1f,
                                Math.min(currentZoom, cameraController.getMaxZoom()));
                        cameraController.setZoom(currentZoom);
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
        modePhoto.setOnClickListener(v -> selectMode(ImageProcessor.MODE_PHOTO));
        modeVideo.setOnClickListener(v -> selectMode(ImageProcessor.MODE_VIDEO));
    }

    private void selectMode(int mode) {
        if (uiMode == mode) return;
        if (cameraController != null && cameraController.isRecording()) return;
        if (isCapturing) return;

        uiMode = mode;
        ImageProcessor.setProcessMode(mode);
        updateModeUi();

        if (mode == ImageProcessor.MODE_VIDEO) {
            faceOverlay.clearFaces();
            // Request mic early when entering video mode
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            }
        }

        // Rebuild camera session for mode surfaces
        if (cameraController != null && textureView.isAvailable()) {
            cameraController.stopCamera();
            startCameraSession();
        }
    }

    private void updateModeUi() {
        if (modePhoto == null) return;
        if (uiMode == ImageProcessor.MODE_PHOTO) {
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
        if (cameraController != null) {
            onIsoUpdated(cameraController.getCurrentIso());
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
        if (textureView == null || cameraController == null
                || cameraController.getPreviewSize() == null) return;
        if (viewWidth == 0 || viewHeight == 0) return;

        int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
        Size previewSize = cameraController.getPreviewSize();
        Matrix matrix = CameraUtils.configureTransform(
                viewWidth, viewHeight,
                previewSize.getWidth(), previewSize.getHeight(),
                rotation,
                cameraController.getSensorOrientation(),
                cameraController.isFrontCamera());
        textureView.setTransform(matrix);
        faceOverlay.setCameraInfo(
                cameraController.getSensorOrientation(),
                cameraController.isFrontCamera());
        faceOverlay.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
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
        applyPreferences();
        if (textureView != null && textureView.isAvailable()) {
            openCameraWithPermission();
        }
    }

    @Override
    public void onPause() {
        if (cameraController != null) {
            if (cameraController.isRecording()) {
                cameraController.stopRecording();
            }
            cameraController.stopCamera();
        }
        mainHandler.removeCallbacks(recordingTick);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        ImageProcessor.releaseFaceDetector();
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

        if (uiMode == ImageProcessor.MODE_VIDEO && !audioOk) {
            showError(getString(R.string.permission_mic_rationale));
            // Stay in video UI but cannot record until granted; still open camera preview
        }

        if (textureView != null && textureView.isAvailable()) {
            startCameraSession();
        }
    }
}
