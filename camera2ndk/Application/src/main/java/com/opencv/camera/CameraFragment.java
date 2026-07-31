package com.opencv.camera;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraMetadata;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
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
import android.view.animation.DecelerateInterpolator;
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
import java.nio.ByteBuffer;
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
    private ImageView btnHdr;
    private EvSliderView evSlider;
    private TextView evLabel;
    private TextView aeLockHint;

    // Pro mode panel
    private LinearLayout proPanel;
    private LinearLayout proSliderRow;
    private LinearLayout zoomPresets;
    private HistogramView histogramView;
    private ArcSliderView proArc;
    private TextView proValue;
    private TextView proAuto;
    private TextView modePortrait;
    private TextView modePro;
    private TextView[] proChips = new TextView[5];
    private int activeProParam = 0; // 0 none,1 iso,2 shutter,3 ev,4 focus,5 wb
    private int awbCycleIndex = 0;
    private long histogramLastTime = 0;
    private float evStep = 1f / 3f; // actual EV step in stops, read from characteristics
    private TextView hdrLabel;
    private TextView zoomPreset1;
    private TextView zoomPreset2;
    private TextView zoomPreset5;
    private TextView isoLabel;
    private TextView zoomLabel;
    private TextView modePhoto;
    private TextView modeVideo;
    private View modeIndicator;
    private boolean modeIndicatorInitialized = false;
    private LinearLayout recordingIndicator;
    private TextView recordingTime;
    private ConstraintLayout topBar;
    private ConstraintLayout bottomBar;
    private View modeContainer;

    private CameraEngine cameraEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean faceBusy = new AtomicBoolean(false);
    private final Runnable faceBusyTimeout = () -> {
        if (faceBusy.get()) {
            Log.w(TAG, "faceBusy timeout, force reset");
            faceBusy.set(false);
        }
    };
    private static final long BURST_INTERVAL_MS = 800;
    private final AtomicBoolean isLongPressCapturing = new AtomicBoolean(false);
    private final Runnable burstCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isLongPressCapturing.get()) return;
            if (cameraEngine == null || uiMode != CameraEngine.MODE_PHOTO) {
                isLongPressCapturing.set(false);
                return;
            }
            if (isCapturing) {
                // Capture still in progress, retry shortly
                mainHandler.postDelayed(this, 100);
                return;
            }
            doCapture();
            mainHandler.postDelayed(this, BURST_INTERVAL_MS);
        }
    };
    private MediaActionSound shutterSound;

    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private final float[] gravityValues = new float[3];
    private final SensorEventListener levelerListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GRAVITY
                    || event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, gravityValues, 0, 3);
                updateRollFromGravity();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void updateRollFromGravity() {
        if (cameraOverlay == null) return;
        float gx = gravityValues[0];
        float gy = gravityValues[1];
        int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
        float roll;
        switch (rotation) {
            case Surface.ROTATION_90:
                roll = (float) Math.toDegrees(Math.atan2(-gy, -gx));
                break;
            case Surface.ROTATION_180:
                roll = (float) Math.toDegrees(Math.atan2(-gx, gy));
                break;
            case Surface.ROTATION_270:
                roll = (float) Math.toDegrees(Math.atan2(gy, gx));
                break;
            case Surface.ROTATION_0:
            default:
                roll = (float) Math.toDegrees(Math.atan2(gx, -gy));
                break;
        }
        cameraOverlay.setRoll(roll);
    }

    private static final int ASPECT_FULL = 0;
    private static final int ASPECT_1_1 = 1;
    private static final int ASPECT_16_9 = 2;
    private static final int ASPECT_4_3 = 3;

    private static final int HDR_OFF = 0;
    private static final int HDR_AUTO = 1;
    private static final int HDR_ON = 2;

    private boolean isCapturing = false;
    private int timerSeconds = 0;
    private int aspectMode = ASPECT_FULL;
    private boolean isAiEnabled = false;
    private boolean isGridVisible = false;
    private boolean faceDetectEnabled = true;
    private boolean shutterSoundEnabled = true;
    private static final int MODE_PORTRAIT = 2;
    private static final int MODE_PRO = 3;
    private int uiMode = CameraEngine.MODE_PHOTO;
    private int flashMode = CameraEngine.FLASH_OFF;
    private int hdrMode = HDR_AUTO;
    private final CameraMediaStore cameraMediaStore = new CameraMediaStore();
    private long activePhotoCaptureId = 0L;

    // Stable Java facade; all algorithm decisions and processing live in C++.
    private volatile CameraAlgoSdk cameraAlgoSdk;

    private int topBarPadL, topBarPadT, topBarPadR, topBarPadB;
    private int bottomBarPadL, bottomBarPadT, bottomBarPadR, bottomBarPadB;

    private float currentZoom = 1.0f;
    private boolean scalingInProgress = false;
    private long lastScaleEndTime = 0;
    private boolean controlsShown = false;
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
        initAlgorithmSdk();
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
        btnHdr = root.findViewById(R.id.btn_hdr);
        hdrLabel = root.findViewById(R.id.hdr_label);
        isoLabel = root.findViewById(R.id.iso_label);
        zoomLabel = root.findViewById(R.id.zoom_label);
        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);

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
        btnShutter.setOnLongClickListener(v -> {
            if (uiMode != CameraEngine.MODE_PHOTO || isCapturing) return false;
            if (cameraEngine == null) return false;
            isLongPressCapturing.set(true);
            btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mainHandler.post(burstCaptureRunnable);
            return true;
        });
        btnShutter.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (isLongPressCapturing.getAndSet(false)) {
                    mainHandler.removeCallbacks(burstCaptureRunnable);
                }
            }
            return false;
        });
        btnSwitchCamera.setOnClickListener(v -> onSwitchCamera());
        thumbnail.setOnClickListener(v -> onThumbnailClicked());
        btnFlash.setOnClickListener(v -> cycleFlash());
        btnTimer.setOnClickListener(v -> cycleTimer());
        btnAi.setOnClickListener(v -> toggleAiMode());
        aspectRatioButton.setOnClickListener(v -> cycleAspectRatio());
        btnSettings.setOnClickListener(v -> openSettings());
        btnHdr.setOnClickListener(v -> cycleHdr());
        zoomPreset1 = root.findViewById(R.id.zoom_preset_1);
        zoomPreset2 = root.findViewById(R.id.zoom_preset_2);
        zoomPreset5 = root.findViewById(R.id.zoom_preset_5);
        zoomPreset1.setOnClickListener(v -> applyZoom(1f));
        zoomPreset2.setOnClickListener(v -> applyZoom(2f));
        zoomPreset5.setOnClickListener(v -> applyZoom(5f));
        syncZoomPresets();

        evSlider = root.findViewById(R.id.ev_slider);
        evLabel = root.findViewById(R.id.ev_label);
        aeLockHint = root.findViewById(R.id.ae_lock_hint);
        evSlider.setOnEvChangeListener(value -> {
            if (cameraEngine != null) {
                cameraEngine.setExposureCompensation(value);
            }
            updateEvLabel(value);
        });
        updateEvLabel(cameraEngine != null ? cameraEngine.getExposureCompensation() : 0);
        updateEvSliderVisibility();

        // Pro panel bindings
        proPanel = root.findViewById(R.id.pro_panel);
        proSliderRow = root.findViewById(R.id.pro_slider_row);
        histogramView = root.findViewById(R.id.histogram_view);
        proArc = root.findViewById(R.id.pro_arc);
        proValue = root.findViewById(R.id.pro_value);
        proAuto = root.findViewById(R.id.pro_auto);
        modePortrait = root.findViewById(R.id.mode_portrait);
        modePro = root.findViewById(R.id.mode_pro);
        proChips[0] = root.findViewById(R.id.pro_chip_iso);
        proChips[1] = root.findViewById(R.id.pro_chip_shutter);
        proChips[2] = root.findViewById(R.id.pro_chip_ev);
        proChips[3] = root.findViewById(R.id.pro_chip_focus);
        proChips[4] = root.findViewById(R.id.pro_chip_wb);
        for (int i = 0; i < proChips.length; i++) {
            final int idx = i + 1;
            proChips[i].setOnClickListener(v -> selectProParam(idx));
        }
        proAuto.setOnClickListener(v -> applyProAuto());
        proArc.setOnArcChangeListener((value, fromUser) -> onProArcChanged(value));
        zoomPresets = root.findViewById(R.id.zoom_presets);

        updateAiModeUi();
        updateAspectRatioUi();
        updateHdrUi();
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
            cameraEngine.setFlashMode(flashMode);
        }
        scheduleSessionControlUpdate();
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

    private void initAlgorithmSdk() {
        processExecutor.execute(() -> {
            try {
                String modelDir = ModelAssets.ensureModelDir(requireContext());
                cameraAlgoSdk = CameraAlgoSdk.create(modelDir);
                pushSessionControl();
                Log.i(TAG, "Native algorithm engine created, modelDir=" + modelDir);
            } catch (Exception e) {
                Log.e(TAG, "Native algorithm engine initialization failed", e);
            }
        });
    }

    private SessionControl buildSessionControl() {
        SessionControl control = new SessionControl();
        control.mode = uiMode == CameraEngine.MODE_VIDEO
                ? SessionControl.MODE_VIDEO : SessionControl.MODE_PHOTO;
        control.lensFacing = cameraEngine != null && cameraEngine.isFrontCamera() ? 2 : 1;
        control.flashMode = flashMode;
        control.preferFaceDetect = faceDetectEnabled;
        control.preferDenoise = true;
        control.preferSharpen = true;
        control.preferHdr = hdrMode != HDR_OFF;
        control.preferClahe = isAiEnabled;
        control.preferSaturation = isAiEnabled;
        control.preferBokeh = uiMode == MODE_PORTRAIT;
        control.jpegQuality = 95;
        control.analysisMaxSide = 320;
        return control;
    }

    private void pushSessionControl() {
        CameraAlgoSdk sdk = cameraAlgoSdk;
        if (sdk == null) return;
        try {
            sdk.updateSessionControl(buildSessionControl());
        } catch (IllegalStateException e) {
            Log.w(TAG, "Native engine is not ready for session control", e);
        }
    }

    private void scheduleSessionControlUpdate() {
        if (processExecutor.isShutdown()) return;
        processExecutor.execute(this::pushSessionControl);
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
        cameraEngine.setFaceDetectEnabled(false);
        scheduleSessionControlUpdate();
        cameraEngine.startCamera();
        if (textureView.isAvailable()) {
            cameraEngine.createPreviewSession(textureView.getSurfaceTexture());
        }
    }

    // ---------- CameraCallback ----------

    @Override
    public void onCameraOpened(Size previewSize) {
        Log.i(TAG, "Camera opened, preview=" + previewSize);
        Size captureSize = cameraEngine != null ? cameraEngine.getCaptureSize() : null;
        processExecutor.execute(() -> {
            CameraAlgoSdk sdk = cameraAlgoSdk;
            if (sdk == null || previewSize == null || captureSize == null) return;
            try {
                sdk.configurePreview(
                        previewSize.getWidth(), previewSize.getHeight(),
                        CameraAlgoSdk.FORMAT_YUV_420_888, 10);
                sdk.configureCapture(
                        captureSize.getWidth(), captureSize.getHeight(),
                        CameraAlgoSdk.FORMAT_YUV_420_888);
                sdk.updateSessionControl(buildSessionControl());
                Log.i(TAG, "Native pipelines configured: preview=" + previewSize
                        + " capture=" + captureSize);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to configure native pipelines", e);
            }
        });
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
            if (cameraEngine != null) {
                Range<Integer> evRange = cameraEngine.getExposureCompensationRange();
                if (evRange != null) {
                    evSlider.setRange(evRange.getLower(), evRange.getUpper());
                    evSlider.setProgress(cameraEngine.getExposureCompensation());
                    updateEvLabel(cameraEngine.getExposureCompensation());
                    evStep = cameraEngine.getExposureCompensationStep();
                    if (cameraEngine.isAeLocked()) {
                        cameraEngine.lockAe();
                    }
                }
            }
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
                // Update focus ring color based on result: green for success, red for failure
                int colorRes = success ? R.color.gc_face_rect : R.color.gc_shutter_recording;
                focusRing.setColorFilter(
                        ContextCompat.getColor(requireContext(), colorRes),
                        PorterDuff.Mode.SRC_ATOP);
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
            isoLabel.setText(String.format(Locale.US, "ISO %d", iso));
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
        boolean faceAcquired = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            if (!faceBusy.compareAndSet(false, true)) {
                image.close();
                return;
            }
            faceAcquired = true;
            // Post timeout to prevent permanent faceBusy deadlock if processExecutor is interrupted
            mainHandler.postDelayed(faceBusyTimeout, 500);

            HardwareBuffer hwBuf = null;
            FrameMetadata meta = null;
            if (CameraAlgoSdk.supportsHardwareBuffer()) {
                hwBuf = image.getHardwareBuffer();
                meta = cameraEngine.metadataForTimestamp(image.getTimestamp());
            }

            final int imgW = image.getWidth();
            final int imgH = image.getHeight();
            updateHistogram(image, imgW, imgH);
            image.close();
            image = null;

            if (hwBuf == null) {
                mainHandler.removeCallbacks(faceBusyTimeout);
                faceBusy.set(false);
                return;
            }

            final HardwareBuffer finalBuf = hwBuf;
            final FrameMetadata finalMeta = meta;
            processExecutor.execute(() -> {
                try {
                    CameraAlgoSdk sdk = cameraAlgoSdk;
                    if (sdk == null || !sdk.isReady()) return;
                    CameraAlgoSdk.PreviewFrameResult result =
                            sdk.processPreview(finalBuf, finalMeta);
                    if (result.status == CameraAlgoSdk.STATUS_SKIPPED
                            || result.status == CameraAlgoSdk.STATUS_NOT_READY) {
                        return;
                    }
                    float[] faceData = result.faces;
                    mainHandler.post(() -> {
                        if (!isAdded() || cameraOverlay == null) return;
                        cameraOverlay.setPreviewSize(imgW, imgH);
                        cameraOverlay.setFaces(parseFaceResults(faceData));
                    });
                } finally {
                    finalBuf.close();
                    mainHandler.removeCallbacks(faceBusyTimeout);
                    faceBusy.set(false);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "preview face detect error", e);
        } finally {
            if (faceAcquired) {
                mainHandler.removeCallbacks(faceBusyTimeout);
                faceBusy.set(false);
            }
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    };

    private void updateHistogram(Image image, int w, int h) {
        if (histogramView == null || uiMode != MODE_PRO) return;
        long now = System.currentTimeMillis();
        if (now - histogramLastTime < 80) return;
        histogramLastTime = now;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int step = Math.max(1, (w * h) / 250000);
            int[] hist = new int[256];
            for (int y = 0; y < h; y += step) {
                int rowOff = y * rowStride;
                for (int x = 0; x < w; x += step) {
                    int v = buf.get(rowOff + x * pixelStride) & 0xFF;
                    hist[v]++;
                }
            }
            final int[] fhist = hist;
            mainHandler.post(() -> histogramView.setHistogram(fhist));
        } catch (Exception e) {
            Log.e(TAG, "updateHistogram failed", e);
        }
    }

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

    /**
     * Public entry used by MainActivity to map hardware volume keys to a shutter press.
     */
    public void triggerVolumeShutter() {
        onShutterClick();
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
        try {
            saveProgress.setVisibility(View.VISIBLE);
            animateCaptureFlash();
            if (shutterSoundEnabled && shutterSound != null) {
                try {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK);
                } catch (Exception ignored) {
                }
            }

            int iso = cameraEngine.getCurrentIso();
            FrameMetadata currentMeta =
                    cameraEngine.extractMetadata(cameraEngine.getLastCaptureResult());
            currentMeta.approximate = true;
            CameraAlgoSdk sdk = cameraAlgoSdk;
            int frames = sdk != null && sdk.isReady()
                    ? sdk.adviseCaptureFrameCount(currentMeta) : 1;
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

            cameraEngine.captureStillBurst(frames, new CameraEngine.BurstCallback() {
                @Override
                public void onBurstComplete(List<byte[]> nv21Frames, int width, int height, int captureIso) {
                    // Legacy fallback: only used when HardwareBuffer is unavailable
                    if (!CameraAlgoSdk.supportsHardwareBuffer()) {
                        Log.d(TAG, "Falling back to legacy NV21 path");
                        long captureId = activePhotoCaptureId;
                        processExecutor.execute(() -> processAndSaveLegacy(captureId, nv21Frames, width, height, captureIso));
                    }
                }

                @Override
                public void onBurstFailed(String reason) {
                    Log.e(TAG, "Burst failed: " + reason);
                    mainHandler.post(() -> finishCaptureWithError(reason));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "doCapture failed", e);
            finishCaptureWithError(e.getMessage());
        }
    }

    /**
     * Centralized error handler for capture failures.
     * Ensures isCapturing and UI state are always reset on error paths.
     */
    private void finishCaptureWithError(String reason) {
        isCapturing = false;
        saveProgress.setVisibility(View.GONE);
        processingIndicator.setVisibility(View.GONE);
        renderThumbnailState(cameraMediaStore.setPhotoSaveFailed(activePhotoCaptureId, null), false);
        showError(getString(R.string.error_capture_failed) + ": " + reason);
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
            CameraAlgoSdk sdk = cameraAlgoSdk;
            byte[] jpeg = sdk != null && sdk.isReady()
                    ? sdk.processCapture(buffers, metadataList, 95)
                    : null;
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
            List<FrameMetadata> metadata = new ArrayList<>(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                FrameMetadata frameMetadata = new FrameMetadata();
                frameMetadata.iso = iso;
                frameMetadata.approximate = true;
                metadata.add(frameMetadata);
            }
            CameraAlgoSdk sdk = cameraAlgoSdk;
            byte[] jpeg = sdk != null && sdk.isReady()
                    ? sdk.processCaptureNv21(frames, metadata, width, height, 95)
                    : null;
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
        scheduleSessionControlUpdate();
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

    private void cycleHdr() {
        hdrMode = (hdrMode + 1) % 3;
        updateHdrUi();
        scheduleSessionControlUpdate();
        bounce(btnHdr);
    }

    private void updateHdrUi() {
        switch (hdrMode) {
            case HDR_ON:
                btnHdr.setImageResource(R.drawable.ic_hdr_on);
                hdrLabel.setText(R.string.hdr_on);
                break;
            case HDR_AUTO:
                btnHdr.setImageResource(R.drawable.ic_hdr_auto);
                hdrLabel.setText(R.string.hdr_auto);
                break;
            case HDR_OFF:
            default:
                btnHdr.setImageResource(R.drawable.ic_hdr_off);
                hdrLabel.setText(R.string.hdr_off);
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
        scheduleSessionControlUpdate();
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
                .scaleX(0.82f).scaleY(0.82f)
                .rotation(2f)
                .setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> thumbnail.animate()
                        .scaleX(1f).scaleY(1f)
                        .rotation(0f)
                        .setDuration(220)
                        .setInterpolator(new OvershootInterpolator(1.6f))
                        .start())
                .start();
    }

    private void setupGestureDetectors() {
        GestureDetector gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (cameraEngine != null && cameraEngine.isAeLocked()) {
                            cameraEngine.unlockAe();
                            aeLockHint.setVisibility(View.GONE);
                        }
                        showFocusRing(e.getX(), e.getY());
                        if (cameraEngine != null) {
                            cameraEngine.focusOnPoint(
                                    e.getX(), e.getY(),
                                    textureView.getWidth(), textureView.getHeight());
                        }
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                        // Ignore flings that arrive while/after a pinch-zoom so lifting one
                        // finger of a two-finger gesture can't accidentally switch mode.
                        if (scalingInProgress) return false;
                        if (e1 == null || e2 == null) return false;
                        if (SystemClock.elapsedRealtime() - lastScaleEndTime < 250) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        // Horizontal swipe switches capture mode; ignore diagonal gestures.
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80 && Math.abs(velocityX) > 300) {
                            if (dx < 0) {
                                selectMode(CameraEngine.MODE_VIDEO);
                            } else {
                                selectMode(CameraEngine.MODE_PHOTO);
                            }
                            btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (cameraEngine == null) return false;
                        float targetZoom = (Math.abs(currentZoom - 1f) < 0.2f) ? 2f : 1f;
                        applyZoom(targetZoom);
                        btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        showFocusRing(e.getX(), e.getY());
                        if (cameraEngine != null) {
                            cameraEngine.focusOnPoint(
                                    e.getX(), e.getY(),
                                    textureView.getWidth(), textureView.getHeight());
                            cameraEngine.lockAe();
                        }
                        aeLockHint.setVisibility(View.VISIBLE);
                        btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                });

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scalingInProgress = true;
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        scalingInProgress = false;
                        lastScaleEndTime = SystemClock.elapsedRealtime();
                    }

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
                        syncZoomPresets();
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
        // Reset to default focus ring color (yellow)
        focusRing.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.gc_focus_ring),
                PorterDuff.Mode.SRC_ATOP);
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

    private void applyZoom(float z) {
        if (cameraEngine == null) return;
        cameraEngine.setZoom(z);
        currentZoom = cameraEngine.getCurrentZoom();
        zoomLabel.setVisibility(View.VISIBLE);
        zoomLabel.setText(String.format(Locale.US, "%.1fx", currentZoom));
        mainHandler.removeCallbacks(hideZoomLabel);
        mainHandler.postDelayed(hideZoomLabel, 1200);
        syncZoomPresets();
    }

    private void syncZoomPresets() {
        float z = cameraEngine != null ? cameraEngine.getCurrentZoom() : currentZoom;
        highlightPreset(zoomPreset1, Math.abs(z - 1f) < 0.15f);
        highlightPreset(zoomPreset2, Math.abs(z - 2f) < 0.15f);
        highlightPreset(zoomPreset5, Math.abs(z - 5f) < 0.15f);
    }

    private void highlightPreset(TextView view, boolean active) {
        if (view == null) return;
        int color = ContextCompat.getColor(requireContext(),
                active ? R.color.gc_accent : R.color.gc_primary);
        view.setTextColor(color);
    }

    private void setupModeSelector() {
        modePhoto.setOnClickListener(v -> selectMode(CameraEngine.MODE_PHOTO));
        modePortrait.setOnClickListener(v -> selectMode(MODE_PORTRAIT));
        modeVideo.setOnClickListener(v -> selectMode(CameraEngine.MODE_VIDEO));
        modePro.setOnClickListener(v -> selectMode(MODE_PRO));
    }

    private void selectMode(int mode) {
        if (uiMode == mode) return;
        if (cameraEngine != null && cameraEngine.isRecording()) return;
        if (isCapturing) return;

        int prevMode = uiMode;
        Log.i(TAG, "Mode switch: " + modeName(mode));
        uiMode = mode;
        if (prevMode == MODE_PRO && cameraEngine != null) {
            // Leaving Pro: restore auto exposure/focus/WB so other modes behave normally
            cameraEngine.setAutoExposure();
            cameraEngine.setAutoFocus();
            cameraEngine.setWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO);
            awbCycleIndex = 0;
        }
        updateModeUi();
        scheduleSessionControlUpdate();

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

    private String modeName(int mode) {
        switch (mode) {
            case CameraEngine.MODE_PHOTO: return "PHOTO";
            case MODE_PORTRAIT: return "PORTRAIT";
            case CameraEngine.MODE_VIDEO: return "VIDEO";
            case MODE_PRO: return "PRO";
            default: return "?";
        }
    }

    /**
     * Animate the mode indicator to center under the active tab.
     * Uses translationX instead of ConstraintLayout constraints because the mode tabs
     * are inside a LinearLayout (mode_container), so ConstraintLayout
     * cannot reliably constrain to them directly.
     * On first call, the indicator is positioned immediately without animation.
     */
    private void animateModeIndicator(TextView activeTab) {
        if (modeIndicator == null || activeTab == null) return;
        modeIndicator.post(() -> {
            // Compute the active tab's center X in window coordinates
            int[] tabPos = new int[2];
            activeTab.getLocationInWindow(tabPos);
            if (tabPos[0] == 0 && activeTab.getWidth() == 0) {
                // View not yet laid out, retry on next frame
                modeIndicator.post(() -> animateModeIndicator(activeTab));
                return;
            }
            float tabCenterX = tabPos[0] + activeTab.getWidth() / 2f;

            // Compute the indicator's original (non-translated) center X in window coordinates
            int[] indicatorPos = new int[2];
            modeIndicator.getLocationInWindow(indicatorPos);
            float indicatorOriginalCenterX =
                    indicatorPos[0] + modeIndicator.getWidth() / 2f - modeIndicator.getTranslationX();

            // Calculate how far we need to translate the indicator
            float targetTranslationX = tabCenterX - indicatorOriginalCenterX;

            if (!modeIndicatorInitialized) {
                // First time: set position immediately, no animation
                modeIndicator.setTranslationX(targetTranslationX);
                modeIndicatorInitialized = true;
            } else {
                // Animate with smooth deceleration
                modeIndicator.animate()
                        .translationX(targetTranslationX)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            }
        });
    }

    private void updateModeUi() {
        if (modePhoto == null) return;
        TextView[] tabs = {modePhoto, modePortrait, modeVideo, modePro};
        int[] modes = {CameraEngine.MODE_PHOTO, MODE_PORTRAIT, CameraEngine.MODE_VIDEO, MODE_PRO};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextAppearance(uiMode == modes[i]
                    ? R.style.ModeSelectorText_Selected : R.style.ModeSelectorText);
        }
        TextView active = (uiMode == MODE_PORTRAIT) ? modePortrait
                : (uiMode == CameraEngine.MODE_VIDEO) ? modeVideo
                : (uiMode == MODE_PRO) ? modePro : modePhoto;
        animateModeIndicator(active);

        boolean isVideo = uiMode == CameraEngine.MODE_VIDEO;
        btnTimer.setVisibility(isVideo ? View.GONE : View.VISIBLE);
        if (timerLabel != null) timerLabel.setVisibility(isVideo ? View.GONE : View.VISIBLE);

        boolean isPro = uiMode == MODE_PRO;
        if (proPanel != null) proPanel.setVisibility(isPro ? View.VISIBLE : View.GONE);
        if (zoomPresets != null) zoomPresets.setVisibility(isPro ? View.GONE : View.VISIBLE);
        if (isPro && proChips != null) {
            for (TextView chip : proChips) chip.setAlpha(1f);
        }
        if (!isPro) {
            activeProParam = 0;
            if (proSliderRow != null) proSliderRow.setVisibility(View.GONE);
        }

        if (cameraEngine != null) {
            onIsoUpdated(cameraEngine.getCurrentIso());
        }
        if (uiMode != CameraEngine.MODE_PHOTO
                && uiMode != MODE_PORTRAIT
                && cameraEngine != null && cameraEngine.isAeLocked()) {
            cameraEngine.unlockAe();
            aeLockHint.setVisibility(View.GONE);
        }
        updateEvSliderVisibility();
    }

    private void updateEvSliderVisibility() {
        if (evSlider == null) return;
        // EV slider only visible in Photo and Portrait modes.
        // Hidden in Video mode (no EV adjustment) and Pro mode (has dedicated EV chip).
        boolean show = uiMode == CameraEngine.MODE_PHOTO || uiMode == MODE_PORTRAIT;
        int vis = show ? View.VISIBLE : View.GONE;
        evSlider.setVisibility(vis);
        evLabel.setVisibility(vis);
    }

    // ---------- Pro mode panel ----------

    private void selectProParam(int param) {
        if (cameraEngine == null) return;
        activeProParam = param;
        proSliderRow.setVisibility(View.VISIBLE);
        proArc.setEnabled(true);
        for (TextView chip : proChips) {
            chip.setAlpha(0.55f);
        }
        proChips[param - 1].setAlpha(1f);

        switch (param) {
            case 1: { // ISO
                Range<Integer> r = cameraEngine.getIsoRange();
                if (r != null) {
                    proArc.setLogarithmic(true);
                    proArc.setRange(r.getLower(), r.getUpper());
                    proArc.setValue(cameraEngine.isManualExposure()
                            ? cameraEngine.getManualIso() : cameraEngine.getCurrentIso());
                }
                proArc.setFormatter(v -> "ISO " + (int) Math.round(v));
                break;
            }
            case 2: { // Shutter
                Range<Long> r = cameraEngine.getShutterRange();
                if (r != null) {
                    proArc.setLogarithmic(true);
                    proArc.setRange(r.getLower(), r.getUpper());
                    proArc.setValue(cameraEngine.isManualExposure()
                            ? cameraEngine.getManualShutterNs() : 33_333_333L);
                }
                proArc.setFormatter(v -> formatShutter((long) v));
                break;
            }
            case 3: { // EV (only effective while AE is on)
                Range<Integer> r = cameraEngine.getExposureCompensationRange();
                if (r != null) {
                    proArc.setLogarithmic(false);
                    proArc.setRange(r.getLower(), r.getUpper());
                    proArc.setValue(cameraEngine.getExposureCompensation());
                }
                proArc.setFormatter(v -> String.format(Locale.US, "EV %+.1f", v * evStep));
                proArc.setEnabled(!cameraEngine.isManualExposure());
                break;
            }
            case 4: { // Focus: 0 = infinity (far end), minFocusDistance = closest
                float maxF = Math.max(cameraEngine.getMinimumFocusDistance(), 0.05f);
                proArc.setLogarithmic(false);
                proArc.setRange(0f, maxF); // 0 = infinity, maxF = closest focus
                proArc.setValue(cameraEngine.isManualFocus()
                        ? cameraEngine.getManualFocusDistance() : 0f);
                proArc.setFormatter(v -> (v <= 0.01f) ? "∞" : String.format(Locale.US, "%.2f m", v));
                break;
            }
            case 5: { // WB cycle
                proSliderRow.setVisibility(View.GONE);
                cycleWhiteBalance();
                return;
            }
        }
        updateProValueLabel();
    }

    private void onProArcChanged(double value) {
        if (cameraEngine == null) return;
        switch (activeProParam) {
            case 1:
                cameraEngine.setManualExposure((int) Math.round(value), cameraEngine.getManualShutterNs());
                break;
            case 2:
                cameraEngine.setManualExposure(cameraEngine.getManualIso(), (long) value);
                break;
            case 3:
                if (cameraEngine.isManualExposure()) { updateProValueLabel(); return; }
                cameraEngine.setExposureCompensation((int) Math.round(value));
                break;
            case 4:
                cameraEngine.setManualFocusDistance((float) value);
                break;
        }
        updateProValueLabel();
    }

    private void applyProAuto() {
        if (cameraEngine == null) return;
        switch (activeProParam) {
            case 1:
            case 2:
                cameraEngine.setAutoExposure();
                break;
            case 3:
                cameraEngine.setExposureCompensation(0);
                break;
            case 4:
                cameraEngine.setAutoFocus();
                break;
            case 5:
                cameraEngine.setWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO);
                awbCycleIndex = 0;
                proValue.setText(awbName(CameraMetadata.CONTROL_AWB_MODE_AUTO));
                return;
        }
        selectProParam(activeProParam);
        proValue.setText("自动");
    }

    private void cycleWhiteBalance() {
        if (cameraEngine == null) return;
        int[] modes = cameraEngine.getAvailableAwbModes();
        if (modes.length == 0) return;
        awbCycleIndex = (awbCycleIndex + 1) % modes.length;
        int mode = modes[awbCycleIndex];
        cameraEngine.setWhiteBalance(mode);
        proValue.setText(awbName(mode));
        for (TextView chip : proChips) chip.setAlpha(0.55f);
        proChips[4].setAlpha(1f);
        proSliderRow.setVisibility(View.GONE);
    }

    private void updateProValueLabel() {
        if (proValue == null || cameraEngine == null) return;
        switch (activeProParam) {
            case 1:
                proValue.setText("ISO " + (cameraEngine.isManualExposure()
                        ? cameraEngine.getManualIso() : cameraEngine.getCurrentIso()));
                break;
            case 2:
                proValue.setText(formatShutter(cameraEngine.isManualExposure()
                        ? cameraEngine.getManualShutterNs() : 33_333_333L));
                break;
            case 3:
                if (cameraEngine.isManualExposure()) {
                    proValue.setText("手动曝光下无效");
                    break;
                }
                proValue.setText(String.format(Locale.US, "EV %+.1f",
                        cameraEngine.getExposureCompensation() * evStep));
                break;
            case 4:
                float maxF = Math.max(cameraEngine.getMinimumFocusDistance(), 0.05f);
                float d = cameraEngine.isManualFocus() ? cameraEngine.getManualFocusDistance() : 0f;
                proValue.setText(d <= 0.01f ? "∞" : String.format(Locale.US, "%.2f m", d));
                break;
        }
    }

    private String formatShutter(long ns) {
        if (ns <= 0) return "1/∞ s";
        double sec = ns / 1_000_000_000.0;
        if (sec >= 1.0) return String.format(Locale.US, "%.1f s", sec);
        return "1/" + (int) Math.round(1.0 / sec) + " s";
    }

    private String awbName(int mode) {
        switch (mode) {
            case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT: return "白炽灯";
            case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT: return "日光";
            case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "阴天";
            case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT: return "荧光灯";
            case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "暖荧光";
            case CameraMetadata.CONTROL_AWB_MODE_SHADE: return "阴影";
            case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT: return "黄昏";
            default: return "自动";
        }
    }

    private void updateEvLabel(int ev) {
        if (evLabel != null) {
            evLabel.setText(String.format(Locale.US, "EV %+.1f", ev * evStep));
        }
    }

    private void animateControlsIn() {
        if (topBar == null) return;
        if (controlsShown) {
            // Already shown (e.g. after a mode switch / returning from background):
            // keep controls visible instead of re-fading, which would flicker.
            topBar.setAlpha(1f);
            bottomBar.setAlpha(1f);
            modeContainer.setAlpha(1f);
            topBar.setTranslationY(0f);
            bottomBar.setTranslationY(0f);
            return;
        }
        controlsShown = true;
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
        registerLevelerSensor();
        if (textureView != null && textureView.isAvailable()) {
            openCameraWithPermission();
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        unregisterLevelerSensor();
        if (cameraEngine != null) {
            if (cameraEngine.isRecording()) {
                cameraEngine.stopRecording();
            }
            cameraEngine.stopCamera();
        }
        mainHandler.removeCallbacks(recordingTick);
        super.onPause();
    }

    private void registerLevelerSensor() {
        if (sensorManager == null) {
            sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        if (gravitySensor == null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravitySensor == null) {
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        if (gravitySensor != null) {
            sensorManager.registerListener(levelerListener, gravitySensor,
                    SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void unregisterLevelerSensor() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(levelerListener);
        }
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView, releasing native resources");
        CameraAlgoSdk sdk = cameraAlgoSdk;
        cameraAlgoSdk = null;
        if (sdk != null) {
            sdk.close();
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
