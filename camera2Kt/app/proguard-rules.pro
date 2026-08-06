# Keep line number information for crash reports (debug-friendly even in release).
-keepattributes SourceFile,LineNumberTable

# Keep CameraX / Camera2 extension callback interfaces and model classes used via reflection.
-keep class androidx.camera.extensions.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mlkit.** { *; }

# Keep the TFLite labels/model loading entry points in the advanced sample.
-keep class com.example.android.camera2.integration.advanced.** { *; }

# Do not strip the AIDL/parcelable used by the camera pipeline.
-keep class android.hardware.camera2.** { *; }
