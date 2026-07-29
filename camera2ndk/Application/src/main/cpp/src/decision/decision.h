#pragma once

#include "src/core/metadata.h"
#include "src/core/types.h"

#include <chrono>
#include <vector>

namespace camera_engine {

enum DecisionReason : uint32_t {
    DECISION_REASON_NONE = 0,
    DECISION_REASON_USER_DISABLED = 1u << 0,
    DECISION_REASON_WRONG_MODE = 1u << 1,
    DECISION_REASON_THROTTLED = 1u << 2,
    DECISION_REASON_HIGH_ISO = 1u << 3,
    DECISION_REASON_MULTI_FRAME = 1u << 4
};

enum AlgorithmParamId : uint32_t {
    PARAM_DENOISE_STRENGTH = 1001,
    PARAM_SHARPEN_STRENGTH = 2001,
    PARAM_HDR_STRENGTH = 3001,
    PARAM_CLAHE_CLIP_LIMIT = 4001,
    PARAM_SATURATION_FACTOR = 5001,
    PARAM_BOKEH_STRENGTH = 6001
};

struct CaptureAdvice {
    int burstFrameCount = 1;
    uint32_t reasonFlags = DECISION_REASON_NONE;
};

class PreviewDecision {
public:
    void configure(int intervalMs);
    DecisionPlan evaluate(const SessionControl& control, const FrameMetadata& metadata);
    void reset();

private:
    int m_intervalMs = 180;
    bool m_faceInitialized = false;
    std::chrono::steady_clock::time_point m_lastProcessTime{};
};

class CaptureDecision {
public:
    CaptureAdvice advise(const SessionControl& control, const FrameMetadata& metadata) const;
    DecisionPlan evaluate(
        const SessionControl& control,
        const std::vector<FrameMetadata>& metadata) const;
};

class VideoDecision {
public:
    DecisionPlan evaluate(
        const SessionControl& control,
        const FrameMetadata& metadata) const;
};

} // namespace camera_engine
