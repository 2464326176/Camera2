#pragma once
#include "../core/types.h"
#include <string>

namespace camera_engine {

class PipelineBase {
public:
    virtual ~PipelineBase() = default;
    virtual ResultCode configure(const PipelineConfig& config) = 0;
    virtual void enableAlgorithm(AlgorithmId id, bool enable) = 0;
    virtual void setAlgorithmParam(AlgorithmId id, const std::string& key, float value) = 0;
};

} // namespace camera_engine