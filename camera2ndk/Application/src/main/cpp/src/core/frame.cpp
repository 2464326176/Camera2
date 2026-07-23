/**
 * Camera frame container implementation.
 *
 * This file owns frame buffer allocation, wrapping of existing image memory,
 * metadata updates, and OpenCV Mat view conversion for native algorithms.
 */
#include "src/core/frame.h"
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
        m_yPlane = m_buffer->yPlane;
        m_uPlane = m_buffer->uPlane;
        m_vPlane = m_buffer->vPlane;
    }
}

YuvFrame::YuvFrame(int width,
                   int height,
                   YuvFormat fmt,
                   const FrameMetadata& meta,
                   YuvPlane yPlane,
                   YuvPlane uPlane,
                   YuvPlane vPlane)
    : m_metadata(meta)
    , m_format(fmt)
    , m_yPlane(yPlane)
    , m_uPlane(uPlane)
    , m_vPlane(vPlane)
    , m_width(width)
    , m_height(height) {
}

bool YuvFrame::isValid() const {
    return m_width > 0 && m_height > 0 && m_yPlane.data != nullptr;
}

const uint8_t* YuvFrame::getYRow(int row) const {
    if (row < 0 || row >= m_height || m_yPlane.data == nullptr) return nullptr;
    return m_yPlane.data + row * m_yPlane.rowStride;
}

const uint8_t* YuvFrame::getUvRow(int row) const {
    if (row < 0 || row >= m_height / 2 || m_uPlane.data == nullptr) return nullptr;
    return m_uPlane.data + row * m_uPlane.rowStride;
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
    // Copy UV data into compact NV21 layout.
    if (m_format == YuvFormat::NV21 && m_uPlane.data != nullptr && m_uPlane.pixelStride == 2) {
        for (int r = 0; r < m_height / 2; r++) {
            memcpy(nv21.data() + ySize + r * m_width, m_uPlane.data + r * m_uPlane.rowStride, m_width);
        }
    } else if (m_uPlane.data != nullptr && m_vPlane.data != nullptr) {
        for (int r = 0; r < m_height / 2; r++) {
            const uint8_t* uRow = m_uPlane.data + r * m_uPlane.rowStride;
            const uint8_t* vRow = m_vPlane.data + r * m_vPlane.rowStride;
            uint8_t* dst = nv21.data() + ySize + r * m_width;
            for (int c = 0; c < m_width / 2; c++) {
                dst[c * 2] = vRow[c * m_vPlane.pixelStride];
                dst[c * 2 + 1] = uRow[c * m_uPlane.pixelStride];
            }
        }
    } else {
        return cv::Mat();
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