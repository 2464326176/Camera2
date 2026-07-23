/**
 * Native pipeline base interface.
 *
 * This header defines the common contract implemented by preview and capture
 * processing pipelines in the camera engine.
 */
#pragma once
#include "../core/types.h"
#include <string>

namespace camera_engine {

/**
 * Common lifecycle and configuration contract for native processing pipelines.
 */
class PipelineBase {
public:
    virtual ~PipelineBase() = default;
    /**
     * Applies runtime pipeline configuration supplied by the Java layer.
     */
    virtual ResultCode configure(const PipelineConfig& config) = 0;
    /**
     * Enables or disables a processing algorithm at runtime.
     */
    virtual void enableAlgorithm(AlgorithmId id, bool enable) = 0;
    /**
     * Updates a numeric parameter for an enabled algorithm.
     */
    virtual void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) = 0;
};

} // namespace camera_engine