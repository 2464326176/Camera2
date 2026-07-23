#pragma once

#include <stdint.h>
#include "camera_engine_types.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CAMERA_ENGINE_MAX_IMAGE_PLANES 4

typedef struct CameraEngineImagePlane {
    uint8_t* data;
    uint32_t row_stride;
    uint32_t pixel_stride;
    uint32_t size_bytes;
} CameraEngineImagePlane;

typedef struct CameraEngineImageBuffer {
    uint32_t struct_size;
    CameraEnginePixelFormat format;
    uint32_t width;
    uint32_t height;
    uint32_t plane_count;
    CameraEngineImagePlane planes[CAMERA_ENGINE_MAX_IMAGE_PLANES];
    void* user_data;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineImageBuffer;

typedef struct CameraEngineFrame {
    uint32_t struct_size;
    CameraEngineImageBuffer image;
    CameraEngineFrameMetadata metadata;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineFrame;

typedef struct CameraEngineMutableBuffer {
    uint32_t struct_size;
    uint8_t* data;
    uint32_t capacity;
    uint32_t size;
    uint32_t reserved[8];
    void* reserved_ptr[4];
} CameraEngineMutableBuffer;

#ifdef __cplusplus
}
#endif
