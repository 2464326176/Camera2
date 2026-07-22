#include "capture_pipeline.h"
#include <android/log.h>

#define LOG_TAG "CapturePipeline"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

ResultCode CapturePipeline::configure(const PipelineConfig& config) {
    m_config = config;
    return ResultCode::OK;
}

void CapturePipeline::enableAlgorithm(AlgorithmId id, bool enable) {
    if (id == AlgorithmId::SHARPEN) {
        m_sharpenEnabled = enable;
    }
}

void CapturePipeline::setAlgorithmParam(AlgorithmId id, const std::string& key, float value) {
    if (id == AlgorithmId::SHARPEN) {
        if (key == "strength") m_sharpenStrength = value;
        else if (key == "radius") m_sharpenRadius = value;
    }
}

CaptureResult CapturePipeline::process(const std::vector<YuvFrame>& frames) {
    CaptureResult result;
    if (frames.empty()) return result;

    // Extract ISO from first frame metadata
    int iso = frames[0].getMetadata().iso;
    result.iso = iso;
    result.timestampNs = frames[0].getMetadata().timestampNs;

    // YUV to BGR conversion
    std::vector<cv::Mat> bgrFrames;
    bgrFrames.reserve(frames.size());
    for (const auto& frame : frames) {
        cv::Mat bgr = frame.toBgr();
        if (!bgr.empty()) {
            bgrFrames.push_back(bgr);
        }
    }

    if (bgrFrames.empty()) return result;

    // Denoise
    cv::Mat denoised;
    if (bgrFrames.size() >= 2) {
        denoised = Denoiser::denoiseMulti(bgrFrames, iso);
    } else {
        denoised = Denoiser::denoiseSingle(bgrFrames[0], iso);
    }

    if (denoised.empty()) {
        denoised = bgrFrames[0];
    }

    // Sharpen (optional)
    if (m_sharpenEnabled) {
        denoised = Sharpener::sharpen(denoised, m_sharpenStrength, m_sharpenRadius);
    }

    // JPEG encode (BGR direct encode, skip NV21 intermediate step)
    result.jpegData = JpegEncoder::encodeBgr(denoised, m_config.jpegQuality);
    return result;
}

} // namespace camera_engine