package com.opencv.camera;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs bundled model files from res/raw into app-private storage so the
 * native engine can open them by absolute path.
 */
final class ModelAssets {
    private static final String TAG = "ModelAssets";
    private static final String FACE_MODEL_NAME = "face_detection_yunet_2023mar.onnx";

    private ModelAssets() {}

    /**
     * Ensures the YuNet face model exists under filesDir and returns that
     * directory path for CameraAlgoSdk.create(assetDir).
     */
    static String ensureModelDir(Context context) throws IOException {
        File dir = context.getApplicationContext().getFilesDir();
        File modelFile = new File(dir, FACE_MODEL_NAME);
        if (!modelFile.exists() || modelFile.length() == 0L) {
            copyRawToFile(context, R.raw.face_detection_yunet_2023mar, modelFile);
            Log.i(TAG, "Installed face model → " + modelFile.getAbsolutePath());
        }
        return dir.getAbsolutePath();
    }

    private static void copyRawToFile(Context context, int rawId, File outFile)
            throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create model directory: " + parent);
        }
        try (InputStream in = context.getResources().openRawResource(rawId);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
