/**
 * Public C ABI implementation for the native camera engine.
 *
 * This file keeps the exported API stable while delegating actual work to the
 * internal C++ camera engine context, pipelines, and platform adapters.
 */
#include "camera_engine/camera_engine.h"

#include <algorithm>
#include <cstring>
#include <new>
#include <vector>

#include <opencv2/core.hpp>

#include "camera_engine_context.h"

namespace {

bool hasValidStructSize(const void* value, uint32_t structSize) {
    return value != nullptr && structSize > 0;
}

camera_engine::FrameMetadata toInternalMetadata(const CameraEngineFrameMetadata& metadata) {
    camera_engine::FrameMetadata internal{};
    internal.timestampNs = metadata.timestamp_ns;
    internal.iso = metadata.iso;
    internal.exposureTimeNs = metadata.exposure_time_ns;
    return internal;
}

camera_engine::YuvFormat toInternalYuvFormat(CameraEnginePixelFormat format) {
    switch (format) {
        case CAMERA_ENGINE_PIXEL_FORMAT_YUV_420_888:
            return camera_engine::YuvFormat::I420;
        case CAMERA_ENGINE_PIXEL_FORMAT_NV21:
        default:
            return camera_engine::YuvFormat::NV21;
    }
}

camera_engine::YuvPlane toInternalPlane(const CameraEngineImagePlane& plane) {
    camera_engine::YuvPlane internal{};
    internal.data = plane.data;
    internal.rowStride = static_cast<int>(plane.row_stride);
    internal.pixelStride = static_cast<int>(plane.pixel_stride == 0 ? 1 : plane.pixel_stride);
    return internal;
}

bool makeYuvFrame(const CameraEngineFrame* frame, camera_engine::YuvFrame* out) {
    if (frame == nullptr || out == nullptr || frame->image.width == 0 || frame->image.height == 0) return false;
    if (frame->image.format != CAMERA_ENGINE_PIXEL_FORMAT_NV21 &&
        frame->image.format != CAMERA_ENGINE_PIXEL_FORMAT_YUV_420_888) return false;
    if (frame->image.plane_count == 0 || frame->image.planes[0].data == nullptr) return false;

    camera_engine::YuvPlane yPlane = toInternalPlane(frame->image.planes[0]);
    camera_engine::YuvPlane uPlane{};
    camera_engine::YuvPlane vPlane{};
    if (frame->image.format == CAMERA_ENGINE_PIXEL_FORMAT_NV21) {
        if (frame->image.plane_count < 1) return false;
        uPlane = toInternalPlane(frame->image.planes[0]);
        uPlane.data = frame->image.planes[0].data + frame->image.width * frame->image.height;
        uPlane.rowStride = static_cast<int>(frame->image.width);
        uPlane.pixelStride = 2;
    } else {
        if (frame->image.plane_count < 3 || frame->image.planes[1].data == nullptr || frame->image.planes[2].data == nullptr) return false;
        uPlane = toInternalPlane(frame->image.planes[1]);
        vPlane = toInternalPlane(frame->image.planes[2]);
    }

    *out = camera_engine::YuvFrame(
        static_cast<int>(frame->image.width),
        static_cast<int>(frame->image.height),
        toInternalYuvFormat(frame->image.format),
        toInternalMetadata(frame->metadata),
        yPlane,
        uPlane,
        vPlane);
    return out->isValid();
}

void copyFaces(const std::vector<camera_engine::FaceRect>& faces, CameraEnginePreviewResult* result) {
    if (result == nullptr || result->faces == nullptr || result->face_capacity == 0) return;
    const uint32_t count = std::min<uint32_t>(static_cast<uint32_t>(faces.size()), result->face_capacity);
    for (uint32_t i = 0; i < count; ++i) {
        CameraEngineFace& dst = result->faces[i];
        std::memset(&dst, 0, sizeof(CameraEngineFace));
        dst.struct_size = sizeof(CameraEngineFace);
        dst.rect.left = static_cast<float>(faces[i].x);
        dst.rect.top = static_cast<float>(faces[i].y);
        dst.rect.right = static_cast<float>(faces[i].x + faces[i].w);
        dst.rect.bottom = static_cast<float>(faces[i].y + faces[i].h);
        dst.score = faces[i].confidence;
    }
    result->face_count = count;
}

bool toMutableOutput(const cv::Mat& image, CameraEngineImageBuffer* output) {
    if (output == nullptr) return true;
    if (output->format != CAMERA_ENGINE_PIXEL_FORMAT_BGR_888 && output->format != CAMERA_ENGINE_PIXEL_FORMAT_RGB_888 && output->format != CAMERA_ENGINE_PIXEL_FORMAT_RGBA_8888) return false;
    if (output->plane_count < 1 || output->planes[0].data == nullptr) return false;
    const uint32_t channels = output->format == CAMERA_ENGINE_PIXEL_FORMAT_RGBA_8888 ? 4u : 3u;
    if (image.empty() || image.cols != static_cast<int>(output->width) || image.rows != static_cast<int>(output->height)) return false;
    const uint32_t rowBytes = output->width * channels;
    if (output->planes[0].row_stride < rowBytes) return false;
    for (uint32_t r = 0; r < output->height; ++r) {
        std::memcpy(output->planes[0].data + r * output->planes[0].row_stride, image.ptr(static_cast<int>(r)), rowBytes);
    }
    output->planes[0].size_bytes = output->planes[0].row_stride * output->height;
    return true;
}

bool toJpegOutput(const std::vector<uint8_t>& jpeg, CameraEngineMutableBuffer* output, uint32_t* required) {
    if (required != nullptr) *required = static_cast<uint32_t>(jpeg.size());
    if (output == nullptr) return true;
    if (output->data == nullptr || output->capacity < jpeg.size()) return false;
    std::memcpy(output->data, jpeg.data(), jpeg.size());
    output->size = static_cast<uint32_t>(jpeg.size());
    return true;
}

bool toInternalAlgorithm(CameraEngineAlgorithm algorithm, camera_engine::AlgorithmId* out) {
    if (out == nullptr) return false;
    switch (algorithm) {
        case CAMERA_ENGINE_ALGORITHM_FACE_DETECT: *out = camera_engine::AlgorithmId::FACE_DETECT; return true;
        case CAMERA_ENGINE_ALGORITHM_DENOISE: *out = camera_engine::AlgorithmId::DENOISE; return true;
        case CAMERA_ENGINE_ALGORITHM_SHARPEN: *out = camera_engine::AlgorithmId::SHARPEN; return true;
        case CAMERA_ENGINE_ALGORITHM_BOKEH: *out = camera_engine::AlgorithmId::BOKEH; return true;
        case CAMERA_ENGINE_ALGORITHM_HDR: *out = camera_engine::AlgorithmId::HDR; return true;
        case CAMERA_ENGINE_ALGORITHM_CLAHE: *out = camera_engine::AlgorithmId::CLAHE; return true;
        case CAMERA_ENGINE_ALGORITHM_SATURATION: *out = camera_engine::AlgorithmId::SATURATION; return true;
        default: return false;
    }
}

} // namespace

const char* camera_engine_status_message(CameraEngineStatus status) {
    switch (status) {
        case CAMERA_ENGINE_OK: return "OK";
        case CAMERA_ENGINE_ERROR_INVALID_ARGUMENT: return "Invalid argument";
        case CAMERA_ENGINE_ERROR_INVALID_STATE: return "Invalid state";
        case CAMERA_ENGINE_ERROR_NOT_INITIALIZED: return "Not initialized";
        case CAMERA_ENGINE_ERROR_OUT_OF_MEMORY: return "Out of memory";
        case CAMERA_ENGINE_ERROR_BUFFER_TOO_SMALL: return "Output buffer is too small";
        case CAMERA_ENGINE_ERROR_UNSUPPORTED_FORMAT: return "Unsupported image format";
        case CAMERA_ENGINE_ERROR_UNSUPPORTED_OPERATION: return "Unsupported operation";
        case CAMERA_ENGINE_ERROR_MODEL_LOAD_FAILED: return "Model load failed";
        case CAMERA_ENGINE_ERROR_PROCESS_FAILED: return "Image processing failed";
        case CAMERA_ENGINE_ERROR_ANDROID_BUFFER_LOCK_FAILED: return "Android buffer lock failed";
        default: return "Unknown error";
    }
}

void camera_engine_get_version(CameraEngineVersion* out_version) {
    if (out_version == nullptr) return;
    std::memset(out_version, 0, sizeof(CameraEngineVersion));
    out_version->struct_size = sizeof(CameraEngineVersion);
    out_version->api_major = CAMERA_ENGINE_API_VERSION_MAJOR;
    out_version->api_minor = CAMERA_ENGINE_API_VERSION_MINOR;
    out_version->api_patch = CAMERA_ENGINE_API_VERSION_PATCH;
    out_version->abi_version = CAMERA_ENGINE_ABI_VERSION;
}

void camera_engine_create_info_init(CameraEngineCreateInfo* info) {
    if (info == nullptr) return;
    std::memset(info, 0, sizeof(CameraEngineCreateInfo));
    info->struct_size = sizeof(CameraEngineCreateInfo);
}

void camera_engine_preview_config_init(CameraEnginePreviewConfig* config) {
    if (config == nullptr) return;
    std::memset(config, 0, sizeof(CameraEnginePreviewConfig));
    config->struct_size = sizeof(CameraEnginePreviewConfig);
    config->enable_face_detect = 1;
    config->enable_denoise = 1;
    config->enable_sharpen = 1;
    config->face_detect_interval = 5;
    config->analysis_max_side = 320;
    config->denoise_strength = 1.0f;
    config->sharpen_strength = 0.5f;
    config->clahe_clip_limit = 2.0f;
}

void camera_engine_capture_config_init(CameraEngineCaptureConfig* config) {
    if (config == nullptr) return;
    std::memset(config, 0, sizeof(CameraEngineCaptureConfig));
    config->struct_size = sizeof(CameraEngineCaptureConfig);
    config->enable_denoise = 1;
    config->enable_sharpen = 1;
    config->enable_hdr = 1;
    config->enable_clahe = 1;
    config->enable_saturation = 1;
    config->jpeg_quality = 95;
    config->denoise_strength = 1.0f;
    config->sharpen_strength = 1.0f;
    config->hdr_strength = 0.7f;
    config->clahe_clip_limit = 2.0f;
    config->saturation_factor = 1.0f;
}

void camera_engine_frame_metadata_init(CameraEngineFrameMetadata* metadata) {
    if (metadata == nullptr) return;
    std::memset(metadata, 0, sizeof(CameraEngineFrameMetadata));
    metadata->struct_size = sizeof(CameraEngineFrameMetadata);
}

void camera_engine_preview_result_init(CameraEnginePreviewResult* result) {
    if (result == nullptr) return;
    std::memset(result, 0, sizeof(CameraEnginePreviewResult));
    result->struct_size = sizeof(CameraEnginePreviewResult);
}

void camera_engine_capture_result_init(CameraEngineCaptureResult* result) {
    if (result == nullptr) return;
    std::memset(result, 0, sizeof(CameraEngineCaptureResult));
    result->struct_size = sizeof(CameraEngineCaptureResult);
}

CameraEngineStatus camera_engine_create(
    const CameraEngineCreateInfo* create_info,
    CameraEngineContext** out_context
) {
    if (out_context == nullptr) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    *out_context = nullptr;
    try {
        auto* context = new CameraEngineContext();
        if (create_info != nullptr) {
            if (create_info->asset_dir != nullptr) context->assetDir = create_info->asset_dir;
            if (create_info->cache_dir != nullptr) context->cacheDir = create_info->cache_dir;
        }
        context->previewPipeline = std::make_unique<camera_engine::PreviewPipeline>();
        context->capturePipeline = std::make_unique<camera_engine::CapturePipeline>();
        *out_context = context;
        return CAMERA_ENGINE_OK;
    } catch (const std::bad_alloc&) {
        return CAMERA_ENGINE_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return CAMERA_ENGINE_ERROR_UNKNOWN;
    }
}

void camera_engine_destroy(CameraEngineContext* context) {
    delete context;
}

CameraEngineStatus camera_engine_configure_preview(
    CameraEngineContext* context,
    const CameraEnginePreviewConfig* config
) {
    if (context == nullptr || !hasValidStructSize(config, config ? config->struct_size : 0)) {
        return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    camera_engine::PipelineConfig internalConfig;
    internalConfig.faceDetectEnabled = config->enable_face_detect != 0;
    internalConfig.faceDetectIntervalMs = static_cast<int>(config->face_detect_interval);
    return context->previewPipeline->configure(internalConfig) == camera_engine::ResultCode::OK
        ? CAMERA_ENGINE_OK
        : CAMERA_ENGINE_ERROR_PROCESS_FAILED;
}

CameraEngineStatus camera_engine_configure_capture(
    CameraEngineContext* context,
    const CameraEngineCaptureConfig* config
) {
    if (context == nullptr || !hasValidStructSize(config, config ? config->struct_size : 0)) {
        return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    camera_engine::PipelineConfig internalConfig;
    internalConfig.faceDetectEnabled = config->enable_face_detect != 0;
    internalConfig.jpegQuality = static_cast<int>(config->jpeg_quality);
    return context->capturePipeline->configure(internalConfig) == camera_engine::ResultCode::OK
        ? CAMERA_ENGINE_OK
        : CAMERA_ENGINE_ERROR_PROCESS_FAILED;
}

CameraEngineStatus camera_engine_set_algorithm_param(
    CameraEngineContext* context,
    const CameraEngineAlgorithmParam* param
) {
    if (context == nullptr || !hasValidStructSize(param, param ? param->struct_size : 0)) {
        return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    camera_engine::AlgorithmId id;
    if (!toInternalAlgorithm(param->algorithm, &id)) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    const char* key = "value";
    switch (param->param_id) {
        case CAMERA_ENGINE_PARAM_DENOISE_STRENGTH: key = "denoise_strength"; break;
        case CAMERA_ENGINE_PARAM_SHARPEN_STRENGTH: key = "sharpen_strength"; break;
        case CAMERA_ENGINE_PARAM_HDR_STRENGTH: key = "hdr_strength"; break;
        case CAMERA_ENGINE_PARAM_CLAHE_CLIP_LIMIT: key = "clahe_clip_limit"; break;
        case CAMERA_ENGINE_PARAM_SATURATION_FACTOR: key = "saturation_factor"; break;
        case CAMERA_ENGINE_PARAM_BOKEH_STRENGTH: key = "bokeh_strength"; break;
        default: return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    if (param->pipeline == CAMERA_ENGINE_PIPELINE_PREVIEW) {
        context->previewPipeline->setAlgorithmParam(id, key, param->value);
    } else if (param->pipeline == CAMERA_ENGINE_PIPELINE_CAPTURE) {
        context->capturePipeline->setAlgorithmParam(id, key, param->value);
    } else {
        return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    return CAMERA_ENGINE_OK;
}

CameraEngineStatus camera_engine_enable_algorithm(
    CameraEngineContext* context,
    CameraEnginePipelineType pipeline,
    CameraEngineAlgorithm algorithm,
    int32_t enabled
) {
    if (context == nullptr) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    camera_engine::AlgorithmId id;
    if (!toInternalAlgorithm(algorithm, &id)) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    if (pipeline == CAMERA_ENGINE_PIPELINE_PREVIEW) {
        context->previewPipeline->enableAlgorithm(id, enabled != 0);
    } else if (pipeline == CAMERA_ENGINE_PIPELINE_CAPTURE) {
        context->capturePipeline->enableAlgorithm(id, enabled != 0);
    } else {
        return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    }
    return CAMERA_ENGINE_OK;
}

CameraEngineStatus camera_engine_process_preview(
    CameraEngineContext* context,
    const CameraEngineFrame* frame,
    CameraEnginePreviewResult* result
) {
    if (context == nullptr || frame == nullptr) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    camera_engine::YuvFrame yuvFrame;
    if (!makeYuvFrame(frame, &yuvFrame)) return CAMERA_ENGINE_ERROR_UNSUPPORTED_FORMAT;
    const camera_engine::PreviewResult internalResult = context->previewPipeline->process(yuvFrame);
    if (result != nullptr) {
        result->face_count = 0;
        copyFaces(internalResult.faces, result);
    }
    return CAMERA_ENGINE_OK;
}

CameraEngineStatus camera_engine_process_capture(
    CameraEngineContext* context,
    const CameraEngineFrame* frames,
    uint32_t frame_count,
    CameraEngineCaptureResult* result
) {
    if (context == nullptr || frames == nullptr || frame_count == 0) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    std::vector<camera_engine::YuvFrame> yuvFrames;
    yuvFrames.reserve(frame_count);
    for (uint32_t i = 0; i < frame_count; ++i) {
        camera_engine::YuvFrame yuvFrame;
        if (!makeYuvFrame(&frames[i], &yuvFrame)) return CAMERA_ENGINE_ERROR_UNSUPPORTED_FORMAT;
        yuvFrames.push_back(yuvFrame);
    }
    const camera_engine::CaptureResult internalResult = context->capturePipeline->process(yuvFrames);
    if (internalResult.jpegData.empty()) return CAMERA_ENGINE_ERROR_PROCESS_FAILED;
    if (result != nullptr) {
        result->face_count = 0;
        if (!toJpegOutput(internalResult.jpegData, result->jpeg_output, &result->required_jpeg_capacity)) {
            return CAMERA_ENGINE_ERROR_BUFFER_TOO_SMALL;
        }
    }
    return CAMERA_ENGINE_OK;
}

CameraEngineStatus camera_engine_reset(CameraEngineContext* context) {
    if (context == nullptr) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    return CAMERA_ENGINE_OK;
}
