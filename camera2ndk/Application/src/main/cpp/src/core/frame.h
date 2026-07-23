/**
 * Camera frame container abstraction.
 *
 * This header defines the Frame object used to carry image buffers, dimensions,
 * pixel format, and capture metadata through the native processing pipeline.
 */
#pragma once
#include "src/platform/android/android_hardware_buffer.h"
#include "metadata.h"
#include "types.h"
#include "opencv2/core.hpp"
#include "opencv2/imgproc.hpp"
#include <memory>

namespace camera_engine {

/**
 * Lightweight YUV frame object passed between native camera modules.
 */
class YuvFrame {
public:
    YuvFrame() = default;

    /**
     * Wraps a locked HardwareBuffer without copying the underlying image data.
     */
    YuvFrame(std::shared_ptr<HardwareBufferRef> hwBuf,
             const FrameMetadata& meta,
             YuvFormat fmt = YuvFormat::NV21);

    /**
     * Borrows external YUV plane memory without taking ownership.
     * The caller must keep the memory alive while this frame is processed.
     */
    YuvFrame(int width,
             int height,
             YuvFormat fmt,
             const FrameMetadata& meta,
             YuvPlane yPlane,
             YuvPlane uPlane,
             YuvPlane vPlane);

    bool isValid() const;

    // Get Y plane row pointer
    const uint8_t* getYRow(int row) const;
    // Get UV plane row pointer (NV21 interleaved format)
    const uint8_t* getUvRow(int row) const;

    int getWidth() const { return m_width; }
    int getHeight() const { return m_height; }
    YuvFormat getFormat() const { return m_format; }
    const FrameMetadata& getMetadata() const { return m_metadata; }

    // YUV to BGR conversion (called on demand, incurs one copy)
    /**
     * Converts the YUV frame into a full-resolution BGR Mat for OpenCV algorithms.
     */
    cv::Mat toBgr() const;

    // YUV to downscaled BGR (for face detection, saves memory)
    /**
     * Converts the frame to BGR and downscales it so the longest side is maxSide.
     */
    cv::Mat toBgrDownscaled(int maxSide) const;

    const HardwareBufferRef* getBuffer() const { return m_buffer.get(); }

private:
    std::shared_ptr<HardwareBufferRef> m_buffer;
    FrameMetadata m_metadata;
    YuvFormat m_format = YuvFormat::NV21;
    YuvPlane m_yPlane{};
    YuvPlane m_uPlane{};
    YuvPlane m_vPlane{};
    int m_width = 0;
    int m_height = 0;
};

} // namespace camera_engine