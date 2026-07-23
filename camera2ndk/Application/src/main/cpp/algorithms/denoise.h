/**
 * Image denoising interfaces.
 *
 * This header declares multiple denoising strategies and tunable parameters for
 * reducing sensor noise in preview and still capture processing paths.
 */
#pragma once
#include "../core/opencv2/core.hpp"
#include <vector>

namespace camera_engine {

/**
 * Collection of denoising routines tuned for different capture conditions.
 */
class Denoiser {
public:
    // Single-frame denoise: BGR input -> BGR output
    /**
     * Denoises one BGR frame using an ISO-dependent algorithm selection.
     */
    static cv::Mat denoiseSingle(const cv::Mat& bgr, int iso);

    // Multi-frame denoise: BGR frames -> ECC alignment + weighted average + NLM refinement -> BGR output
    /**
     * Aligns and merges multiple BGR frames to reduce temporal noise.
     */
    static cv::Mat denoiseMulti(const std::vector<cv::Mat>& bgrFrames, int iso);

private:
    /**
     * Maps sensor ISO to a coarse denoising strength level.
     */
    static int chooseDenoiseStrength(int iso);
    static void bgrToNv21(const cv::Mat& bgr, unsigned char* out, int width, int height);

    // Denoise constants
    static constexpr int ISO_L1 = 200, ISO_L2 = 400, ISO_L3 = 800, ISO_L4 = 1600;
    static constexpr float BILATERAL_D = 5.0f;
    static constexpr float BILATERAL_SIGMA_L1 = 30.0f, BILATERAL_SIGMA_L2 = 50.0f;
    static constexpr float NLM_H_L3 = 5.0f, NLM_H_L4 = 8.0f, NLM_H_L5 = 10.0f;
    static constexpr int NLM_TEMPLATE = 7, NLM_SEARCH = 21;
    static constexpr float SHARPEN_STRENGTH = 1.25f, SHARPEN_BLEND = -0.25f, SHARPEN_SIGMA = 1.0;
    static constexpr double ECC_SCALE = 0.25;
    static constexpr double ECC_CC_THRESHOLD = 0.1;
    static constexpr int ECC_MAX_ITER = 40;
    static constexpr double ECC_EPSILON = 1e-3;
    static constexpr float ECC_SCALE_BACK = 4.0f;
};

} // namespace camera_engine