#pragma once

#include <stdint.h>
#include "camera_engine_result.h"
#include "camera_engine_types.h"
#include "camera_engine_buffer.h"
#include "camera_engine_config.h"
#include "camera_engine_version.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CameraEngineContext CameraEngineContext;

typedef struct CameraEngineCreateInfo {
    uint32_t struct_size;
    const char* asset_dir;
    const char* cache_dir;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineCreateInfo;

typedef struct CameraEnginePreviewResult {
    uint32_t struct_size;
    CameraEngineFace* faces;
    uint32_t face_capacity;
    uint32_t face_count;
    CameraEngineImageBuffer* output_image;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEnginePreviewResult;

typedef struct CameraEngineCaptureResult {
    uint32_t struct_size;
    CameraEngineImageBuffer* output_image;
    CameraEngineMutableBuffer* jpeg_output;
    uint32_t required_jpeg_capacity;
    CameraEngineFace* faces;
    uint32_t face_capacity;
    uint32_t face_count;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineCaptureResult;

void camera_engine_create_info_init(CameraEngineCreateInfo* info);
void camera_engine_preview_config_init(CameraEnginePreviewConfig* config);
void camera_engine_capture_config_init(CameraEngineCaptureConfig* config);
void camera_engine_frame_metadata_init(CameraEngineFrameMetadata* metadata);
void camera_engine_preview_result_init(CameraEnginePreviewResult* result);
void camera_engine_capture_result_init(CameraEngineCaptureResult* result);

CameraEngineStatus camera_engine_create(
    const CameraEngineCreateInfo* create_info,
    CameraEngineContext** out_context
);

void camera_engine_destroy(CameraEngineContext* context);

CameraEngineStatus camera_engine_configure_preview(
    CameraEngineContext* context,
    const CameraEnginePreviewConfig* config
);

CameraEngineStatus camera_engine_configure_capture(
    CameraEngineContext* context,
    const CameraEngineCaptureConfig* config
);

CameraEngineStatus camera_engine_set_algorithm_param(
    CameraEngineContext* context,
    const CameraEngineAlgorithmParam* param
);

CameraEngineStatus camera_engine_enable_algorithm(
    CameraEngineContext* context,
    CameraEnginePipelineType pipeline,
    CameraEngineAlgorithm algorithm,
    int32_t enabled
);

CameraEngineStatus camera_engine_process_preview(
    CameraEngineContext* context,
    const CameraEngineFrame* frame,
    CameraEnginePreviewResult* result
);

CameraEngineStatus camera_engine_process_capture(
    CameraEngineContext* context,
    const CameraEngineFrame* frames,
    uint32_t frame_count,
    CameraEngineCaptureResult* result
);

CameraEngineStatus camera_engine_reset(CameraEngineContext* context);

#ifdef __cplusplus
}
#endif
