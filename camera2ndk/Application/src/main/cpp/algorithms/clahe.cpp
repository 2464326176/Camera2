/**
 * CLAHE contrast enhancement implementation.
 *
 * This file applies contrast limited adaptive histogram equalization on the
 * luminance channel to improve local detail while limiting noise amplification.
 */
#include "clahe.h"
#include "../core/opencv2/imgproc.hpp"

namespace camera_engine {

/**
 * Enhances local luminance contrast with CLAHE while preserving color channels.
 */
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