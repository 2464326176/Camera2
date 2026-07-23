/**
 * Face detection interface.
 *
 * This header declares a small OpenCV-backed detector used by preview and
 * bokeh processing to locate faces in camera frames.
 */
#pragma once
#include "../core/types.h"
#include "../core/opencv2/core.hpp"
#include "../core/opencv2/objdetect.hpp"
#include <string>
#include <vector>
#include <mutex>

namespace camera_engine {

/**
 * OpenCV-backed face detector used by preview and portrait effects.
 */
class FaceDetector {
public:
    FaceDetector() = default;
    ~FaceDetector();

    /** Loads the OpenCV YuNet face detection model from disk. */
    bool init(const std::string& modelPath);
    /** Releases detector resources and marks the detector unavailable. */
    void release();
    /** Returns whether the detector has been initialized successfully. */
    bool isReady() const { return m_ready; }

    /** Detects faces on a scaled BGR image and maps results to original size. */
    std::vector<FaceRect> detect(const cv::Mat& bgrSmall, int origWidth, int origHeight);

private:
    cv::Ptr<cv::FaceDetectorYN> m_detector;
    std::mutex m_mutex;
    bool m_ready = false;
    static const int DETECT_INPUT_MAX = 320;
};

} // namespace camera_engine