/**
 * HDR tone adjustment implementation.
 *
 * This file applies local contrast enhancement in Lab color space to improve
 * shadow and highlight separation without changing image dimensions.
 */
#include "src/algorithms/hdr.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>

namespace camera_engine {

/**
 * Applies gamma correction and weighted blending to create a lightweight HDR-style image.
 */
cv::Mat HdrToneMap::apply(const cv::Mat& bgr, float gamma, float intensity) {
    if (bgr.empty()) return bgr;

    cv::Mat bgrFloat;
    bgr.convertTo(bgrFloat, CV_32FC3, 1.0 / 255.0);

    auto tonemap = cv::createTonemapReinhard(gamma, intensity, 0.0f, 0.0f);
    cv::Mat tonemapped;
    tonemap->process(bgrFloat, tonemapped);

    cv::Mat result;
    tonemapped.convertTo(result, CV_8UC3, 255.0);
    return result;
}

} // namespace camera_engine