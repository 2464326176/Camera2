/**
 * JPEG encoding interface.
 *
 * This header exposes a compact encoder for converting processed BGR frames
 * into JPEG byte streams before returning data to the Java layer.
 */
#pragma once
#include "../core/types.h"
#include "../core/frame.h"
#include "../core/opencv2/core.hpp"
#include <vector>
#include <cstdint>

namespace camera_engine {

/**
 * Stateless JPEG encoder for processed camera frames.
 */
class JpegEncoder {
public:
    /**
     * Encodes a BGR image directly into a JPEG byte buffer.
     */
    static std::vector<uint8_t> encodeBgr(const cv::Mat& bgr, int quality = 95);

    /**
     * Converts a YUV frame to BGR and encodes it into a JPEG byte buffer.
     */
    static std::vector<uint8_t> encodeYuv(const YuvFrame& frame, int quality = 95);
};

} // namespace camera_engine