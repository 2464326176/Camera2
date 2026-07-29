/**
 * Preview processing pipeline implementation.
 *
 * This file coordinates face detection and real-time image enhancement steps
 * that must remain lightweight enough for interactive camera preview.
 */
#include "src/pipeline/preview_pipeline.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "PreviewPipeline"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

PreviewPipeline::PreviewPipeline()
    : m_algorithmManager(PipelineType::PREVIEW) {}

/**
 * Stores preview configuration under lock so runtime calls remain thread-safe.
 */
ResultCode PreviewPipeline::configure(const PipelineConfig& config) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_config = config;
    m_decision.configure(config.faceDetectIntervalMs);
    return ResultCode::OK;
}

void PreviewPipeline::enableAlgorithm(AlgorithmId id, bool enable) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (id == AlgorithmId::FACE_DETECT) {
        m_control.preferFaceDetect = enable;
    }
}

void PreviewPipeline::setAlgorithmParam(AlgorithmId id, const std::string& key, float value) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (id == AlgorithmId::FACE_DETECT && key == "intervalMs") {
        m_config.faceDetectIntervalMs = (int)value;
    }
}

/**
 * Loads the face model used by the preview pipeline.
 */
bool PreviewPipeline::initFaceDetector(const std::string& modelPath) {
    const std::string suffix = "/face_detection_yunet_2023mar.onnx";
    if (modelPath.size() >= suffix.size() &&
        modelPath.compare(modelPath.size() - suffix.size(), suffix.size(), suffix) == 0) {
        m_config.assetDir = modelPath.substr(0, modelPath.size() - suffix.size());
    }
    AlgorithmInitInfo info{m_config.assetDir, PipelineType::PREVIEW,
                           m_config.width, m_config.height};
    return m_algorithmManager.applyInit({AlgorithmId::FACE_DETECT}, info)
        == ResultCode::OK;
}

void PreviewPipeline::releaseFaceDetector() {
    m_algorithmManager.applyUninit({AlgorithmId::FACE_DETECT});
    m_decision.reset();
}

void PreviewPipeline::updateSessionControl(const SessionControl& control) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_control = control;
}

/**
 * Converts the input frame, optionally detects faces at interval, and returns preview output.
 */
PreviewResult PreviewPipeline::process(const YuvFrame& frame) {
    std::lock_guard<std::mutex> lock(m_mutex);
    PreviewResult result;
    result.timestampNs = frame.getMetadata().timestampNs;

    const DecisionPlan plan = m_decision.evaluate(m_control, frame.getMetadata());
    m_algorithmManager.applyUninit(plan.needUninit);
    if (plan.skipEntireFrame) {
        result.status = ResultCode::FRAME_SKIPPED;
        return result;
    }

    cv::Mat bgrSmall = frame.toBgrDownscaled(
        std::max(64, m_control.analysisMaxSide));
    if (bgrSmall.empty()) {
        result.status = ResultCode::ERROR;
        return result;
    }

    const AlgorithmInitInfo initInfo{
        m_config.assetDir, PipelineType::PREVIEW,
        frame.getWidth(), frame.getHeight()};
    result.status = m_algorithmManager.applyInit(plan.needInit, initInfo);
    if (result.status != ResultCode::OK) return result;

    AlgorithmContext context;
    context.image = bgrSmall;
    context.metadata = {frame.getMetadata()};
    context.originalWidth = frame.getWidth();
    context.originalHeight = frame.getHeight();
    result.status = m_algorithmManager.execute(plan.stages, context);
    if (result.status == ResultCode::OK) result.faces = std::move(context.faces);
    m_algorithmManager.applyUninit(plan.needUninit);
    return result;
}

} // namespace camera_engine