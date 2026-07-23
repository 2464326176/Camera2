/**
 * Image sharpening interface.
 *
 * This header declares an unsharp-mask based enhancer for improving perceived
 * detail after denoising or other smoothing operations.
 */
#pragma once
#include <opencv2/core.hpp>

namespace camera_engine {

class Sharpener {
public:
    static cv::Mat sharpen(const cv::Mat& bgr, float strength = 0.15f, float radius = 2.0f);
};

} // namespace camera_engine