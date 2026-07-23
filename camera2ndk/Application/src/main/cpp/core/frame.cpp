/**
 * Camera frame container implementation.
 *
 * This file owns frame buffer allocation, wrapping of existing image memory,
 * metadata updates, and OpenCV Mat view conversion for native algorithms.
 */
#include "frame.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "YuvFrame"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

YuvFrame::YuvFrame(std::shared_ptr<HardwareBufferRef> hwBuf,
                   const FrameMetadata& meta,
                   YuvFormat fmt)
    : m_buffer(std::move(hwBuf))
    , m_metadata(meta)
    , m_format(fmt) {
    if (m_buffer) {
        m_width = m_buffer->width;
        m_height = m_buffer->height;
    }
}

const uint8_t* YuvFrame::getYRow(int row) const {
    if (!m_buffer || row < 0 || row >= m_height) return nullptr;
    return m_buffer->yPlane.data + row * m_buffer->yPlane.rowStride;
}

const uint8_t* YuvFrame::getUvRow(int row) const {
    if (!m_buffer || row < 0 || row >= m_height / 2) return nullptr;
    return m_buffer->uPlane.data + row * m_buffer->uPlane.rowStride;
}

/**
 * Converts NV21-style YUV data into a BGR Mat for OpenCV processing.
 */
cv::Mat YuvFrame::toBgr() const {
    if (!isValid()) return cv::Mat();

    // Compact NV21 to cv::Mat YUV
    int ySize = m_width * m_height;
    int uvSize = ySize / 2;
    std::vector<uint8_t> nv21(ySize + uvSize);

    // Copy Y plane (respect rowStride)
    for (int r = 0; r < m_height; r++) {
        memcpy(nv21.data() + r * m_width, getYRow(r), m_width);
    }
    // Copy UV plane (pixelStride=2, rowStride=width)
    if (m_buffer->uPlane.pixelStride == 2) {
        memcpy(nv21.data() + ySize, m_buffer->uPlane.data, uvSize);
    } else {
        for (int r = 0; r < m_height / 2; r++) {
            memcpy(nv21.data() + ySize + r * m_width, getUvRow(r), m_width);
        }
    }

    cv::Mat yuv(m_height + m_height / 2, m_width, CV_8UC1, nv21.data());
    cv::Mat bgr;
    cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR_NV21);
    return bgr.clone(); // Return independent copy, free nv21 temp buffer
}

/**
 * Produces a BGR preview copy scaled down for lightweight algorithms.
 */
cv::Mat YuvFrame::toBgrDownscaled(int maxSide) const {
    if (!isValid()) return cv::Mat();

    // Calculate scale factor
    float scale = 1.0f;
    if (m_width > m_height && m_width > maxSide) {
        scale = (float)maxSide / m_width;
    } else if (m_height > maxSide) {
        scale = (float)maxSide / m_height;
    }

    if (scale >= 1.0f) {
        return toBgr();
    }

    int smallW = (int)(m_width * scale);
    int smallH = (int)(m_height * scale);

    // Downscale first via NV21 to BGR at smaller size
    int ySize = m_width * m_height;
    int uvSize = ySize / 2;
    std::vector<uint8_t> nv21(ySize + uvSize);

    for (int r = 0; r < m_height; r++) {
        memcpy(nv21.data() + r * m_width, getYRow(r), m_width);
    }
    if (m_buffer->uPlane.pixelStride == 2) {
        memcpy(nv21.data() + ySize, m_buffer->uPlane.data, uvSize);
    } else {
        for (int r = 0; r < m_height / 2; r++) {
            memcpy(nv21.data() + ySize + r * m_width, getUvRow(r), m_width);
        }
    }

    cv::Mat yuv(m_height + m_height / 2, m_width, CV_8UC1, nv21.data());
    cv::Mat bgrSmall;
    cv::cvtColor(yuv, bgrSmall, cv::COLOR_YUV2BGR_NV21);
    cv::resize(bgrSmall, bgrSmall, cv::Size(smallW, smallH), 0, 0, cv::INTER_AREA);
    return bgrSmall;
}

} // namespace camera_engine