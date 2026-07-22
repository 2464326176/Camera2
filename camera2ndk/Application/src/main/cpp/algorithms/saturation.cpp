#include "saturation.h"
#include <opencv2/imgproc.hpp>

namespace camera_engine {

cv::Mat SaturationAdjuster::adjust(const cv::Mat& bgr, float factor) {
    if (bgr.empty()) return bgr;

    cv::Mat hsv;
    cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);

    std::vector<cv::Mat> channels;
    cv::split(hsv, channels);
    channels[1] = channels[1] * factor;
    cv::min(channels[1], 255, channels[1]);
    cv::merge(channels, hsv);

    cv::Mat result;
    cv::cvtColor(hsv, result, cv::COLOR_HSV2BGR);
    return result;
}

} // namespace camera_engine