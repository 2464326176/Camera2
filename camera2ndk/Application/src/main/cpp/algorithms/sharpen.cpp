#include "sharpen.h"
#include <opencv2/imgproc.hpp>

namespace camera_engine {

cv::Mat Sharpener::sharpen(const cv::Mat& bgr, float strength, float radius) {
    if (bgr.empty()) return cv::Mat();
    cv::Mat blurred;
    cv::GaussianBlur(bgr, blurred, cv::Size(0, 0), radius);
    cv::Mat result;
    cv::addWeighted(bgr, 1.0f + strength, blurred, -strength, 0, result);
    return result;
}

} // namespace camera_engine