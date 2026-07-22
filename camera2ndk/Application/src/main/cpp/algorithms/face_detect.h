#pragma once
#include "../core/types.h"
#include <opencv2/core.hpp>
#include <opencv2/objdetect.hpp>
#include <string>
#include <vector>
#include <mutex>

namespace camera_engine {

class FaceDetector {
public:
    FaceDetector() = default;
    ~FaceDetector();

    bool init(const std::string& modelPath);
    void release();
    bool isReady() const { return m_ready; }

    std::vector<FaceRect> detect(const cv::Mat& bgrSmall, int origWidth, int origHeight);

private:
    cv::Ptr<cv::FaceDetectorYN> m_detector;
    std::mutex m_mutex;
    bool m_ready = false;
    static const int DETECT_INPUT_MAX = 320;
};

} // namespace camera_engine