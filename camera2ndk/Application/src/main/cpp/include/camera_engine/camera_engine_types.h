#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum CameraEnginePixelFormat {
    CAMERA_ENGINE_PIXEL_FORMAT_UNKNOWN = 0,
    CAMERA_ENGINE_PIXEL_FORMAT_NV21 = 1,
    CAMERA_ENGINE_PIXEL_FORMAT_YUV_420_888 = 2,
    CAMERA_ENGINE_PIXEL_FORMAT_RGBA_8888 = 3,
    CAMERA_ENGINE_PIXEL_FORMAT_BGR_888 = 4,
    CAMERA_ENGINE_PIXEL_FORMAT_RGB_888 = 5
} CameraEnginePixelFormat;

typedef enum CameraEngineRotation {
    CAMERA_ENGINE_ROTATION_0 = 0,
    CAMERA_ENGINE_ROTATION_90 = 90,
    CAMERA_ENGINE_ROTATION_180 = 180,
    CAMERA_ENGINE_ROTATION_270 = 270
} CameraEngineRotation;

typedef enum CameraEngineLensFacing {
    CAMERA_ENGINE_LENS_UNKNOWN = 0,
    CAMERA_ENGINE_LENS_BACK = 1,
    CAMERA_ENGINE_LENS_FRONT = 2,
    CAMERA_ENGINE_LENS_EXTERNAL = 3
} CameraEngineLensFacing;

typedef struct CameraEngineSize {
    uint32_t width;
    uint32_t height;
} CameraEngineSize;

typedef struct CameraEngineRect {
    float left;
    float top;
    float right;
    float bottom;
} CameraEngineRect;

typedef struct CameraEnginePoint {
    float x;
    float y;
} CameraEnginePoint;

typedef struct CameraEngineFace {
    uint32_t struct_size;
    CameraEngineRect rect;
    float score;
    CameraEnginePoint left_eye;
    CameraEnginePoint right_eye;
    CameraEnginePoint nose;
    CameraEnginePoint mouth_left;
    CameraEnginePoint mouth_right;
    uint32_t reserved[8];
} CameraEngineFace;

typedef struct CameraEngineFrameMetadata {
    uint32_t struct_size;
    int64_t timestamp_ns;
    int32_t iso;
    int64_t exposure_time_ns;
    float aperture;
    float focal_length;
    float focus_distance;
    CameraEngineRotation rotation;
    CameraEngineLensFacing lens_facing;
    uint32_t frame_number;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineFrameMetadata;

#ifdef __cplusplus
}
#endif
