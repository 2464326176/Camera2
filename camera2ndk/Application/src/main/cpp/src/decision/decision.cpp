#include "src/decision/decision.h"

#include <algorithm>

namespace camera_engine {

void PreviewDecision::configure(int intervalMs) {
    m_intervalMs = std::max(0, intervalMs);
}

DecisionPlan PreviewDecision::evaluate(
        const SessionControl& control,
        const FrameMetadata& /* metadata */) {
    DecisionPlan plan;

    const bool shouldRun =
        control.mode == CameraMode::PHOTO && control.preferFaceDetect;
    if (!shouldRun) {
        plan.skipEntireFrame = true;
        plan.reasonFlags = control.mode == CameraMode::PHOTO
            ? DECISION_REASON_USER_DISABLED
            : DECISION_REASON_WRONG_MODE;
        // Only push uninit when face detector was previously initialized
        // to avoid repeated uninit calls on every frame
        if (m_faceInitialized) {
            plan.needUninit.push_back(AlgorithmId::FACE_DETECT);
            m_faceInitialized = false;
        }
        return plan;
    }

    const auto now = std::chrono::steady_clock::now();
    if (m_lastProcessTime.time_since_epoch().count() != 0) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - m_lastProcessTime).count();
        if (elapsed < m_intervalMs) {
            plan.skipEntireFrame = true;
            plan.reasonFlags = DECISION_REASON_THROTTLED;
            return plan;
        }
    }
    m_lastProcessTime = now;

    plan.needInit.push_back(AlgorithmId::FACE_DETECT);
    m_faceInitialized = true;
    plan.stages.push_back({AlgorithmId::FACE_DETECT, {}});
    return plan;
}

void PreviewDecision::reset() {
    m_faceInitialized = false;
    m_lastProcessTime = {};
}

CaptureAdvice CaptureDecision::advise(
        const SessionControl& /* control */,
        const FrameMetadata& metadata) const {
    CaptureAdvice advice;
    const int iso = std::max(100, metadata.iso);
    if (iso < 200) advice.burstFrameCount = 1;
    else if (iso < 400) advice.burstFrameCount = 3;
    else if (iso < 800) advice.burstFrameCount = 4;
    else if (iso < 1600) advice.burstFrameCount = 5;
    else advice.burstFrameCount = 6;
    if (advice.burstFrameCount > 1) {
        advice.reasonFlags |= DECISION_REASON_HIGH_ISO;
    }
    return advice;
}

DecisionPlan CaptureDecision::evaluate(
        const SessionControl& control,
        const std::vector<FrameMetadata>& metadata) const {
    DecisionPlan plan;
    if (metadata.empty()) {
        plan.skipEntireFrame = true;
        return plan;
    }

    const int iso = std::max(100, metadata.front().iso);

    // Table-driven algorithm selection: each entry binds a preference flag to
    // its strength calculator and optional availability predicate. Adding a
    // capture-stage algorithm is a one-line registration here; the loop below
    // preserves the original DENOISE -> HDR -> CLAHE -> SHARPEN -> SATURATION
    // -> BOKEH ordering so processing output stays identical.
    struct AlgoPref {
        AlgorithmId id;
        bool SessionControl::* enabled;
        bool (*available)(const std::vector<FrameMetadata>&, int);
        AlgorithmParamId param;
        float (*strength)(int);
    };
    static const AlgoPref kPrefs[] = {
        {AlgorithmId::DENOISE, &SessionControl::preferDenoise, nullptr,
            PARAM_DENOISE_STRENGTH,
            [](int v) { return v >= 1600 ? 1.0f : (v >= 800 ? 0.8f : 0.5f); }},
        {AlgorithmId::HDR, &SessionControl::preferHdr,
            [](const std::vector<FrameMetadata>& m, int) { return m.size() >= 2; },
            PARAM_HDR_STRENGTH, [](int) { return 0.7f; }},
        {AlgorithmId::CLAHE, &SessionControl::preferClahe, nullptr,
            PARAM_CLAHE_CLIP_LIMIT, [](int) { return 2.0f; }},
        {AlgorithmId::SHARPEN, &SessionControl::preferSharpen, nullptr,
            PARAM_SHARPEN_STRENGTH,
            [](int v) { return v >= 1600 ? 0.1f : 0.15f; }},
        {AlgorithmId::SATURATION, &SessionControl::preferSaturation, nullptr,
            PARAM_SATURATION_FACTOR, [](int) { return 1.05f; }},
        {AlgorithmId::BOKEH, &SessionControl::preferBokeh, nullptr,
            PARAM_BOKEH_STRENGTH, [](int) { return 0.5f; }},
    };

    for (const AlgoPref& pref : kPrefs) {
        if (!(control.*pref.enabled)) continue;
        if (pref.available != nullptr && !pref.available(metadata, iso)) continue;
        AlgorithmStage stage{pref.id, {}};
        stage.params[pref.param] = pref.strength(iso);
        plan.needInit.push_back(pref.id);
        plan.stages.push_back(std::move(stage));
    }

    if (iso >= 800) plan.reasonFlags |= DECISION_REASON_HIGH_ISO;
    if (metadata.size() > 1) plan.reasonFlags |= DECISION_REASON_MULTI_FRAME;
    return plan;
}

DecisionPlan VideoDecision::evaluate(
        const SessionControl& control,
        const FrameMetadata& /* metadata */) const {
    DecisionPlan plan;
    // TODO: Implement video enhancement pipeline by connecting video frames
    // from CameraEngine (currently using MediaRecorder directly) to the C++
    // algorithm pipeline. Steps needed:
    //   1. Create VideoPipeline class (similar to PreviewPipeline)
    //   2. Route video frames from CameraEngine to VideoPipeline via ImageReader
    //   3. Add real decision logic here (e.g., denoise at high ISO, CLAHE)
    //   4. The Java/JNI ABI is already designed to support this extension
    //      without breaking changes.
    plan.skipEntireFrame = true;
    if (control.mode != CameraMode::VIDEO) {
        plan.reasonFlags = DECISION_REASON_WRONG_MODE;
    }
    return plan;
}

} // namespace camera_engine
