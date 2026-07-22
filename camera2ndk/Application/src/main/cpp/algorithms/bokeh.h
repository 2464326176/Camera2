#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class BokehEffect {
public:
    static cv::Mat apply(const cv::Mat& bgr, float centerX, float centerY,
                         float focusRadius, float blurStrength);
private:
    static constexpr float SMOOTHSTEP_EDGE0 = 0.6f;
    static constexpr float SMOOTHSTEP_EDGE1 = 1.2f;
    static constexpr float ASPECT_RATIO = 1.2f;
};

} // namespace camera_engine