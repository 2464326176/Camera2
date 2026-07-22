#include "clahe.h"
#include <opencv2/imgproc.hpp>

namespace camera_engine {

cv::Mat ClaheEnhancer::apply(const cv::Mat& bgr, float clipLimit, int tileGridSize) {
    if (bgr.empty()) return bgr;

    cv::Mat lab;
    cv::cvtColor(bgr, lab, cv::COLOR_BGR2Lab);

    std::vector<cv::Mat> channels;
    cv::split(lab, channels);

    auto clahe = cv::createCLAHE(clipLimit, cv::Size(tileGridSize, tileGridSize));
    clahe->apply(channels[0], channels[0]);

    cv::merge(channels, lab);
    cv::Mat result;
    cv::cvtColor(lab, result, cv::COLOR_Lab2BGR);
    return result;
}

} // namespace camera_engine