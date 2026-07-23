/**
 * Portrait bokeh processing implementation.
 *
 * This file builds a face mask, blurs the background, and composites the result
 * to simulate shallow depth-of-field for portrait images.
 */
#include "src/algorithms/bokeh.h"
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "BokehEffect"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

cv::Mat BokehEffect::apply(const cv::Mat& bgr, float centerX, float centerY,
                           float focusRadius, float blurStrength) {
    if (bgr.empty()) return bgr;

    int w = bgr.cols, h = bgr.rows;
    cv::Mat blurred;
    int ksize = std::max(5, (int)(blurStrength * 30));
    if (ksize % 2 == 0) ksize++;
    cv::GaussianBlur(bgr, blurred, cv::Size(ksize, ksize), blurStrength * 10);

    cv::Mat mask(h, w, CV_32FC1);
    int cx = (int)(centerX * w);
    int cy = (int)(centerY * h);
    float rx = std::max(focusRadius * w, 1.0f);
    float ry = std::max(focusRadius * h * ASPECT_RATIO, 1.0f);

    cv::Mat xCoords(1, w, CV_32FC1);
    cv::Mat yCoords(h, 1, CV_32FC1);
    for (int x = 0; x < w; x++) xCoords.at<float>(x) = (float)(x - cx) / rx;
    for (int y = 0; y < h; y++) yCoords.at<float>(y) = (float)(y - cy) / ry;

    cv::Mat xGrid, yGrid;
    cv::repeat(xCoords, h, 1, xGrid);
    cv::repeat(yCoords, 1, w, yGrid);

    cv::Mat distGrid;
    cv::sqrt(xGrid.mul(xGrid) + yGrid.mul(yGrid), distGrid);

    cv::Mat weight;
    distGrid = (distGrid - SMOOTHSTEP_EDGE0) / (SMOOTHSTEP_EDGE1 - SMOOTHSTEP_EDGE0);
    cv::Mat ones = cv::Mat::ones(distGrid.size(), CV_32FC1);
    cv::Mat zeros = cv::Mat::zeros(distGrid.size(), CV_32FC1);
    cv::min(distGrid, ones, weight);
    cv::max(weight, zeros, weight);
    weight = weight.mul(weight).mul(3.0f - 2.0f * weight);

    cv::Mat bgrFloat, blurFloat;
    bgr.convertTo(bgrFloat, CV_32FC3);
    blurred.convertTo(blurFloat, CV_32FC3);

    std::vector<cv::Mat> maskChannels(3, mask);
    cv::Mat mask3;
    cv::merge(maskChannels, mask3);

    cv::Mat result;
    cv::Mat resultFloat = bgrFloat.mul(cv::Scalar(1, 1, 1) - mask3) + blurFloat.mul(mask3);
    resultFloat.convertTo(result, CV_8UC3);
    return result;
}

} // namespace camera_engine