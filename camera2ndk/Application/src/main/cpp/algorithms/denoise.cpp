#include "denoise.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/video.hpp>
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "Denoiser"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

int Denoiser::chooseDenoiseStrength(int iso) {
    if (iso < ISO_L1) return 1;
    if (iso < ISO_L2) return 2;
    if (iso < ISO_L3) return 3;
    if (iso < ISO_L4) return 4;
    return 5;
}

void Denoiser::bgrToNv21(const cv::Mat& bgr, unsigned char* out, int width, int height) {
    int alignedWidth = (width + 1) & ~1;
    int alignedHeight = (height + 1) & ~1;
    cv::Mat bgrAligned;
    if (alignedWidth != width || alignedHeight != height) {
        cv::resize(bgr, bgrAligned, cv::Size(alignedWidth, alignedHeight));
    } else {
        bgrAligned = bgr;
    }

    cv::Mat yuv;
    cv::cvtColor(bgrAligned, yuv, cv::COLOR_BGR2YUV_I420);

    const int ySize = alignedWidth * alignedHeight;
    memcpy(out, yuv.data, ySize);

    const unsigned char* uPlane = yuv.data + ySize;
    const unsigned char* vPlane = uPlane + ySize / 4;
    unsigned char* vu = out + ySize;
    const int uvCount = ySize / 4;
    for (int i = 0; i < uvCount; i++) {
        vu[i * 2] = vPlane[i];
        vu[i * 2 + 1] = uPlane[i];
    }
}

cv::Mat Denoiser::denoiseSingle(const cv::Mat& bgr, int iso) {
    if (bgr.empty()) return cv::Mat();

    int level = chooseDenoiseStrength(iso);
    LOGD("Single-frame denoise level=%d iso=%d", level, iso);

    cv::Mat denoised;
    try {
        if (level <= 1) {
            cv::bilateralFilter(bgr, denoised, (int)BILATERAL_D, BILATERAL_SIGMA_L1, BILATERAL_SIGMA_L1);
        } else if (level == 2) {
            cv::bilateralFilter(bgr, denoised, 7, BILATERAL_SIGMA_L2, BILATERAL_SIGMA_L2);
        } else if (level == 3) {
            cv::fastNlMeansDenoisingColored(bgr, denoised, NLM_H_L3, NLM_H_L3, NLM_TEMPLATE, NLM_SEARCH);
        } else if (level == 4) {
            cv::fastNlMeansDenoisingColored(bgr, denoised, NLM_H_L4, NLM_H_L4, NLM_TEMPLATE, NLM_SEARCH);
        } else {
            cv::fastNlMeansDenoisingColored(bgr, denoised, NLM_H_L5, NLM_H_L5, NLM_TEMPLATE, NLM_SEARCH);
        }

        cv::Mat blurred;
        cv::GaussianBlur(denoised, blurred, cv::Size(0, 0), SHARPEN_SIGMA);
        cv::addWeighted(denoised, SHARPEN_STRENGTH, blurred, SHARPEN_BLEND, 0, denoised);
    } catch (const cv::Exception& e) {
        LOGE("Denoise single failed: %s", e.what());
        denoised = bgr.clone();
    }
    return denoised;
}

cv::Mat Denoiser::denoiseMulti(const std::vector<cv::Mat>& bgrFrames, int iso) {
    if (bgrFrames.empty()) return cv::Mat();
    if (bgrFrames.size() == 1) return denoiseSingle(bgrFrames[0], iso);

    int frameCount = (int)bgrFrames.size();
    const cv::Mat& ref = bgrFrames[0];
    int w = ref.cols, h = ref.rows;

    // ECC alignment on scaled-down frames
    cv::Mat refSmall, refGray;
    cv::resize(ref, refSmall, cv::Size(), ECC_SCALE, ECC_SCALE, cv::INTER_AREA);
    cv::cvtColor(refSmall, refGray, cv::COLOR_BGR2GRAY);

    cv::Mat accumulator(h, w, CV_32FC3, cv::Scalar(0, 0, 0));
    int used = 0;

    for (int i = 0; i < frameCount; i++) {
        cv::Mat curSmall, curGray;
        cv::resize(bgrFrames[i], curSmall, cv::Size(), ECC_SCALE, ECC_SCALE, cv::INTER_AREA);
        cv::cvtColor(curSmall, curGray, cv::COLOR_BGR2GRAY);

        cv::Mat warp = cv::Mat::eye(2, 3, CV_32FC1);
        try {
            double cc = cv::findTransformECC(refGray, curGray, warp,
                cv::MOTION_EUCLIDEAN,
                cv::TermCriteria(cv::TermCriteria::COUNT + cv::TermCriteria::EPS,
                    ECC_MAX_ITER, ECC_EPSILON));
            if (cc < ECC_CC_THRESHOLD) {
                LOGD("Frame %d ECC cc=%.3f low, skip", i, cc);
                continue;
            }
            warp.at<float>(0, 2) *= ECC_SCALE_BACK;
            warp.at<float>(1, 2) *= ECC_SCALE_BACK;

            cv::Mat aligned;
            cv::warpAffine(bgrFrames[i], aligned, warp, cv::Size(w, h),
                cv::INTER_LINEAR, cv::BORDER_REPLICATE);
            cv::accumulate(aligned, accumulator);
            used++;
        } catch (const cv::Exception& e) {
            LOGD("Frame %d ECC failed: %s", i, e.what());
        }
    }

    if (used < 2) {
        LOGW("Effective frames < 2, fallback to single-frame denoise");
        return denoiseSingle(bgrFrames[0], iso);
    }

    cv::Mat averaged;
    accumulator.convertTo(averaged, CV_8UC3, 1.0 / used);

    // NLM refinement
    int level = chooseDenoiseStrength(iso);
    cv::Mat denoised;
    try {
        if (level >= 3) {
            if (w > 1600) {
                cv::Mat small;
                cv::resize(averaged, small, cv::Size(), 0.5, 0.5, cv::INTER_AREA);
                cv::fastNlMeansDenoisingColored(small, small, NLM_H_L3, NLM_H_L3, NLM_TEMPLATE, NLM_SEARCH);
                cv::resize(small, denoised, cv::Size(w, h), 0, 0, cv::INTER_LINEAR);
                cv::addWeighted(averaged, 0.3, denoised, 0.7, 0, denoised);
            } else {
                cv::fastNlMeansDenoisingColored(averaged, denoised, NLM_H_L3, NLM_H_L3, NLM_TEMPLATE, NLM_SEARCH);
            }
        } else {
            denoised = averaged;
        }

        cv::Mat blurred;
        cv::GaussianBlur(denoised, blurred, cv::Size(0, 0), SHARPEN_SIGMA);
        cv::addWeighted(denoised, 1.2, blurred, -0.2, 0, denoised);
    } catch (const cv::Exception& e) {
        LOGE("Multi denoise failed: %s", e.what());
        denoised = averaged;
    }
    return denoised;
}

} // namespace camera_engine