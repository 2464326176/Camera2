#pragma once
#include "pipeline_base.h"
#include "../core/frame.h"
#include "../algorithms/face_detect.h"
#include <memory>
#include <mutex>
#include <chrono>

namespace camera_engine {

class PreviewPipeline : public PipelineBase {
public:
    PreviewPipeline() = default;

    ResultCode configure(const PipelineConfig& config) override;
    void enableAlgorithm(AlgorithmId id, bool enable) override;
    void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) override;

    bool initFaceDetector(const std::string& modelPath);
    void releaseFaceDetector();

    PreviewResult process(const YuvFrame& frame);

private:
    PipelineConfig m_config;
    FaceDetector m_faceDetector;
    std::mutex m_mutex;
    std::chrono::steady_clock::time_point m_lastDetectTime;
};

} // namespace camera_engine