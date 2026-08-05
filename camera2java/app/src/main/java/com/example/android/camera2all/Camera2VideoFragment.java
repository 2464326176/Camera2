/*
 * Copyright 2014 The Android Open Source Project
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
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Camera2Video: MP4 video recording using MediaRecorder + Camera2 surfaces.
 */
public class Camera2VideoFragment extends Fragment implements View.OnClickListener, FlashControl {

    // Sensor orientation (degrees) used when the device is NOT in the inverse rotation case.
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    // Sensor orientation (degrees) used when the device IS in the inverse rotation case.
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    // Screen-to-sensor orientation mapping for the default (non-inverse) case.
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    // Screen-to-sensor orientation mapping for the inverse rotation case.
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    // Log tag for this fragment.
    private static final String TAG = "Camera2VideoFragment";
    // Tag used to identify the permission / error dialog fragment in the child FragmentManager.
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    // Custom TextureView that keeps a correct aspect ratio for the camera preview.
    private AutoFitTextureView mTextureView;
    // Button that toggles between starting and stopping video recording.
    private Button mButtonVideo;
    // Handle to the opened camera device, null when closed.
    private CameraDevice mCameraDevice;
    // Active capture session used to issue the repeating preview request.
    private CameraCaptureSession mPreviewSession;
    // Listens for the preview surface becoming available / resized to drive camera open/transform.
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // === Algorithm pseudo-interface: video-frame processing (video preview frame callback) ===
            // Call timing: every video preview frame arrives, before the render update (this is the
            // TextureView update callback).
            // Data flow: raw video frame (NV21) -> processVideoFrame() -> processed Bitmap (watermark/beauty)
            // Threading: posted to the dedicated algorithm thread (mAlgorithmHandler) so it never
            // blocks the main thread or the render pipeline.
            if (mVideoAlgorithm != null && mAlgorithmHandler != null && mPreviewSize != null) {
                final long tsNs = System.nanoTime();
                // onSurfaceTextureUpdated fires both in preview and while recording (the preview
                // surface stays live during capture), so the same hook covers both PREVIEW and
                // VIDEO (recording) stages; mIsRecordingVideo disambiguates the active stage.
                final boolean recording = mIsRecordingVideo;
                mAlgorithmHandler.post(() ->
                        mVideoAlgorithm.processFrame(
                                null, mPreviewSize.getWidth(), mPreviewSize.getHeight(), tsNs));
                if (recording) {
                    Log.v(TAG, "Video algorithm (recording stage) frame processed");
                }
            }
        }

    };

    // Size selected for the preview stream.
    private Size mPreviewSize;
    // Size selected for the recorded video.
    private Size mVideoSize;
    // Encodes the camera frames to a video file on disk.
    private MediaRecorder mMediaRecorder;
    // True while a video recording is in progress.
    private boolean mIsRecordingVideo;
    // Current lens facing (back or front); toggled by the reverse button.
    private int mCurrentFacing = CameraCharacteristics.LENS_FACING_BACK;
    // Selected flash mode, synced from the top-bar flash button via FlashControl.
    private int mFlashMode = FlashControl.FLASH_AUTO;
    // Whether the selected camera supports flash.
    private boolean mFlashSupported;
    // Thumbnail button showing the last recorded video; click opens the full clip.
    private ImageButton mThumbnail;
    // Most recently recorded video file, used by the thumbnail button.
    private File mLastVideoFile;
    // Background thread that handles camera callbacks off the UI thread.
    private HandlerThread mBackgroundThread;
    // Handler bound to the background thread's looper.
    private Handler mBackgroundHandler;
    // Guards open/close of the camera device so it cannot be opened twice or closed while in use.
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    // === Algorithm pseudo-interface hook (video-frame processing, mock) ===
    // Video-frame algorithm: invoked in the video preview frame callback (onSurfaceTextureUpdated),
    // executed on a dedicated thread so it does not block rendering.
    // Per-frame video algorithm (preview + recording). Null-safe; swap the Mock impl for a real
    // algorithm to activate it. Runs on a dedicated thread so it never blocks render/record.
    private final CameraAlgorithm.VideoAlgorithm mVideoAlgorithm =
            new CameraAlgorithm.MockVideoAlgorithm();
    // Dedicated thread for the video-frame algorithm: keeps processing off the main thread and
    // the render pipeline.
    private HandlerThread mAlgorithmThread;
    private Handler mAlgorithmHandler;

    // Receives camera device open/close lifecycle events and drives the preview session.
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        // Camera opened successfully: store the handle, start the preview and release the lock.
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
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
    // Clockwise rotation in degrees the sensor is mounted at, relative to the device's natural orientation.
    private Integer mSensorOrientation;
    // Absolute filesystem path for the next video file to be recorded.
    private String mNextVideoAbsolutePath;
    // Resolved video-format key actually used for the current recording (after device fallback).
    private String mVideoFormatResolved = SettingsManager.DEF_VIDEO_FORMAT;
    // Builder for the repeating preview capture request.
    private CaptureRequest.Builder mPreviewBuilder;

    // Factory method that returns a new fragment instance.
    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }

    // Picks a supported video size with a 4:3 aspect ratio limited to 1080p width.
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    // Selects the smallest size that is at least as large as the preview area and matches the aspect ratio.
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }

    @Override
    // Wires up the record/reverse/thumbnail buttons and caches the preview TextureView.
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = view.findViewById(R.id.texture);
        mButtonVideo = view.findViewById(R.id.video);
        mButtonVideo.setOnClickListener(this);
        mThumbnail = view.findViewById(R.id.thumbnail);
        mThumbnail.setOnClickListener(this);
        view.findViewById(R.id.reverse).setOnClickListener(this);
    }

    @Override
    // Starts the background thread and opens the camera once the fragment is resumed/visible.
    public void onResume() {
        super.onResume();
        // Start the video-frame algorithm dedicated thread (separate from the camera background
        // thread to avoid blocking rendering).
        mAlgorithmThread = new HandlerThread("VideoAlgorithm");
        mAlgorithmThread.start();
        mAlgorithmHandler = new Handler(mAlgorithmThread.getLooper());
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
        // Stop the video-frame algorithm dedicated thread.
        if (mAlgorithmThread != null) {
            mAlgorithmThread.quitSafely();
            try {
                mAlgorithmThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mAlgorithmThread = null;
            mAlgorithmHandler = null;
        }
        super.onPause();
    }

    @Override
    // Handles the record button (toggles recording), the reverse button (switches camera) and the
    // thumbnail button (opens the last recorded video).
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.video: {
                if (mIsRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
                break;
            }
            case R.id.reverse: {
                switchCamera();
                break;
            }
            case R.id.thumbnail: {
                CameraUtils.openMedia(getActivity(), mLastVideoFile, true);
                break;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CameraConstants.REQUEST_CAMERA_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                // Permissions granted: open the camera now if the surface is ready.
                if (mTextureView.isAvailable()) {
                    openCamera(mTextureView.getWidth(), mTextureView.getHeight());
                } else {
                    mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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

    @SuppressWarnings("MissingPermission")
    // Opens the camera asynchronously after ensuring permissions, then starts the preview.
    private void openCamera(int width, int height) {
        boolean allGranted = true;
        for (String permission : CameraConstants.VIDEO_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(requireActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            requestPermissions(CameraConstants.VIDEO_PERMISSIONS,
                    CameraConstants.REQUEST_CAMERA_PERMISSIONS);
            return;
        }
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId = CameraUtils.chooseCameraId(manager, mCurrentFacing);
            if (cameraId == null) {
                throw new RuntimeException("No camera available");
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = flashAvailable != null && flashAvailable;
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            // Honour the user-configured video size when supported; else use the original 4:3 pick.
            Size videoPref = SettingsManager.pickSize(map.getOutputSizes(MediaRecorder.class),
                    SettingsManager.getVideoSize(activity));
            mVideoSize = (videoPref != null) ? videoPref
                    : chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);

            // Honour the user-configured preview size when supported by the device.
            Size previewPref = SettingsManager.pickSize(map.getOutputSizes(SurfaceTexture.class),
                    SettingsManager.getPreviewSize(activity));
            if (previewPref != null) {
                mPreviewSize = previewPref;
            }

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    // Releases the camera device, capture session, MediaRecorder and frees the open/close lock.
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    // Creates the capture session and begins the repeating preview request.
    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            MainActivity main = (MainActivity) getActivity();
            if (main != null) {
                mFlashMode = main.getFlashMode();
            }

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = new Surface(texture);
            mPreviewBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            androidx.fragment.app.FragmentActivity activity = getActivity();
                            if (null != activity) {
                                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Re-submits the repeating preview request after parameters change.
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Applies common preview capture-request settings (e.g. continuous AF mode and flash mode).
    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        applyFlashMode(builder, false);
    }

    // Applies the current flash mode to a capture request builder. isCapture=true forces the flash
    // (torch during recording); the preview request uses auto-flash for "on".
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
        if (mPreviewBuilder != null && mPreviewSession != null && mFlashSupported) {
            applyFlashMode(mPreviewBuilder, false);
            try {
                mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
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
        }
        mTextureView.setTransform(matrix);
    }

    // Configures the MediaRecorder (source, profile, orientation, output file) for video capture.
    private void setUpMediaRecorder() throws IOException {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        // Resolve the configured video format, falling back to MP4/H.264 on unsupported devices.
        String vfmt = SettingsManager.getVideoFormat(activity);
        if (!SettingsManager.isVideoFormatSupported(vfmt)) {
            vfmt = SettingsManager.DEF_VIDEO_FORMAT;
            Toast.makeText(activity, R.string.video_format_unsupported, Toast.LENGTH_SHORT).show();
        }
        mVideoFormatResolved = vfmt;

        mMediaRecorder.setOutputFormat(SettingsManager.getVideoOutputFormat(vfmt));
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath(getActivity());
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(SettingsManager.getVideoEncoder(vfmt));
        mMediaRecorder.setAudioEncoder(SettingsManager.getAudioEncoder(vfmt));
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();
    }

    // Builds the absolute path for a new video file inside the app's external Movies directory.
    private String getVideoFilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        String ext = SettingsManager.getVideoExtension(mVideoFormatResolved);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ext;
    }

    // Starts MediaRecorder, switches the capture session to record and updates the UI button.
    private void startRecordingVideo() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            setUpMediaRecorder();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            applyFlashMode(mPreviewBuilder, true);
            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mButtonVideo.setText(R.string.stop);
                            mIsRecordingVideo = true;
                            mMediaRecorder.start();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    androidx.fragment.app.FragmentActivity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }

    }

    // Stops and releases the current capture session if one exists.
    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    // Stops MediaRecorder, releases it, notifies the user and returns to the preview session.
    private void stopRecordingVideo() {
        mIsRecordingVideo = false;
        mButtonVideo.setText(R.string.record);
        mMediaRecorder.stop();
        mMediaRecorder.reset();

        final File videoFile = (mNextVideoAbsolutePath != null)
                ? new File(mNextVideoAbsolutePath) : null;
        mLastVideoFile = videoFile;

        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mNextVideoAbsolutePath,
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Video saved: " + mNextVideoAbsolutePath);
        }
        mNextVideoAbsolutePath = null;
        if (videoFile != null) {
            CameraUtils.updateVideoThumbnail(videoFile, mThumbnail, mBackgroundHandler,
                    new Handler(Looper.getMainLooper()));
        }
        startPreview();
    }

}
