/*
 * Copyright 2017 The Android Open Source Project
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
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageButton;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Camera2Basic: JPEG still image capture with AF/AE/AWB 3A control and a state machine.
 */
public class Camera2BasicFragment extends Fragment implements View.OnClickListener, FlashControl {

    // Maps device screen rotation to the JPEG orientation value required by the camera sensor.
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    // Tag used to identify the permission / error dialog fragment in the child FragmentManager.
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    // Log tag for this fragment.
    private static final String TAG = "Camera2BasicFragment";

    // State machine: camera is streaming a preview to the surface.
    private static final int STATE_PREVIEW = 0;
    // State machine: AF lock has been requested, waiting for AF to converge.
    private static final int STATE_WAITING_LOCK = 1;
    // State machine: AE precapture sequence has been triggered, waiting for it to start.
    private static final int STATE_WAITING_PRECAPTURE = 2;
    // State machine: precapture started, waiting for it to finish before taking the picture.
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    // State machine: still picture has been captured, waiting for the result.
    private static final int STATE_PICTURE_TAKEN = 4;

    // Listens for the preview surface becoming available / resized to drive camera open/transform.
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        // Called when the preview surface is created; opens the camera with the surface dimensions.
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        // Called when the preview surface changes size; recomputes the preview transform.
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        // Called when the preview surface is destroyed; returns true so the surface is released.
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        // Called for every new preview frame; no action needed here.
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    // Id of the back-facing camera chosen for this fragment.
    private String mCameraId;
    // Custom TextureView that keeps a correct aspect ratio for the camera preview.
    private AutoFitTextureView mTextureView;
    // Active capture session used to issue repeating preview and one-shot capture requests.
    private CameraCaptureSession mCaptureSession;
    // Handle to the opened camera device, null when closed.
    private CameraDevice mCameraDevice;
    // Size selected for the preview stream.
    private Size mPreviewSize;

    // Receives camera device open/close lifecycle events and drives the capture session.
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        // Camera opened successfully: store the handle, release the lock and start the preview.
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        // Camera was disconnected (e.g. unplugged): release the lock and close it.
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        // A fatal error occurred: release the lock, close the camera and finish the activity.
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    // Background thread that handles camera callbacks off the UI thread.
    private HandlerThread mBackgroundThread;
    // Handler bound to the background thread's looper.
    private Handler mBackgroundHandler;
    // Reader that receives JPEG frames captured by the camera.
    private ImageReader mImageReader;
    // Optional RAW (DNG) reader, created only when the still-image format includes DNG.
    private ImageReader mRawImageReader;
    // Output file for the captured DNG; null when DNG capture is not enabled.
    private File mRawFile;
    // Output file the most recent captured picture is written to.
    private File mFile;
    // Current lens facing (back or front); toggled by the reverse button.
    private int mCurrentFacing = CameraCharacteristics.LENS_FACING_BACK;
    // Selected flash mode, synced from the top-bar flash button via FlashControl.
    private int mFlashMode = FlashControl.FLASH_AUTO;
    // Thumbnail button showing the last captured picture; click opens the full image.
    private ImageButton mThumbnail;

    // Notified when a captured JPEG frame is ready; hands the image off to ImageSaver on the bg thread.
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
            // Refresh the thumbnail shortly after, so the JPEG has been flushed to disk.
            final File saved = mFile;
            mBackgroundHandler.postDelayed(() -> CameraUtils.updateImageThumbnail(
                    saved, mThumbnail, mBackgroundHandler,
                    new Handler(Looper.getMainLooper())), 400);
        }

    };

    // Builder for the repeating preview capture request.
    private CaptureRequest.Builder mPreviewRequestBuilder;
    // The built preview request that is submitted repeatedly.
    private CaptureRequest mPreviewRequest;
    // Current state of the capture state machine (see STATE_* constants).
    private int mState = STATE_PREVIEW;
    // Guards open/close of the camera device so it cannot be opened twice or closed while in use.
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    // Whether the selected camera supports flash.
    private boolean mFlashSupported;
    // Clockwise rotation in degrees the sensor is mounted at, relative to the device's natural orientation.
    private int mSensorOrientation;
    // Characteristics of the active camera, needed to write valid DNG files.
    private CameraCharacteristics mCharacteristics;

    // Receives per-frame capture results and drives the still-capture state machine.
    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        // Advances the capture state machine according to the latest AF/AE states.
        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    // Displays a short toast on the UI thread with the given message.
    private void showToast(final String text) {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // Selects the smallest size that is at least as large as the preview and matches the aspect ratio.
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

    // Factory method that returns a new fragment instance.
    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    // Wires up the capture/reverse/thumbnail buttons and caches the preview TextureView.
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        mThumbnail = view.findViewById(R.id.thumbnail);
        mThumbnail.setOnClickListener(this);
        view.findViewById(R.id.reverse).setOnClickListener(this);
        mTextureView = view.findViewById(R.id.texture);
    }

    @Override
    // Prepares the output file the captured picture will be written to.
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mFile = new File(requireActivity().getExternalFilesDir(null), "pic.jpg");
    }

    @Override
    // Starts the background thread and opens the camera once the fragment is resumed/visible.
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    // Tears down the camera and background thread when the fragment is paused.
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // Requests the camera permission, showing a rationale dialog first if appropriate.
    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(CameraConstants.CAMERA_PERMISSIONS,
                    CameraConstants.REQUEST_CAMERA_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CameraConstants.REQUEST_CAMERA_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    ErrorDialog.newInstance(getString(R.string.request_permission))
                            .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                    return;
                }
            }
            // All permissions granted: open the camera now if the surface is ready.
            if (mTextureView.isAvailable()) {
                openCamera(mTextureView.getWidth(), mTextureView.getHeight());
            } else {
                mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    // Enumerates camera characteristics, picks the back-facing camera, sizes the preview/ImageReader
    // surfaces and decides whether flash is supported.
    private void setUpCameraOutputs(int width, int height) {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = CameraUtils.chooseCameraId(manager, mCurrentFacing);
            if (cameraId == null) {
                ErrorDialog.newInstance(getString(R.string.camera_error))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                ErrorDialog.newInstance(getString(R.string.camera_error))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                return;
            }

                // Honour the user-configured still-image size when the device supports it; otherwise
            // fall back to the largest available JPEG size (original behaviour).
            Size photoSize = SettingsManager.pickSize(map.getOutputSizes(ImageFormat.JPEG),
                    SettingsManager.getPhotoSize(activity));
                Size largest = (photoSize != null) ? photoSize
                        : Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                                new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // Still-image format: optionally also capture a DNG when the device supports RAW_SENSOR.
                String photoFormat = SettingsManager.getPhotoFormat(activity);
                if (photoFormat.contains("dng")
                        && map.getOutputSizes(ImageFormat.RAW_SENSOR).length > 0) {
                    mRawImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                            ImageFormat.RAW_SENSOR, /*maxImages*/2);
                    mRawFile = new File(activity.getExternalFilesDir(null), "pic.dng");
                }

                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                mCharacteristics = characteristics;
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > CameraConstants.MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = CameraConstants.MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > CameraConstants.MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = CameraConstants.MAX_PREVIEW_HEIGHT;
                }

                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // Honour the user-configured preview size when the device supports it.
                Size previewSizePref = SettingsManager.pickSize(
                        map.getOutputSizes(SurfaceTexture.class),
                        SettingsManager.getPreviewSize(activity));
                if (previewSizePref != null) {
                    mPreviewSize = previewSizePref;
                }

                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    // Opens the camera asynchronously after ensuring permissions, then creates the preview session.
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(requireActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        androidx.fragment.app.FragmentActivity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    // Releases the camera device, capture session and ImageReader, and frees the open/close lock.
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            if (mRawImageReader != null) {
                mRawImageReader.close();
                mRawImageReader = null;
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
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    // Quits the background handler thread and waits for it to terminate.
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Creates a capture session and starts the repeating preview request.
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            Surface surface = new Surface(texture);

            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(mImageReader.getSurface());
            if (mRawImageReader != null) {
                surfaces.add(mRawImageReader.getSurface());
            }
            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }

                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                MainActivity main = (MainActivity) getActivity();
                                if (main != null) {
                                    mFlashMode = main.getFlashMode();
                                }
                                applyFlashMode(mPreviewRequestBuilder, false);

                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Computes and applies a texture transform so the preview matches the sensor orientation/aspect.
    private void configureTransform(int viewWidth, int viewHeight) {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    // Entry point for the capture button: begins focus lock then the precapture sequence.
    private void takePicture() {
        lockFocus();
    }

    // Triggers AF lock and moves the state machine into STATE_WAITING_LOCK.
    private void lockFocus() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Starts the AE precapture sequence and waits for AE convergence before capturing.
    private void runPrecaptureSequence() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Builds and submits the still-capture request (JPEG) using the STILL_CAPTURE template.
    private void captureStillPicture() {
        try {
            final androidx.fragment.app.FragmentActivity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            if (mRawImageReader != null) {
                captureBuilder.addTarget(mRawImageReader.getSurface());
            }

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlashMode(captureBuilder, true);

            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    // When DNG capture is enabled, persist the RAW frame as a proper DNG file,
                    // pairing it with this capture's result/characteristics for DngCreator.
                    if (mRawImageReader != null && mRawFile != null && mCharacteristics != null) {
                        Image rawImage = mRawImageReader.acquireLatestImage();
                        if (rawImage != null) {
                            final Image finalRaw = rawImage;
                            final TotalCaptureResult finalResult = result;
                            mBackgroundHandler.post(() -> {
                                FileOutputStream out = null;
                                try {
                                    DngCreator dngCreator =
                                            new DngCreator(mCharacteristics, finalResult);
                                    out = new FileOutputStream(mRawFile);
                                    dngCreator.writeImage(out, finalRaw);
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to write DNG: " + e.getMessage());
                                    e.printStackTrace();
                                } finally {
                                    finalRaw.close();
                                    if (out != null) {
                                        try {
                                            out.close();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            });
                        }
                    }

                    String saved = "Saved: " + mFile;
                    if (mRawFile != null) {
                        saved += ", " + mRawFile.getName();
                    }
                    showToast(saved);
                    Log.d(TAG, saved);
                    unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    // Resets AF/AE triggers, returns to the preview state and resumes the repeating request.
    private void unlockFocus() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            applyFlashMode(mPreviewRequestBuilder, false);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
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
                CameraUtils.openMedia(getActivity(), mFile, false);
                break;
            }
        }
    }

    // Applies the current flash mode to a capture request builder. isCapture distinguishes the
    // still-capture request (where "on" forces the flash) from the live preview request.
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
                        mCaptureCallback, mBackgroundHandler);
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
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        private final Image mImage;
        private final File mFile;

        ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }
}
