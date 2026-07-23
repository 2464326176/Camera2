/**
 * Face detection implementation.
 *
 * This file wraps OpenCV Haar cascade detection and converts detected face
 * rectangles into camera engine metadata structures.
 */
#include "face_detect.h"
#include <android/log.h>

#define LOG_TAG "FaceDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

FaceDetector::~FaceDetector() { release(); }

/**
 * Creates and configures the OpenCV face detector from the supplied model path.
 */
bool FaceDetector::init(const std::string& modelPath) {
    std::lock_guard<std::mutex> lock(m_mutex);
    try {
        m_detector = cv::FaceDetectorYN::create(
            modelPath, "",
            cv::Size(DETECT_INPUT_MAX, DETECT_INPUT_MAX),
            0.7f, 0.3f, 5000);
        m_ready = !m_detector.empty();
        if (m_ready) {
            LOGD("YuNet loaded from %s", modelPath.c_str());
        } else {
            LOGE("YuNet load failed");
        }
        return m_ready;
    } catch (const cv::Exception& e) {
        LOGE("YuNet init exception: %s", e.what());
        m_detector.release();
        return false;
    }
}

void FaceDetector::release() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_detector.release();
    m_ready = false;
}

/**
 * Detects faces on the smaller input image and rescales rectangles to original frame size.
 */
std::vector<FaceRect> FaceDetector::detect(const cv::Mat& bgrSmall, int origWidth, int origHeight) {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<FaceRect> faces;
    if (!m_ready || bgrSmall.empty()) return faces;

    try {
        m_detector->setInputSize(bgrSmall.size());
        cv::Mat facesMat;
        m_detector->detect(bgrSmall, facesMat);

        if (facesMat.rows == 0) return faces;

        float scaleX = (float)origWidth / bgrSmall.cols;
        float scaleY = (float)origHeight / bgrSmall.rows;

        for (int i = 0; i < facesMat.rows; i++) {
            FaceRect f;
            f.x = facesMat.at<float>(i, 0) * scaleX;
            f.y = facesMat.at<float>(i, 1) * scaleY;
            f.w = facesMat.at<float>(i, 2) * scaleX;
            f.h = facesMat.at<float>(i, 3) * scaleY;
            // right eye, left eye, nose tip, right mouth corner, left mouth corner
            for (int j = 0; j < 10; j++) {
                f.landmarks[j] = facesMat.at<float>(i, 4 + j);
            }
            // confidence
            if (facesMat.cols > 14) {
                f.confidence = facesMat.at<float>(i, 14);
            } else {
                f.confidence = 1.0f;
            }
            faces.push_back(f);
        }
    } catch (const cv::Exception& e) {
        LOGE("FaceDetector::detect: %s", e.what());
    }
    return faces;
}

} // namespace camera_engine