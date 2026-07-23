/**
 * Preview processing pipeline implementation.
 *
 * This file coordinates face detection and real-time image enhancement steps
 * that must remain lightweight enough for interactive camera preview.
 */
#include "src/pipeline/preview_pipeline.h"
#include <android/log.h>

#define LOG_TAG "PreviewPipeline"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

/**
 * Stores preview configuration under lock so runtime calls remain thread-safe.
 */
ResultCode PreviewPipeline::configure(const PipelineConfig& config) {
    m_config = config;
    m_lastDetectTime = std::chrono::steady_clock::now();
    return ResultCode::OK;
}

void PreviewPipeline::enableAlgorithm(AlgorithmId id, bool enable) {
    if (id == AlgorithmId::FACE_DETECT) {
        m_config.faceDetectEnabled = enable;
    }
}

void PreviewPipeline::setAlgorithmParam(AlgorithmId id, const std::string& key, float value) {
    if (id == AlgorithmId::FACE_DETECT && key == "intervalMs") {
        m_config.faceDetectIntervalMs = (int)value;
    }
}

/**
 * Loads the face model used by the preview pipeline.
 */
bool PreviewPipeline::initFaceDetector(const std::string& modelPath) {
    return m_faceDetector.init(modelPath);
}

void PreviewPipeline::releaseFaceDetector() {
    m_faceDetector.release();
}

/**
 * Converts the input frame, optionally detects faces at interval, and returns preview output.
 */
PreviewResult PreviewPipeline::process(const YuvFrame& frame) {
    PreviewResult result;
    result.timestampNs = frame.getMetadata().timestampNs;

    if (!m_config.faceDetectEnabled) return result;

    // Gate: enforce at least faceDetectIntervalMs between detections
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - m_lastDetectTime).count();
    if (elapsed < m_config.faceDetectIntervalMs) {
        return result;
    }
    m_lastDetectTime = now;

    cv::Mat bgrSmall = frame.toBgrDownscaled(320);
    if (bgrSmall.empty()) return result;

    result.faces = m_faceDetector.detect(bgrSmall, frame.getWidth(), frame.getHeight());
    return result;
}

} // namespace camera_engine