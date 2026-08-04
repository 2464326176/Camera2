/*
 * Shared constants for the combined Camera2 (Basic / Video / Raw) application.
 */

package com.example.android.camera2all;

public final class CameraConstants {
    /** Request code for all camera-related permissions. */
    public static final int REQUEST_CAMERA_PERMISSIONS = 1;

    /**
     * Minimum permissions for photo / RAW capture. Both Basic and Raw write to the app-specific
     * external-files directory (getExternalFilesDir), which is exempt from scoped-storage
     * permission requirements on Android 10+, so no storage permission is needed here.
     */
    public static final String[] CAMERA_PERMISSIONS = {
            android.Manifest.permission.CAMERA,
    };

    /**
     * Permissions for video recording: camera plus microphone. Also scoped-storage exempt, so no
     * storage permission is required.
     */
    public static final String[] VIDEO_PERMISSIONS = {
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
    };

    /** Max preview width guaranteed by the Camera2 API. */
    public static final int MAX_PREVIEW_WIDTH = 1920;

    /** Max preview height guaranteed by the Camera2 API. */
    public static final int MAX_PREVIEW_HEIGHT = 1080;

    private CameraConstants() {}
}
