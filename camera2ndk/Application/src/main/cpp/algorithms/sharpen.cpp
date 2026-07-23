/**
 * Image sharpening implementation.
 *
 * This file applies a lightweight unsharp-mask operation to enhance local
 * contrast while preserving the original frame dimensions and format.
 */
#include "sharpen.h"
#include "../core/opencv2/imgproc.hpp"

namespace camera_engine {

/**
 * Uses Gaussian blur subtraction to sharpen edges and fine image details.
 */
cv::Mat Sharpener::sharpen(const cv::Mat& bgr, float strength, float radius) {
    if (bgr.empty()) return cv::Mat();
    cv::Mat blurred;
    cv::GaussianBlur(bgr, blurred, cv::Size(0, 0), radius);
    cv::Mat result;
    cv::addWeighted(bgr, 1.0f + strength, blurred, -strength, 0, result);
    return result;
}

} // namespace camera_engine