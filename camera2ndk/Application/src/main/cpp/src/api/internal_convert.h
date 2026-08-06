/**
 * Shared internal conversion helpers for the public C ABI.
 *
 * Both the cross-platform (camera_engine_api.cpp) and the Android-specific
 * (camera_engine_android.cpp) ABI implementations need the same conversions
 * between public C structs and the internal camera_engine types. Keeping them
 * in one place avoids copy drift between the two files.
 */
#pragma once

#include <cstdint>
#include <vector>

#include "camera_engine/camera_engine.h"
#include "src/core/types.h"
#include "src/core/metadata.h"

namespace camera_engine {
namespace internal {

// Converts public frame metadata into the internal FrameMetadata used by the
// pipelines. Identical semantics for both the CPU and HardwareBuffer paths.
FrameMetadata toInternalMetadata(const CameraEngineFrameMetadata& metadata);

// Maps an internal processing result to the public-facing status code.
CameraEngineStatus toPublicStatus(ResultCode status);

// Copies detected faces into the caller-provided result array, honoring
// face_capacity. No-op when result or its face buffer is missing.
void copyFaces(const std::vector<FaceRect>& faces, CameraEnginePreviewResult* result);

// Writes encoded JPEG bytes into the mutable output buffer, reporting the
// required capacity when output is null or too small.
bool toJpegOutput(const std::vector<uint8_t>& jpeg, CameraEngineMutableBuffer* output, uint32_t* required);

} // namespace internal
} // namespace camera_engine
