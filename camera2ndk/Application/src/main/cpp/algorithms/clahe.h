/**
 * CLAHE contrast enhancement interface.
 *
 * This header declares an adaptive histogram equalization helper for improving
 * local contrast in camera frames.
 */
#pragma once
#include "../core/opencv2/core.hpp"

namespace camera_engine {

class ClaheEnhancer {
public:
    static cv::Mat apply(const cv::Mat& bgr, float clipLimit = 2.0f, int tileGridSize = 8);
};

} // namespace camera_engine