/**
 * Portrait bokeh processing interface.
 *
 * This header declares a face-aware background blur processor for applying a
 * simple portrait effect on BGR camera frames.
 */
#pragma once
#include "../core/opencv2/core.hpp"

namespace camera_engine {

/**
 * Applies a radial background blur around a normalized focus center.
 */
class BokehEffect {
public:
    /** Builds a smooth radial blur mask and composites blurred background pixels. */
    static cv::Mat apply(const cv::Mat& bgr, float centerX, float centerY,
                         float focusRadius, float blurStrength);
private:
    static constexpr float SMOOTHSTEP_EDGE0 = 0.6f;
    static constexpr float SMOOTHSTEP_EDGE1 = 1.2f;
    static constexpr float ASPECT_RATIO = 1.2f;
};

} // namespace camera_engine