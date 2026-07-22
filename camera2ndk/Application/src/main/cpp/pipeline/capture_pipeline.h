#pragma once
#include "pipeline_base.h"
#include "../core/frame.h"
#include "../algorithms/denoise.h"
#include "../algorithms/sharpen.h"
#include "../encode/jpeg_encoder.h"
#include <memory>
#include <mutex>

namespace camera_engine {

class CapturePipeline : public PipelineBase {
public:
    CapturePipeline() = default;

    ResultCode configure(const PipelineConfig& config) override;
    void enableAlgorithm(AlgorithmId id, bool enable) override;
    void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) override;

    CaptureResult process(const std::vector<YuvFrame>& frames);

private:
    PipelineConfig m_config;
    bool m_sharpenEnabled = true;
    float m_sharpenStrength = 0.15f;
    float m_sharpenRadius = 2.0f;
    std::mutex m_mutex;
};

} // namespace camera_engine