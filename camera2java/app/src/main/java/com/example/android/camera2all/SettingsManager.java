/*
 * Persistent user configuration for preview / photo / video capture.
 *
 * Every value is stored in a private SharedPreferences file and read back when the
 * SettingsActivity opens or when a camera fragment builds its capture session. All
 * getters return a sensible default when nothing has been stored yet, and the helper
 * methods used by the fragments fall back to the camera's own defaults when the
 * user-selected value is not supported by the device - so unchanged settings behave
 * exactly like the original code.
 */

package com.example.android.camera2all;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Centralised, type-light access to the app's saved capture preferences.
 */
public final class SettingsManager {

    private static final String PREF_NAME = "camera2_settings";

    // Preference keys, one per (module, attribute) pair.
    public static final String KEY_PREVIEW_SIZE = "preview_size";
    public static final String KEY_PREVIEW_FORMAT = "preview_format";
    public static final String KEY_PHOTO_SIZE = "photo_size";
    public static final String KEY_PHOTO_FORMAT = "photo_format";
    public static final String KEY_VIDEO_SIZE = "video_size";
    public static final String KEY_VIDEO_FORMAT = "video_format";

    // Defaults. "auto" always means "let the fragment choose its own default".
    public static final String DEF_PREVIEW_SIZE = "auto";
    public static final String DEF_PREVIEW_FORMAT = "auto";
    public static final String DEF_PHOTO_SIZE = "auto";
    public static final String DEF_PHOTO_FORMAT = "jpeg";
    public static final String DEF_VIDEO_SIZE = "auto";
    public static final String DEF_VIDEO_FORMAT = "mp4_h264";

    // Sentinel value stored for "let the fragment decide".
    public static final String AUTO = "auto";

    private SettingsManager() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ---- Generic read/write -------------------------------------------------

    @NonNull
    public static String get(@NonNull Context context, @NonNull String key, @NonNull String def) {
        return prefs(context).getString(key, def);
    }

    public static void put(@NonNull Context context, @NonNull String key, @NonNull String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    // ---- Typed accessors ----------------------------------------------------

    @NonNull
    public static String getPreviewSize(@NonNull Context c) {
        return get(c, KEY_PREVIEW_SIZE, DEF_PREVIEW_SIZE);
    }

    @NonNull
    public static String getPreviewFormat(@NonNull Context c) {
        return get(c, KEY_PREVIEW_FORMAT, DEF_PREVIEW_FORMAT);
    }

    @NonNull
    public static String getPhotoSize(@NonNull Context c) {
        return get(c, KEY_PHOTO_SIZE, DEF_PHOTO_SIZE);
    }

    @NonNull
    public static String getPhotoFormat(@NonNull Context c) {
        return get(c, KEY_PHOTO_FORMAT, DEF_PHOTO_FORMAT);
    }

    @NonNull
    public static String getVideoSize(@NonNull Context c) {
        return get(c, KEY_VIDEO_SIZE, DEF_VIDEO_SIZE);
    }

    @NonNull
    public static String getVideoFormat(@NonNull Context c) {
        return get(c, KEY_VIDEO_FORMAT, DEF_VIDEO_FORMAT);
    }

    /** Restores every preference to its default value. */
    public static void resetAll(@NonNull Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString(KEY_PREVIEW_SIZE, DEF_PREVIEW_SIZE);
        e.putString(KEY_PREVIEW_FORMAT, DEF_PREVIEW_FORMAT);
        e.putString(KEY_PHOTO_SIZE, DEF_PHOTO_SIZE);
        e.putString(KEY_PHOTO_FORMAT, DEF_PHOTO_FORMAT);
        e.putString(KEY_VIDEO_SIZE, DEF_VIDEO_SIZE);
        e.putString(KEY_VIDEO_FORMAT, DEF_VIDEO_FORMAT);
        e.apply();
    }

    // ---- Size helpers -------------------------------------------------------

    /**
     * Parses a "WxH" / "W×H" string into an {@link Size}, or null if it is not valid.
     */
    @Nullable
    public static Size parseSize(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        int x = t.indexOf('x');
        if (x < 0) x = t.indexOf('×');
        if (x < 0) return null;
        try {
            int w = Integer.parseInt(t.substring(0, x));
            int h = Integer.parseInt(t.substring(x + 1));
            if (w > 0 && h > 0) return new Size(w, h);
        } catch (NumberFormatException ignore) {
            // fall through to null
        }
        return null;
    }

    /**
     * Returns the configured size if it is present in {@code available}, otherwise null.
     * A null / empty / "auto" configuration also yields null so the caller can use its
     * own default selection.
     */
    @Nullable
    public static Size pickSize(@Nullable Size[] available, @Nullable String configured) {
        if (available == null || configured == null
                || configured.isEmpty() || AUTO.equals(configured)) {
            return null;
        }
        Size want = parseSize(configured);
        if (want == null) return null;
        for (Size s : available) {
            if (s.getWidth() == want.getWidth() && s.getHeight() == want.getHeight()) {
                return s;
            }
        }
        return null;
    }

    // ---- Video format mapping ----------------------------------------------

    /** MediaRecorder output container for the given format key. */
    public static int getVideoOutputFormat(@NonNull String key) {
        if ("webm_vp8".equals(key)) {
            return MediaRecorder.OutputFormat.WEBM;
        }
        return MediaRecorder.OutputFormat.MPEG_4;
    }

    /** MediaRecorder video encoder for the given format key. */
    public static int getVideoEncoder(@NonNull String key) {
        if ("mp4_hevc".equals(key)) {
            return MediaRecorder.VideoEncoder.HEVC;
        }
        if ("webm_vp8".equals(key)) {
            return MediaRecorder.VideoEncoder.VP8;
        }
        return MediaRecorder.VideoEncoder.H264;
    }

    /** MediaRecorder audio encoder for the given format key. */
    public static int getAudioEncoder(@NonNull String key) {
        if ("webm_vp8".equals(key)) {
            return MediaRecorder.AudioEncoder.VORBIS;
        }
        return MediaRecorder.AudioEncoder.AAC;
    }

    /** File extension (including the dot) for the given format key. */
    @NonNull
    public static String getVideoExtension(@NonNull String key) {
        if ("webm_vp8".equals(key)) {
            return ".webm";
        }
        return ".mp4";
    }

    /** Whether the device can actually record the requested video format. */
    public static boolean isVideoFormatSupported(@NonNull String key) {
        if ("mp4_hevc".equals(key)) {
            // MediaRecorder.VideoEncoder.HEVC was added in API 24.
            return Build.VERSION.SDK_INT >= 24;
        }
        if ("webm_vp8".equals(key)) {
            // WEBM container + VP8/Vorbis encoders require API 21+.
            return Build.VERSION.SDK_INT >= 21;
        }
        return true;
    }
}
