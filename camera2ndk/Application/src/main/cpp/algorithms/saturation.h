#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class SaturationAdjuster {
public:
    static cv::Mat adjust(const cv::Mat& bgr, float factor = 1.05f);
};

} // namespace camera_engine