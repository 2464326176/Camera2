/**
 * Still capture processing pipeline implementation.
 *
 * This file applies quality-oriented image enhancement stages and optional JPEG
 * encoding for final photo capture output.
 */
#include "src/pipeline/capture_pipeline.h"
#include <android/log.h>
#include <unordered_map>

#define LOG_TAG "CapturePipeline"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

CapturePipeline::CapturePipeline()
    : m_algorithmManager(PipelineType::CAPTURE) {}

/**
 * Stores capture configuration for the next still-image processing request.
 */
ResultCode CapturePipeline::configure(const PipelineConfig& config) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_config = config;
    m_control.jpegQuality = config.jpegQuality;
    return ResultCode::OK;
}

void CapturePipeline::enableAlgorithm(AlgorithmId id, bool enable) {
    std::lock_guard<std::mutex> lock(m_mutex);
    // Maps each toggleable algorithm to the prefer flag it controls, so the
    // enable/disable switch stays in one place and matches CaptureDecision.
    static const std::unordered_map<AlgorithmId, bool SessionControl::*> kFlags = {
        {AlgorithmId::DENOISE, &SessionControl::preferDenoise},
        {AlgorithmId::SHARPEN, &SessionControl::preferSharpen},
        {AlgorithmId::HDR, &SessionControl::preferHdr},
        {AlgorithmId::CLAHE, &SessionControl::preferClahe},
        {AlgorithmId::SATURATION, &SessionControl::preferSaturation},
        {AlgorithmId::BOKEH, &SessionControl::preferBokeh},
    };
    const auto it = kFlags.find(id);
    if (it != kFlags.end()) m_control.*(it->second) = enable;
}

void CapturePipeline::setAlgorithmParam(AlgorithmId id, const std::string& key, float value) {
    (void) id;
    if (key == "jpegQuality") {
        m_control.jpegQuality = static_cast<int>(value);
    }
}

void CapturePipeline::updateSessionControl(const SessionControl& control) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_control = control;
}

CaptureAdvice CapturePipeline::adviseCapture(const FrameMetadata& metadata) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_decision.advise(m_control, metadata);
}

/**
 * Converts capture frames to BGR, applies enabled quality algorithms, and encodes JPEG output.
 * Decision evaluation is performed before BGR conversion to avoid wasted work on skipped frames.
 */
CaptureResult CapturePipeline::process(const std::vector<YuvFrame>& frames) {
    std::lock_guard<std::mutex> lock(m_mutex);
    CaptureResult result;
    if (frames.empty()) {
        result.status = ResultCode::ERROR;
        return result;
    }

    // Extract ISO from first frame metadata
    int iso = frames[0].getMetadata().iso;
    result.iso = iso;
    result.timestampNs = frames[0].getMetadata().timestampNs;

    // 1. YUV to BGR conversion (only when frame will actually be processed).
    // Metadata is collected per successfully converted frame so that the
    // metadata vector always stays in sync with bgrFrames.
    std::vector<cv::Mat> bgrFrames;
    std::vector<FrameMetadata> metadata;
    bgrFrames.reserve(frames.size());
    metadata.reserve(frames.size());
    for (const auto& frame : frames) {
        cv::Mat bgr = frame.toBgr();
        if (!bgr.empty()) {
            bgrFrames.push_back(bgr);
            metadata.push_back(frame.getMetadata());
        }
    }

    if (bgrFrames.empty()) return result;

    // 2. Evaluate decision plan AFTER BGR conversion. The decision operates on
    // the same frame set that will be processed.
    const DecisionPlan plan = m_decision.evaluate(m_control, metadata);
    if (plan.skipEntireFrame) {
        result.status = ResultCode::FRAME_SKIPPED;
        return result;
    }

    const AlgorithmInitInfo initInfo{
        m_config.assetDir, PipelineType::CAPTURE,
        frames.front().getWidth(), frames.front().getHeight()};
    result.status = m_algorithmManager.applyInit(plan.needInit, initInfo);
    if (result.status != ResultCode::OK) return result;

    AlgorithmContext context;
    context.sourceFrames = std::move(bgrFrames);
    context.image = context.sourceFrames.front();
    context.metadata = std::move(metadata);
    context.originalWidth = frames.front().getWidth();
    context.originalHeight = frames.front().getHeight();
    result.status = m_algorithmManager.execute(plan.stages, context);
    m_algorithmManager.applyUninit(plan.needUninit);
    if (result.status != ResultCode::OK || context.image.empty()) return result;

    // JPEG encode (BGR direct encode, skip NV21 intermediate step)
    result.jpegData = JpegEncoder::encodeBgr(context.image, m_control.jpegQuality);
    result.status = result.jpegData.empty() ? ResultCode::ERROR : ResultCode::OK;
    return result;
}

} // namespace camera_engine