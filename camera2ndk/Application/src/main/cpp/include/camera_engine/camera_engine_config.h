#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum CameraEngineAlgorithm {
    CAMERA_ENGINE_ALGORITHM_DENOISE = 1,
    CAMERA_ENGINE_ALGORITHM_SHARPEN = 2,
    CAMERA_ENGINE_ALGORITHM_HDR = 3,
    CAMERA_ENGINE_ALGORITHM_CLAHE = 4,
    CAMERA_ENGINE_ALGORITHM_SATURATION = 5,
    CAMERA_ENGINE_ALGORITHM_FACE_DETECT = 6,
    CAMERA_ENGINE_ALGORITHM_BOKEH = 7
} CameraEngineAlgorithm;

typedef enum CameraEnginePipelineType {
    CAMERA_ENGINE_PIPELINE_PREVIEW = 1,
    CAMERA_ENGINE_PIPELINE_CAPTURE = 2
} CameraEnginePipelineType;

#define CAMERA_ENGINE_PARAM_DENOISE_STRENGTH      1001u
#define CAMERA_ENGINE_PARAM_SHARPEN_STRENGTH      2001u
#define CAMERA_ENGINE_PARAM_HDR_STRENGTH          3001u
#define CAMERA_ENGINE_PARAM_CLAHE_CLIP_LIMIT      4001u
#define CAMERA_ENGINE_PARAM_SATURATION_FACTOR     5001u
#define CAMERA_ENGINE_PARAM_BOKEH_STRENGTH        6001u

typedef struct CameraEnginePreviewConfig {
    uint32_t struct_size;
    int32_t enable_face_detect;
    int32_t enable_denoise;
    int32_t enable_sharpen;
    int32_t enable_clahe;
    uint32_t face_detect_interval;
    uint32_t analysis_max_side;
    float denoise_strength;
    float sharpen_strength;
    float clahe_clip_limit;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEnginePreviewConfig;

typedef struct CameraEngineCaptureConfig {
    uint32_t struct_size;
    int32_t enable_denoise;
    int32_t enable_sharpen;
    int32_t enable_hdr;
    int32_t enable_clahe;
    int32_t enable_saturation;
    int32_t enable_bokeh;
    int32_t enable_face_detect;
    float denoise_strength;
    float sharpen_strength;
    float hdr_strength;
    float clahe_clip_limit;
    float saturation_factor;
    float bokeh_strength;
    uint32_t jpeg_quality;
    uint32_t reserved[16];
    void* reserved_ptr[4];
} CameraEngineCaptureConfig;

typedef struct CameraEngineAlgorithmParam {
    uint32_t struct_size;
    CameraEnginePipelineType pipeline;
    CameraEngineAlgorithm algorithm;
    uint32_t param_id;
    float value;
    uint32_t reserved[8];
} CameraEngineAlgorithmParam;

#ifdef __cplusplus
}
#endif
