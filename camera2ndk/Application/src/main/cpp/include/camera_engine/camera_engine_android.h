#pragma once

#include <stdint.h>
#include <android/hardware_buffer.h>
#include "camera_engine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CameraEngineAndroidHardwareBufferFrame {
    uint32_t struct_size;
    AHardwareBuffer* hardware_buffer;
    CameraEngineFrameMetadata metadata;
    int32_t android_format;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineAndroidHardwareBufferFrame;

CameraEngineStatus camera_engine_android_process_preview_hardware_buffer(
    CameraEngineContext* context,
    const CameraEngineAndroidHardwareBufferFrame* frame,
    CameraEnginePreviewResult* result
);

CameraEngineStatus camera_engine_android_process_capture_hardware_buffers(
    CameraEngineContext* context,
    const CameraEngineAndroidHardwareBufferFrame* frames,
    uint32_t frame_count,
    CameraEngineCaptureResult* result
);

#ifdef __cplusplus
}
#endif
