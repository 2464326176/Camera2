/**
 * Still capture processing pipeline interface.
 *
 * This header declares the higher-quality pipeline used for captured photos,
 * where more expensive processing can be applied than in preview.
 */
#pragma once
#include "src/pipeline/pipeline_base.h"
#include "src/core/frame.h"
#include "src/algorithm/algorithm_manager.h"
#include "src/decision/decision.h"
#include "src/encode/jpeg_encoder.h"
#include <memory>
#include <mutex>

namespace camera_engine {

/**
 * Quality-oriented processing pipeline used for still photo capture.
 */
class CapturePipeline : public PipelineBase {
public:
    CapturePipeline();

    /** Applies still-capture configuration and quality options. */
    ResultCode configure(const PipelineConfig& config) override;
    /** Enables or disables a capture-stage algorithm. */
    void enableAlgorithm(AlgorithmId id, bool enable) override;
    /** Updates a capture algorithm parameter at runtime. */
    void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) override;

    void updateSessionControl(const SessionControl& control);
    CaptureAdvice adviseCapture(const FrameMetadata& metadata) const;

    /** Processes one or more still-capture frames and returns image/JPEG output. */
    CaptureResult process(const std::vector<YuvFrame>& frames);

private:
    PipelineConfig m_config;
    SessionControl m_control;
    CaptureDecision m_decision;
    AlgorithmManager m_algorithmManager;
    mutable std::mutex m_mutex;
};

} // namespace camera_engine