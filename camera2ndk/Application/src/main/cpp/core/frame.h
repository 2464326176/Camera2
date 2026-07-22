#pragma once
#include "hardware_buffer.h"
#include "metadata.h"
#include "types.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <memory>

namespace camera_engine {

class YuvFrame {
public:
    YuvFrame() = default;

    // Construct from HardwareBuffer (zero-copy)
    YuvFrame(std::shared_ptr<HardwareBufferRef> hwBuf,
             const FrameMetadata& meta,
             YuvFormat fmt = YuvFormat::NV21);

    bool isValid() const { return m_buffer != nullptr && m_buffer->isLocked(); }

    // Get Y plane row pointer
    const uint8_t* getYRow(int row) const;
    // Get UV plane row pointer (NV21 interleaved format)
    const uint8_t* getUvRow(int row) const;

    int getWidth() const { return m_width; }
    int getHeight() const { return m_height; }
    YuvFormat getFormat() const { return m_format; }
    const FrameMetadata& getMetadata() const { return m_metadata; }

    // YUV to BGR conversion (called on demand, incurs one copy)
    cv::Mat toBgr() const;

    // YUV to downscaled BGR (for face detection, saves memory)
    cv::Mat toBgrDownscaled(int maxSide) const;

    const HardwareBufferRef* getBuffer() const { return m_buffer.get(); }

private:
    std::shared_ptr<HardwareBufferRef> m_buffer;
    FrameMetadata m_metadata;
    YuvFormat m_format = YuvFormat::NV21;
    int m_width = 0;
    int m_height = 0;
};

} // namespace camera_engine