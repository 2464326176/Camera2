/**
 * Android-specific public C ABI extensions.
 *
 * This file exposes HardwareBuffer based entry points while keeping Android NDK
 * types out of the cross-platform camera_engine.h header.
 */
#include "camera_engine/camera_engine_android.h"

#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

#include "camera_engine_context.h"
#include "internal_convert.h"
#include "src/core/frame.h"
#include "src/platform/android/android_hardware_buffer.h"

namespace {

using camera_engine::internal::toInternalMetadata;
using camera_engine::internal::toPublicStatus;
using camera_engine::internal::copyFaces;
using camera_engine::internal::toJpegOutput;

bool makeHardwareBufferFrame(
    const CameraEngineAndroidHardwareBufferFrame* frame,
    std::shared_ptr<camera_engine::HardwareBufferRef>* outRef,
    camera_engine::YuvFrame* outFrame
) {
    if (frame == nullptr || frame->hardware_buffer == nullptr || outRef == nullptr || outFrame == nullptr) return false;
    auto bufferRef = std::make_shared<camera_engine::HardwareBufferRef>();
    if (!bufferRef->lock(frame->hardware_buffer)) return false;
    *outFrame = camera_engine::YuvFrame(bufferRef, toInternalMetadata(frame->metadata), camera_engine::YuvFormat::I420);
    *outRef = bufferRef;
    return outFrame->isValid();
}

} // namespace

CameraEngineStatus camera_engine_android_process_preview_hardware_buffer(
    CameraEngineContext* context,
    const CameraEngineAndroidHardwareBufferFrame* frame,
    CameraEnginePreviewResult* result
) {
    if (context == nullptr || frame == nullptr) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> contextLock(context->mutex);
    std::shared_ptr<camera_engine::HardwareBufferRef> bufferRef;
    camera_engine::YuvFrame yuvFrame;
    if (!makeHardwareBufferFrame(frame, &bufferRef, &yuvFrame)) return CAMERA_ENGINE_ERROR_ANDROID_BUFFER_LOCK_FAILED;
    const camera_engine::PreviewResult internalResult = context->previewPipeline->process(yuvFrame);
    if (result != nullptr) {
        result->status = toPublicStatus(internalResult.status);
        result->face_count = 0;
        copyFaces(internalResult.faces, result);
    }
    return toPublicStatus(internalResult.status);
}

CameraEngineStatus camera_engine_android_process_capture_hardware_buffers(
    CameraEngineContext* context,
    const CameraEngineAndroidHardwareBufferFrame* frames,
    uint32_t frame_count,
    CameraEngineCaptureResult* result
) {
    if (context == nullptr || frames == nullptr || frame_count == 0) return CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> contextLock(context->mutex);
    std::vector<std::shared_ptr<camera_engine::HardwareBufferRef>> bufferRefs;
    std::vector<camera_engine::YuvFrame> yuvFrames;
    bufferRefs.reserve(frame_count);
    yuvFrames.reserve(frame_count);
    for (uint32_t i = 0; i < frame_count; ++i) {
        std::shared_ptr<camera_engine::HardwareBufferRef> bufferRef;
        camera_engine::YuvFrame yuvFrame;
        if (!makeHardwareBufferFrame(&frames[i], &bufferRef, &yuvFrame)) return CAMERA_ENGINE_ERROR_ANDROID_BUFFER_LOCK_FAILED;
        bufferRefs.push_back(bufferRef);
        yuvFrames.push_back(yuvFrame);
    }
    const camera_engine::CaptureResult internalResult = context->capturePipeline->process(yuvFrames);
    if (internalResult.status != camera_engine::ResultCode::OK) {
        if (result != nullptr) result->status = toPublicStatus(internalResult.status);
        return toPublicStatus(internalResult.status);
    }
    if (internalResult.jpegData.empty()) return CAMERA_ENGINE_ERROR_PROCESS_FAILED;
    if (result != nullptr) {
        result->status = CAMERA_ENGINE_OK;
        result->face_count = 0;
        if (!toJpegOutput(internalResult.jpegData, result->jpeg_output, &result->required_jpeg_capacity)) {
            return CAMERA_ENGINE_ERROR_BUFFER_TOO_SMALL;
        }
    }
    return CAMERA_ENGINE_OK;
}
