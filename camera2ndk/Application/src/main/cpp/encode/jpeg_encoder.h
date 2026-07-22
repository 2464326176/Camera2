#pragma once
#include "../core/types.h"
#include "../core/frame.h"
#include <opencv2/core.hpp>
#include <vector>
#include <cstdint>

namespace camera_engine {

class JpegEncoder {
public:
    // BGR to JPEG (direct encode after denoise, optimized path)
    static std::vector<uint8_t> encodeBgr(const cv::Mat& bgr, int quality = 95);

    // YUV frame to JPEG (fallback path)
    static std::vector<uint8_t> encodeYuv(const YuvFrame& frame, int quality = 95);
};

} // namespace camera_engine