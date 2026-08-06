/**
 * Shared internal conversion helpers for the public C ABI.
 *
 * Single implementation of the conversions used by both the cross-platform and
 * Android-specific ABI entry points. See internal_convert.h.
 */
#include "internal_convert.h"

#include <algorithm>
#include <cstring>

namespace camera_engine {
namespace internal {

FrameMetadata toInternalMetadata(const CameraEngineFrameMetadata& metadata) {
    FrameMetadata internal{};
    internal.timestampNs = metadata.timestamp_ns;
    internal.iso = metadata.iso;
    internal.exposureTimeNs = metadata.exposure_time_ns;
    internal.flashState = metadata.flash_state;
    internal.lensAperture = metadata.aperture;
    internal.aeState = metadata.ae_state;
    internal.afState = metadata.af_state;
    internal.awbState = metadata.awb_state;
    internal.focalLength = metadata.focal_length;
    internal.focusDistance = metadata.focus_distance;
    internal.rotation = static_cast<int32_t>(metadata.rotation);
    internal.lensFacing = static_cast<int32_t>(metadata.lens_facing);
    internal.frameNumber = metadata.frame_number;
    internal.approximate = metadata.approximate != 0;
    return internal;
}

CameraEngineStatus toPublicStatus(ResultCode status) {
    switch (status) {
        case ResultCode::OK: return CAMERA_ENGINE_OK;
        case ResultCode::FRAME_SKIPPED: return CAMERA_ENGINE_SKIPPED;
        case ResultCode::NOT_READY: return CAMERA_ENGINE_NOT_READY;
        case ResultCode::INIT_FAILED: return CAMERA_ENGINE_ERROR_INIT_FAILED;
        default: return CAMERA_ENGINE_ERROR_PROCESS_FAILED;
    }
}

void copyFaces(const std::vector<FaceRect>& faces, CameraEnginePreviewResult* result) {
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
        CameraEnginePoint* points[5] = {
            &dst.left_eye, &dst.right_eye, &dst.nose,
            &dst.mouth_left, &dst.mouth_right};
        for (int p = 0; p < 5; ++p) {
            points[p]->x = faces[i].landmarks[p * 2];
            points[p]->y = faces[i].landmarks[p * 2 + 1];
        }
    }
    result->face_count = count;
}

bool toJpegOutput(const std::vector<uint8_t>& jpeg, CameraEngineMutableBuffer* output, uint32_t* required) {
    if (required != nullptr) *required = static_cast<uint32_t>(jpeg.size());
    if (output == nullptr) return true;
    if (output->data == nullptr || output->capacity < jpeg.size()) return false;
    std::memcpy(output->data, jpeg.data(), jpeg.size());
    output->size = static_cast<uint32_t>(jpeg.size());
    return true;
}

} // namespace internal
} // namespace camera_engine
