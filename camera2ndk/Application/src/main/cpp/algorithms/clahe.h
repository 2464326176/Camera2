#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class ClaheEnhancer {
public:
    static cv::Mat apply(const cv::Mat& bgr, float clipLimit = 2.0f, int tileGridSize = 8);
};

} // namespace camera_engine