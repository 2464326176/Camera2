#pragma once
#include <android/hardware_buffer.h>
#include <cstdint>

namespace camera_engine {

struct YuvPlane {
    uint8_t* data = nullptr;
    int32_t rowStride = 0;
    int32_t pixelStride = 0;
};

class HardwareBufferRef {
public:
    HardwareBufferRef() = default;
    ~HardwareBufferRef();

    // Non-copyable
    HardwareBufferRef(const HardwareBufferRef&) = delete;
    HardwareBufferRef& operator=(const HardwareBufferRef&) = delete;

    bool lock(AHardwareBuffer* buffer, uint64_t usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN);
    void unlock();
    bool isLocked() const { return m_locked; }

    YuvPlane yPlane;
    YuvPlane uPlane;
    YuvPlane vPlane;
    int width = 0;
    int height = 0;
    int format = 0; // AHardwareBuffer_Format

private:
    AHardwareBuffer* m_buffer = nullptr;
    void* m_data = nullptr;
    bool m_locked = false;
};

} // namespace camera_engine