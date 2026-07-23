/**
 * JPEG encoding implementation.
 *
 * This file uses OpenCV image encoding utilities to serialize BGR frames into
 * compressed JPEG buffers with caller-controlled quality.
 */
#include "jpeg_encoder.h"
#include "../core/opencv2/imgcodecs.hpp"
#include "../core/opencv2/imgproc.hpp"
#include <android/log.h>

#define LOG_TAG "JpegEncoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

/**
 * Encodes a BGR Mat to JPEG using OpenCV and clamps invalid input safely.
 */
std::vector<uint8_t> JpegEncoder::encodeBgr(const cv::Mat& bgr, int quality) {
    if (bgr.empty()) return {};
    std::vector<uint8_t> buf;
    std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, quality};
    try {
        cv::imencode(".jpg", bgr, buf, params);
    } catch (const cv::Exception& e) {
        LOGE("encodeBgr failed: %s", e.what());
    }
    return buf;
}

/**
 * Converts a YUV frame to BGR first, then reuses the BGR JPEG encoder.
 */
std::vector<uint8_t> JpegEncoder::encodeYuv(const YuvFrame& frame, int quality) {
    cv::Mat bgr = frame.toBgr();
    if (bgr.empty()) return {};
    return encodeBgr(bgr, quality);
}

} // namespace camera_engine