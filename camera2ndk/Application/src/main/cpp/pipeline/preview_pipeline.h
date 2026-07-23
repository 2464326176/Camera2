/**
 * Preview processing pipeline interface.
 *
 * This header declares the low-latency pipeline used for real-time camera
 * preview frames and lightweight visual enhancement.
 */
#pragma once
#include "pipeline_base.h"
#include "../core/frame.h"
#include "../algorithms/face_detect.h"
#include <memory>
#include <mutex>
#include <chrono>

namespace camera_engine {

/**
 * Low-latency processing pipeline used for live camera preview frames.
 */
class PreviewPipeline : public PipelineBase {
public:
    PreviewPipeline() = default;

    /** Applies preview pipeline configuration such as algorithm toggles. */
    ResultCode configure(const PipelineConfig& config) override;
    /** Enables or disables a preview-stage algorithm. */
    void enableAlgorithm(AlgorithmId id, bool enable) override;
    /** Updates a preview algorithm parameter at runtime. */
    void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) override;

    /** Initializes the face detector used by preview metadata and effects. */
    bool initFaceDetector(const std::string& modelPath);
    /** Releases face detection resources when preview processing stops. */
    void releaseFaceDetector();

    /** Processes one preview frame and returns face metadata plus preview image. */
    PreviewResult process(const YuvFrame& frame);

private:
    PipelineConfig m_config;
    FaceDetector m_faceDetector;
    std::mutex m_mutex;
    std::chrono::steady_clock::time_point m_lastDetectTime;
};

} // namespace camera_engine