/**
 * Saturation adjustment interface.
 *
 * This header declares a helper for scaling color saturation in BGR camera
 * frames using HSV color space conversion.
 */
#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class SaturationAdjuster {
public:
    static cv::Mat adjust(const cv::Mat& bgr, float factor = 1.05f);
};

} // namespace camera_engine