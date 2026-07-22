#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class HdrToneMap {
public:
    static cv::Mat apply(const cv::Mat& bgr, float gamma = 1.0f, float intensity = 0.5f);
};

} // namespace camera_engine