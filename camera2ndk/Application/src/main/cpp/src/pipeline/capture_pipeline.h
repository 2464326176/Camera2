/**
 * Still capture processing pipeline interface.
 *
 * This header declares the higher-quality pipeline used for captured photos,
 * where more expensive processing can be applied than in preview.
 */
#pragma once
#include "src/pipeline/pipeline_base.h"
#include "src/core/frame.h"
#include "src/algorithms/denoise.h"
#include "src/algorithms/sharpen.h"
#include "src/encode/jpeg_encoder.h"
#include <memory>
#include <mutex>

namespace camera_engine {

/**
 * Quality-oriented processing pipeline used for still photo capture.
 */
class CapturePipeline : public PipelineBase {
public:
    CapturePipeline() = default;

    /** Applies still-capture configuration and quality options. */
    ResultCode configure(const PipelineConfig& config) override;
    /** Enables or disables a capture-stage algorithm. */
    void enableAlgorithm(AlgorithmId id, bool enable) override;
    /** Updates a capture algorithm parameter at runtime. */
    void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) override;

    /** Processes one or more still-capture frames and returns image/JPEG output. */
    CaptureResult process(const std::vector<YuvFrame>& frames);

private:
    PipelineConfig m_config;
    bool m_sharpenEnabled = true;
    float m_sharpenStrength = 0.15f;
    float m_sharpenRadius = 2.0f;
    std::mutex m_mutex;
};

} // namespace camera_engine