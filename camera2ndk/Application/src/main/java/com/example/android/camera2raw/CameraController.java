package com.example.android.camera2raw;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraController {
    private static final String TAG = "CameraController";
    private static final int PREVIEW_TARGET_W = 640;
    private static final int PREVIEW_TARGET_H = 480;
    private static final int CAPTURE_MAX_W = 4000;
    private static final int CAPTURE_MAX_H = 3000;

    private final Context context;
    private final ImageReader.OnImageAvailableListener previewListener;
    private final ImageReader.OnImageAvailableListener captureListener;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private String cameraId;
    private Size previewSize;
    private Size captureSize;
    private ImageReader previewReader;
    private ImageReader captureReader;

    private int mCurrentFacing = CameraCharacteristics.LENS_FACING_BACK;
    private SurfaceTexture mPendingTexture;          // 用于在相机打开后创建会话

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
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
        }
    };

    public CameraController(@NonNull Context context,
                            @NonNull ImageReader.OnImageAvailableListener previewListener,
                            @NonNull ImageReader.OnImageAvailableListener captureListener) {
        this.context = context;
        this.previewListener = previewListener;
        this.captureListener = captureListener;
    }

    public void startCamera() {
        startBackgroundThread();
        openCamera();
    }

    public void stopCamera() {
        closeCamera();
        stopBackgroundThread();
    }

    /** 切换前后摄像头，调用前需确保 TextureView 已有 SurfaceTexture */
    public void switchCamera(SurfaceTexture texture) {
        if (backgroundHandler == null) return;
        backgroundHandler.post(() -> {
            mPendingTexture = texture;
            // 切换朝向
            mCurrentFacing = (mCurrentFacing == CameraCharacteristics.LENS_FACING_BACK)
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            // 关闭当前相机，重新打开
            closeCameraInternal();  // 不重置 mPendingTexture
            openCamera();
        });
    }

    /** 创建预览会话，可由外部调用（如 SurfaceTexture 可用时） */
    public void createPreviewSession(SurfaceTexture texture) {
        if (cameraDevice == null) {
            mPendingTexture = texture;
            return;
        }
        if (texture == null) return;
        mPendingTexture = null;

        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surface = new Surface(texture);

        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.addTarget(previewReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            List<Surface> outputs = Arrays.asList(surface, previewReader.getSurface(), captureReader.getSurface());

            cameraDevice.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSession failed", e);
        }
    }

    public void captureStillImage() {
        if (cameraDevice == null || captureSession == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(captureReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            captureSession.capture(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureStillImage failed", e);
        }
    }

    // ---------- private ----------

    private void openCamera() {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timeout waiting to lock camera opening.");
            }

            // 释放旧的 ImageReader
            if (previewReader != null) { previewReader.close(); previewReader = null; }
            if (captureReader != null) { captureReader.close(); captureReader = null; }

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) != mCurrentFacing) continue;

                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;

                Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (yuvSizes == null || yuvSizes.length == 0) continue;

                previewSize = chooseMaxBelow(yuvSizes, PREVIEW_TARGET_W, PREVIEW_TARGET_H);
                if (previewSize == null) {
                    previewSize = Collections.min(Arrays.asList(yuvSizes), new CompareSizesByArea());
                }

                captureSize = chooseMaxBelow(yuvSizes, CAPTURE_MAX_W, CAPTURE_MAX_H);
                if (captureSize == null) {
                    captureSize = Collections.min(Arrays.asList(yuvSizes), new CompareSizesByArea());
                }

                Log.i(TAG, "Preview: " + previewSize + " Capture: " + captureSize);
                cameraId = id;
                break;
            }

            if (cameraId == null) {
                cameraOpenCloseLock.release();
                return;
            }

            previewReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(previewListener, backgroundHandler);

            captureReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.YUV_420_888, 4);
            captureReader.setOnImageAvailableListener(captureListener, backgroundHandler);

            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            cameraOpenCloseLock.release();
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

    /** 内部关闭，不释放锁，也不重置 mPendingTexture */
    private void closeCameraInternal() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewReader != null) {
            previewReader.close();
            previewReader = null;
        }
        if (captureReader != null) {
            captureReader.close();
            captureReader = null;
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static Size chooseMaxBelow(Size[] sizes, int maxW, int maxH) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() <= maxW && s.getHeight() <= maxH) {
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}