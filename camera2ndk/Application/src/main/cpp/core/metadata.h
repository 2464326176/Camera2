#pragma once
#include <cstdint>

namespace camera_engine {

struct FrameMetadata {
    int64_t timestampNs = 0;
    int32_t iso = 100;
    int64_t exposureTimeNs = 0;
    int32_t flashState = 0;
    float lensAperture = 0.0f;
    int32_t aeState = 0;
    int32_t afState = 0;
    int32_t awbState = 0;
};

} // namespace camera_engine