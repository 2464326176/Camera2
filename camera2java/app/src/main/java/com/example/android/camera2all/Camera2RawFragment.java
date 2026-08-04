/*
 * Copyright 2015 The Android Open Source Project
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

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2Raw: captures both RAW (DNG) and JPEG images with 3A convergence and reference-counted
 * resource management.
 */
public class Camera2RawFragment extends Fragment implements View.OnClickListener, FlashControl {

    // Maps device screen rotation to the JPEG orientation value required by the camera sensor.
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    // Maximum time (ms) to wait for the AE precapture sequence to converge before giving up.
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;
    // Allowed relative difference when comparing two aspect ratios for equality.
    private static final double ASPECT_RATIO_TOLERANCE = 0.005;
    // Log tag for this fragment.
    private static final String TAG = "Camera2RawFragment";
    // Tag used to identify the permission / error dialog fragment in the child FragmentManager.
    private static final String FRAGMENT_DIALOG = "dialog";

    // State machine: camera device is closed.
    private static final int STATE_CLOSED = 0;
    // State machine: camera device is open but no active session/preview.
    private static final int STATE_OPENED = 1;
    // State machine: camera is streaming the preview.
    private static final int STATE_PREVIEW = 2;
    // State machine: waiting for the AF/AE/AWB (3A) convergence before capturing.
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;

    // Listens to device orientation changes to set the correct JPEG capture rotation.
    private OrientationEventListener mOrientationListener;

    // Listens for the preview surface becoming available / resized to drive the transform.
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        // Called when the preview surface is created; recomputes the preview transform.
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        // Called when the preview surface changes size; recomputes the preview transform.
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        // Called when the preview surface is destroyed; clears the cached preview size.
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            synchronized (mCameraStateLock) {
                mPreviewSize = null;
            }
            return true;
        }

        @Override
        // Called for every new preview frame; invokes the single-frame algorithm here (preview
        // frame processing before the UI render).
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            // === Algorithm pseudo-interface: single-frame processing (preview frame callback) ===
            // Call timing: every preview frame arrives, before the TextureView render update
            // (runs on the camera background thread).
            // Data flow: preview frame (NV21) -> processFrame() -> processed Bitmap
            if (mSingleFrameAlgorithm != null && mPreviewSize != null) {
                mSingleFrameAlgorithm.processFrame(
                        null, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            }
        }

    };

    // === Algorithm pseudo-interface hooks (mock, no real algorithm integrated) ===
    // Single-frame algorithm: invoked in the preview frame callback, completes before the UI render.
    private final CameraAlgorithm.SingleFrameAlgorithm mSingleFrameAlgorithm =
            new CameraAlgorithm.MockSingleFrameAlgorithm();

    // Custom TextureView that keeps a correct aspect ratio for the camera preview.
    private AutoFitTextureView mTextureView;
    // Background thread that handles camera callbacks off the UI thread.
    private HandlerThread mBackgroundThread;
    // Monotonic counter used to tag capture requests so their callbacks can be matched.
    private final AtomicInteger mRequestCounter = new AtomicInteger();
    // Guards open/close of the camera device so it cannot be opened twice or closed while in use.
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    // Lock protecting shared camera state fields read/written from multiple threads.
    private final Object mCameraStateLock = new Object();

    // Id of the back-facing camera chosen for this fragment.
    private String mCameraId;
    // Active capture session used to issue repeating preview and one-shot capture requests.
    private CameraCaptureSession mCaptureSession;
    // Handle to the opened camera device, null when closed.
    private CameraDevice mCameraDevice;
    // Size selected for the preview stream.
    private Size mPreviewSize;
    // Static characteristics (capabilities, orientation, AF modes) of the selected camera.
    private CameraCharacteristics mCharacteristics;
    // Current lens facing (back or front); toggled by the reverse button.
    private int mCurrentFacing = CameraCharacteristics.LENS_FACING_BACK;
    // Selected flash mode, synced from the top-bar flash button via FlashControl.
    private int mFlashMode = FlashControl.FLASH_AUTO;
    // Whether the selected camera supports flash.
    private boolean mFlashSupported;
    // Thumbnail button showing the last captured JPEG; click opens the full image.
    private ImageButton mThumbnail;
    // Most recently captured JPEG file, used by the thumbnail button.
    private File mLastJpegFile;
    // Handler bound to the background thread's looper.
    private Handler mBackgroundHandler;
    // Reference-counted reader that receives JPEG frames captured by the camera.
    private RefCountedAutoCloseable<ImageReader> mJpegImageReader;
    // Reference-counted reader that receives RAW (Bayer) frames captured by the camera.
    private RefCountedAutoCloseable<ImageReader> mRawImageReader;
    // True when an AF run must be skipped (e.g. AF is not available for the current 3A state).
    private boolean mNoAFRun = false;
    // Number of captures still queued by the user but not yet completed.
    private int mPendingUserCaptures = 0;
    // Maps request tag -> in-progress JPEG image-saver builder, ordered by capture request id.
    private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mJpegResultQueue = new TreeMap<>();
    // Maps request tag -> in-progress RAW image-saver builder, ordered by capture request id.
    private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mRawResultQueue = new TreeMap<>();
    // Builder for the repeating preview capture request.
    private CaptureRequest.Builder mPreviewRequestBuilder;
    // Current state of the capture state machine (see STATE_* constants).
    private int mState = STATE_CLOSED;
    // Timestamp (SystemClock.elapsedRealtime) recording when the precapture wait began.
    private long mCaptureTimer;

    // Receives camera device open/close lifecycle events and drives the capture session.
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        // Camera opened successfully: mark OPENED, release the lock and start the preview session.
        public void onOpened(CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_OPENED;
                mCameraOpenCloseLock.release();
                mCameraDevice = cameraDevice;

                if (mPreviewSize != null && mTextureView.isAvailable()) {
                    createCameraPreviewSessionLocked();
                }
            }
        }

        @Override
        // Camera was disconnected (e.g. unplugged): mark CLOSED and release the lock/device.
        public void onDisconnected(CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        // A fatal error occurred: log it, mark CLOSED, release the lock and finish the activity.
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Received camera device error: " + error);
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
            }
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    // Notified when a JPEG frame is ready; dequeues it and dispatches the save on the background thread.
    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader);
        }

    };

    // Notified when a RAW frame is ready; dequeues it and dispatches the save on the background thread.
    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
        }

    };

    // Receives capture results during the precapture/3A-convergence wait and drives the state machine.
    private final CameraCaptureSession.CaptureCallback mPreCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        // Advances the capture state machine according to the latest AF/AE/AWB convergence states.
        private void process(CaptureResult result) {
            synchronized (mCameraStateLock) {
                switch (mState) {
                    case STATE_PREVIEW: {
                        break;
                    }
                    case STATE_WAITING_FOR_3A_CONVERGENCE: {
                        boolean readyToCapture = true;
                        if (!mNoAFRun) {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                break;
                            }

                            readyToCapture =
                                    (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                        }

                        if (!isLegacyLocked()) {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                            if (aeState == null || awbState == null) {
                                break;
                            }

                            readyToCapture = readyToCapture &&
                                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
                        }

                        if (!readyToCapture && hitTimeoutLocked()) {
                            Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                            readyToCapture = true;
                        }

                        if (readyToCapture && mPendingUserCaptures > 0) {
                            while (mPendingUserCaptures > 0) {
                                captureStillPictureLocked();
                                mPendingUserCaptures--;
                            }
                            mState = STATE_PREVIEW;
                        }
                    }
                }
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }

    };

    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                     long timestamp, long frameNumber) {
            String currentDateTime = generateTimestamp();
            // Use the app-specific DCIM directory: writable on all API levels without
            // holding WRITE_EXTERNAL_STORAGE, and still visible to MediaScanner.
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            File dcimDir = activity.getExternalFilesDir(Environment.DIRECTORY_DCIM);
            if (dcimDir == null) {
                dcimDir = activity.getExternalFilesDir(null);
            }
            File rawFile = new File(dcimDir, "RAW_" + currentDateTime + ".dng");
            File jpegFile = new File(dcimDir, "JPEG_" + currentDateTime + ".jpg");

            ImageSaver.ImageSaverBuilder jpegBuilder;
            ImageSaver.ImageSaverBuilder rawBuilder;
            int requestId = (int) request.getTag();
            synchronized (mCameraStateLock) {
                jpegBuilder = mJpegResultQueue.get(requestId);
                rawBuilder = mRawResultQueue.get(requestId);
            }

            if (jpegBuilder != null) jpegBuilder.setFile(jpegFile);
            if (rawBuilder != null) rawBuilder.setFile(rawFile);
            mLastJpegFile = jpegFile;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            int requestId = (int) request.getTag();
            ImageSaver.ImageSaverBuilder jpegBuilder;
            ImageSaver.ImageSaverBuilder rawBuilder;
            StringBuilder sb = new StringBuilder();

            synchronized (mCameraStateLock) {
                jpegBuilder = mJpegResultQueue.get(requestId);
                rawBuilder = mRawResultQueue.get(requestId);

                if (jpegBuilder != null) {
                    jpegBuilder.setResult(result);
                    sb.append("Saving JPEG as: ");
                    sb.append(jpegBuilder.getSaveLocation());
                }
                if (rawBuilder != null) {
                    rawBuilder.setResult(result);
                    if (jpegBuilder != null) sb.append(", ");
                    sb.append("Saving RAW as: ");
                    sb.append(rawBuilder.getSaveLocation());
                }

                handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue);
                handleCompletionLocked(requestId, rawBuilder, mRawResultQueue);

                finishedCaptureLocked();
            }

            showToast(sb.toString());

            // Refresh the thumbnail a moment later, once the JPEG has been flushed to disk.
            final File jf = mLastJpegFile;
            if (jf != null && mBackgroundHandler != null && mThumbnail != null) {
                mBackgroundHandler.postDelayed(() -> CameraUtils.updateImageThumbnail(
                        jf, mThumbnail, mBackgroundHandler, new Handler(Looper.getMainLooper())), 500);
            }
        }

        @Override
        // A one-shot capture failed: drop the queued builders, run post-capture cleanup and notify.
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                    CaptureFailure failure) {
            int requestId = (int) request.getTag();
            synchronized (mCameraStateLock) {
                mJpegResultQueue.remove(requestId);
                mRawResultQueue.remove(requestId);
                finishedCaptureLocked();
            }
            showToast("Capture failed!");
        }

    };

    // Runs on the main looper and shows toast messages posted from background threads.
    private final Handler mMessageHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, (String) msg.obj, Toast.LENGTH_SHORT).show();
            }
        }
    };

    // Factory method that returns a new fragment instance.
    public static Camera2RawFragment newInstance() {
        return new Camera2RawFragment();
    }

    @Override
    // Inflates the fragment layout.
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_raw, container, false);
    }

    @Override
    // Wires up the capture/reverse/thumbnail buttons, caches the preview TextureView and sets up the
    // orientation listener.
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        mThumbnail = view.findViewById(R.id.thumbnail);
        mThumbnail.setOnClickListener(this);
        view.findViewById(R.id.reverse).setOnClickListener(this);
        mTextureView = view.findViewById(R.id.texture);

        mOrientationListener = new OrientationEventListener(getActivity(),
                SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            // Device rotated: recompute the preview transform so the image stays upright.
            public void onOrientationChanged(int orientation) {
                if (mTextureView != null && mTextureView.isAvailable()) {
                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                }
            }
        };
    }

    @Override
    // Starts the background thread, opens the camera and enables orientation tracking on resume.
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        openCamera();

        if (mTextureView.isAvailable()) {
            configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }
    }

    @Override
    // Disables orientation tracking, closes the camera and stops the background thread on pause.
    public void onPause() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
        }
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    // Handles the runtime permission result; opens the camera if all permissions are granted.
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CameraConstants.REQUEST_CAMERA_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showMissingPermissionError();
                    return;
                }
            }
            // All permissions granted: open the camera now.
            openCamera();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    // Handles the capture button (take a picture), the reverse button (switches camera) and the
    // thumbnail button (opens the last captured JPEG).
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                takePicture();
                break;
            }
            case R.id.reverse: {
                switchCamera();
                break;
            }
            case R.id.thumbnail: {
                CameraUtils.openMedia(getActivity(), mLastJpegFile, false);
                break;
            }
        }
    }

    // Enumerates camera characteristics, picks the back-facing camera, sizes the preview/JPEG/RAW
    // readers and decides which 3A controls are supported. Returns false if no camera is available.
    private boolean setUpCameraOutputs() {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            ErrorDialog.newInstance("This device doesn't support Camera2 API.").
                    show(getChildFragmentManager(), FRAGMENT_DIALOG);
            return false;
        }
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                if (!contains(characteristics.get(
                                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    continue;
                }

                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != mCurrentFacing) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                Size largestJpeg = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());

                Size largestRaw = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
                        new CompareSizesByArea());

                synchronized (mCameraStateLock) {
                    if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
                        mJpegImageReader = new RefCountedAutoCloseable<>(
                                ImageReader.newInstance(largestJpeg.getWidth(),
                                        largestJpeg.getHeight(), ImageFormat.JPEG, /*maxImages*/5));
                    }
                    mJpegImageReader.get().setOnImageAvailableListener(
                            mOnJpegImageAvailableListener, mBackgroundHandler);

                    if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
                        mRawImageReader = new RefCountedAutoCloseable<>(
                                ImageReader.newInstance(largestRaw.getWidth(),
                                        largestRaw.getHeight(), ImageFormat.RAW_SENSOR, /*maxImages*/ 5));
                    }
                    mRawImageReader.get().setOnImageAvailableListener(
                            mOnRawImageAvailableListener, mBackgroundHandler);

                    mCharacteristics = characteristics;
                    mCameraId = cameraId;
                    Boolean flashAvailable = characteristics.get(
                            CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    mFlashSupported = flashAvailable != null && flashAvailable;
                }
                return true;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        ErrorDialog.newInstance("This device doesn't support capturing RAW photos").
                show(getChildFragmentManager(), FRAGMENT_DIALOG);
        return false;
    }

    @SuppressWarnings("MissingPermission")
    // Opens the camera asynchronously after ensuring permissions, then creates the preview session.
    private void openCamera() {
        if (!setUpCameraOutputs()) {
            return;
        }
        if (!hasAllPermissionsGranted()) {
            requestCameraPermissions();
            return;
        }

        androidx.fragment.app.FragmentActivity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            String cameraId;
            Handler backgroundHandler;
            synchronized (mCameraStateLock) {
                cameraId = mCameraId;
                backgroundHandler = mBackgroundHandler;
            }

            manager.openCamera(cameraId, mStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    // Requests the CAMERA and WRITE_EXTERNAL_STORAGE runtime permissions from the user.
    private void requestCameraPermissions() {
        if (shouldShowRationale()) {
            ConfirmationDialog.newInstance().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(CameraConstants.CAMERA_PERMISSIONS,
                    CameraConstants.REQUEST_CAMERA_PERMISSIONS);
        }
    }

    // Returns true only when every required permission has been granted.
    private boolean hasAllPermissionsGranted() {
        for (String permission : CameraConstants.CAMERA_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(requireActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Returns true if the system suggests we should explain why the permissions are needed.
    private boolean shouldShowRationale() {
        for (String permission : CameraConstants.CAMERA_PERMISSIONS) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    // Shows an error dialog and finishes the activity when required permissions are missing.
    private void showMissingPermissionError() {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity != null) {
            Toast.makeText(activity, R.string.request_permission, Toast.LENGTH_SHORT).show();
            activity.finish();
        }
    }

    // Releases the camera device, capture session, ImageReaders and frees the open/close lock.
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            synchronized (mCameraStateLock) {
                mPendingUserCaptures = 0;
                mState = STATE_CLOSED;
                if (null != mCaptureSession) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
                if (null != mCameraDevice) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
                if (null != mJpegImageReader) {
                    mJpegImageReader.close();
                    mJpegImageReader = null;
                }
                if (null != mRawImageReader) {
                    mRawImageReader.close();
                    mRawImageReader = null;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    // Starts the background handler thread used for camera operations.
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        synchronized (mCameraStateLock) {
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    // Quits the background handler thread and waits for it to terminate.
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            synchronized (mCameraStateLock) {
                mBackgroundHandler = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Creates a capture session (preview + JPEG + RAW surfaces) and starts the repeating preview.
    private void createCameraPreviewSessionLocked() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            Surface surface = new Surface(texture);

            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface,
                            mJpegImageReader.get().getSurface(),
                            mRawImageReader.get().getSurface()), new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            synchronized (mCameraStateLock) {
                                if (null == mCameraDevice) {
                                    return;
                                }

                                try {
                                    setup3AControlsLocked(mPreviewRequestBuilder);
                                    MainActivity main = (MainActivity) getActivity();
                                    if (main != null) {
                                        mFlashMode = main.getFlashMode();
                                    }
                                    applyFlashMode(mPreviewRequestBuilder, false);
                                    cameraCaptureSession.setRepeatingRequest(
                                            mPreviewRequestBuilder.build(),
                                            mPreCaptureCallback, mBackgroundHandler);
                                    mState = STATE_PREVIEW;
                                } catch (CameraAccessException | IllegalStateException e) {
                                    e.printStackTrace();
                                    return;
                                }
                                mCaptureSession = cameraCaptureSession;
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed to configure camera.");
                        }
                    }, mBackgroundHandler
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Configures the AF/AE/AWB (3A) control modes and triggers on the given capture request builder.
    private void setup3AControlsLocked(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        mNoAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!mNoAFRun) {
            if (contains(mCharacteristics.get(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        if (contains(mCharacteristics.get(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
        }

        if (contains(mCharacteristics.get(
                        CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    // Computes and applies a texture transform so the preview matches the sensor orientation/aspect.
    private void configureTransform(int viewWidth, int viewHeight) {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        synchronized (mCameraStateLock) {
            if (null == mTextureView || null == activity || null == mCharacteristics) {
                return;
            }

            StreamConfigurationMap map = mCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            Point displaySize = new Point();
            activity.getWindowManager().getDefaultDisplay().getSize(displaySize);

            int totalRotation = sensorToDeviceRotation(mCharacteristics, deviceRotation);

            boolean swappedDimensions = totalRotation == 90 || totalRotation == 270;
            int rotatedViewWidth = viewWidth;
            int rotatedViewHeight = viewHeight;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;

            if (swappedDimensions) {
                rotatedViewWidth = viewHeight;
                rotatedViewHeight = viewWidth;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            if (maxPreviewWidth > CameraConstants.MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = CameraConstants.MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > CameraConstants.MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = CameraConstants.MAX_PREVIEW_HEIGHT;
            }

            Size previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight,
                    largestJpeg);

            if (swappedDimensions) {
                mTextureView.setAspectRatio(
                        previewSize.getHeight(), previewSize.getWidth());
            } else {
                mTextureView.setAspectRatio(
                        previewSize.getWidth(), previewSize.getHeight());
            }

            int rotation = (mCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT) ?
                    (360 + ORIENTATIONS.get(deviceRotation)) % 360 :
                    (360 - ORIENTATIONS.get(deviceRotation)) % 360;

            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max(
                        (float) viewHeight / previewSize.getHeight(),
                        (float) viewWidth / previewSize.getWidth());
                matrix.postScale(scale, scale, centerX, centerY);
            }
            matrix.postRotate(rotation, centerX, centerY);

            mTextureView.setTransform(matrix);

            if (mPreviewSize == null || !checkAspectsEqual(previewSize, mPreviewSize)) {
                mPreviewSize = previewSize;
                if (mState != STATE_CLOSED) {
                    createCameraPreviewSessionLocked();
                }
            }
        }
    }

    // Entry point for the capture button: triggers 3A convergence then the still-picture capture.
    private void takePicture() {
        synchronized (mCameraStateLock) {
            mPendingUserCaptures++;

            if (mState != STATE_PREVIEW) {
                return;
            }

            try {
                if (!mNoAFRun) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_START);
                }

                if (!isLegacyLocked()) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                }

                mState = STATE_WAITING_FOR_3A_CONVERGENCE;

                startTimerLocked();

                mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
                        mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    // Builds and submits the JPEG + RAW still-capture requests using the STILL_CAPTURE template.
    private void captureStillPictureLocked() {
        try {
            final androidx.fragment.app.FragmentActivity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            captureBuilder.addTarget(mJpegImageReader.get().getSurface());
            captureBuilder.addTarget(mRawImageReader.get().getSurface());

            setup3AControlsLocked(captureBuilder);
            applyFlashMode(captureBuilder, true);

            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                    sensorToDeviceRotation(mCharacteristics, rotation));

            captureBuilder.setTag(mRequestCounter.getAndIncrement());

            CaptureRequest request = captureBuilder.build();

            ImageSaver.ImageSaverBuilder jpegBuilder = new ImageSaver.ImageSaverBuilder(activity)
                    .setCharacteristics(mCharacteristics);
            ImageSaver.ImageSaverBuilder rawBuilder = new ImageSaver.ImageSaverBuilder(activity)
                    .setCharacteristics(mCharacteristics);

            mJpegResultQueue.put((int) request.getTag(), jpegBuilder);
            mRawResultQueue.put((int) request.getTag(), rawBuilder);

            mCaptureSession.capture(request, mCaptureCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Resets the AF trigger and returns the state machine to the preview/lock state after capture.
    private void finishedCaptureLocked() {
        try {
            if (!mNoAFRun) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);

                mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
                        mBackgroundHandler);

                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Removes the oldest queued image-saver builder and dispatches it to the background handler to save.
    private void dequeueAndSaveImage(TreeMap<Integer, ImageSaver.ImageSaverBuilder> pendingQueue,
                                     RefCountedAutoCloseable<ImageReader> reader) {
        synchronized (mCameraStateLock) {
            Map.Entry<Integer, ImageSaver.ImageSaverBuilder> entry =
                    pendingQueue.firstEntry();
            ImageSaver.ImageSaverBuilder builder = entry.getValue();

            if (reader == null || reader.getAndRetain() == null) {
                Log.e(TAG, "Paused the activity before we could save the image," +
                        " ImageReader already closed.");
                pendingQueue.remove(entry.getKey());
                return;
            }

            Image image;
            try {
                image = reader.get().acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Too many images queued for saving, dropping image for request: " +
                        entry.getKey());
                pendingQueue.remove(entry.getKey());
                return;
            }

            builder.setRefCountedReader(reader).setImage(image);

            handleCompletionLocked(entry.getKey(), builder, pendingQueue);
        }
    }

    // Selects the largest size that fits the preview area and matches the requested aspect ratio.
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    // Builds a filesystem-safe timestamp string used to name captured image files.
    private static String generateTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US);
        return sdf.format(new Date());
    }

    // Returns true if the given mode value is present in the modes array.
    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    // Returns true when the two sizes have approximately equal aspect ratios (within tolerance).
    private static boolean checkAspectsEqual(Size a, Size b) {
        double aAspect = a.getWidth() / (double) a.getHeight();
        double bAspect = b.getWidth() / (double) b.getHeight();
        return Math.abs(aAspect - bAspect) <= ASPECT_RATIO_TOLERANCE;
    }

    // Computes the total rotation needed to orient a captured image for the current device rotation.
    private static int sensorToDeviceRotation(CameraCharacteristics c, int deviceOrientation) {
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        deviceOrientation = ORIENTATIONS.get(deviceOrientation);

        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            deviceOrientation = -deviceOrientation;
        }

        return (sensorOrientation - deviceOrientation + 360) % 360;
    }

    // Displays a short toast on the UI thread with the given message.
    private void showToast(String text) {
        Message message = Message.obtain();
        message.obj = text;
        mMessageHandler.sendMessage(message);
    }

    // Removes a finished capture from the pending queue and, when all captures are done, tidies up.
    private void handleCompletionLocked(int requestId, ImageSaver.ImageSaverBuilder builder,
                                        TreeMap<Integer, ImageSaver.ImageSaverBuilder> queue) {
        if (builder == null) return;
        ImageSaver saver = builder.buildIfComplete();
        if (saver != null) {
            queue.remove(requestId);
            ImageSaver.execute(saver);
        }
    }

    // Returns true if the selected camera is running in LEGACY (limited) hardware level.
    private boolean isLegacyLocked() {
        return mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
    }

    // Records the current time so the precapture timeout can be measured.
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    // Returns true if the precapture wait has exceeded PRECAPTURE_TIMEOUT_MS.
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    // Applies the current flash mode to a capture request builder. isCapture forces the flash for the
    // still capture (so "on" fires the flash); the preview request uses auto-flash for "on".
    private void applyFlashMode(CaptureRequest.Builder requestBuilder, boolean isCapture) {
        if (!mFlashSupported) {
            return;
        }
        switch (mFlashMode) {
            case FlashControl.FLASH_OFF:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case FlashControl.FLASH_ON:
                if (isCapture) {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                } else {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                }
                break;
            default: // AUTO
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
        }
    }

    // FlashControl: the top-bar flash button routes its mode here while this fragment is active.
    @Override
    public void setFlashMode(int mode) {
        mFlashMode = mode;
        if (mPreviewRequestBuilder != null && mCaptureSession != null && mFlashSupported) {
            applyFlashMode(mPreviewRequestBuilder, false);
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                        mPreCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int getFlashMode() {
        return mFlashMode;
    }

    @Override
    public boolean isFlashSupported() {
        return mFlashSupported;
    }

    // Toggles between the front and back camera and reopens the device.
    private void switchCamera() {
        mCurrentFacing = (mCurrentFacing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        closeCamera();
        openCamera();
    }
}
