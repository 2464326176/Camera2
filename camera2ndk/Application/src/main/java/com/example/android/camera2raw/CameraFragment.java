package com.example.android.camera2raw;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

public class CameraFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private CameraController cameraController;
    private FaceOverlayView faceOverlayView;
    private TextureView textureView;
    private ImageView thumbnailView;
    private Button switchCameraButton;

    private int frameCounter = 0;
    private static final int DETECTION_INTERVAL = 3;

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        textureView = view.findViewById(R.id.texture);
        faceOverlayView = view.findViewById(R.id.face_overlay);
        thumbnailView = view.findViewById(R.id.thumbnail);
        switchCameraButton = view.findViewById(R.id.switch_camera);

        view.findViewById(R.id.picture).setOnClickListener(v -> takePicture());
        switchCameraButton.setOnClickListener(v -> switchCamera());

        textureView.setSurfaceTextureListener(this);

        ImageProcessor.initFaceDetector(requireContext().getApplicationContext());
        cameraController = new CameraController(requireContext(), previewImageListener, captureImageListener);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraController.startCamera();
            cameraController.createPreviewSession(surface);
        } else {
            Toast.makeText(getContext(), "Camera permission required.", Toast.LENGTH_LONG).show();
            requireActivity().finish();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        cameraController.stopCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void switchCamera() {
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st != null) {
            cameraController.switchCamera(st);
        } else {
            Toast.makeText(getContext(), "Surface not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private final ImageReader.OnImageAvailableListener previewImageListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            frameCounter++;
            if (frameCounter % DETECTION_INTERVAL != 0) return;

            RectF[] faces = ImageProcessor.detectFaces(image);
            final int imgW = image.getWidth();
            final int imgH = image.getHeight();
            final int viewW = textureView.getWidth();
            final int viewH = textureView.getHeight();

            final float sx = (float) viewW / imgW;
            final float sy = (float) viewH / imgH;

            final RectF[] scaled;
            if (faces != null && faces.length > 0) {
                scaled = new RectF[faces.length];
                for (int i = 0; i < faces.length; i++) {
                    scaled[i] = new RectF(
                            faces[i].left * sx,
                            faces[i].top * sy,
                            faces[i].right * sx,
                            faces[i].bottom * sy);
                }
            } else {
                scaled = new RectF[0];
            }

            requireActivity().runOnUiThread(() -> faceOverlayView.setFaces(scaled));
        } finally {
            image.close();
        }
    };

    private final ImageReader.OnImageAvailableListener captureImageListener = reader -> {
        Image image = reader.acquireNextImage();
        if (image == null) return;

        // 先获取宽高，再处理数据，避免关闭后访问
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = ImageProcessor.imageToNv21(image);
        image.close();

        if (nv21 != null) {
            Bitmap bitmap = ImageProcessor.nv21ToBitmap(nv21, width, height);
            if (bitmap != null) {
                requireActivity().runOnUiThread(() -> {
                    thumbnailView.setImageBitmap(bitmap);
                    Toast.makeText(getContext(), "Captured!", Toast.LENGTH_SHORT).show();
                });
            }
        }
    };

    private void takePicture() {
        cameraController.captureStillImage();
    }

    @Override
    public void onDestroy() {
        ImageProcessor.releaseFaceDetector();
        super.onDestroy();
    }
}