/**
 * Android HardwareBuffer wrapper.
 *
 * This header provides RAII-style access to AHardwareBuffer instances and hides
 * lock, unlock, and plane layout details from pipeline code.
 */
#pragma once
#include <android/hardware_buffer.h>
#include <cstdint>

namespace camera_engine {

/**
 * Describes one CPU-accessible plane inside a locked YUV HardwareBuffer.
 */
struct YuvPlane {
    uint8_t* data = nullptr;
    int32_t rowStride = 0;
    int32_t pixelStride = 0;
};

/**
 * Owns temporary CPU access to an Android HardwareBuffer.
 */
class HardwareBufferRef {
public:
    HardwareBufferRef() = default;
    ~HardwareBufferRef();

    // Non-copyable
    HardwareBufferRef(const HardwareBufferRef&) = delete;
    HardwareBufferRef& operator=(const HardwareBufferRef&) = delete;

    /**
     * Locks the HardwareBuffer for CPU access and caches plane layout metadata.
     */
    bool lock(AHardwareBuffer* buffer, uint64_t usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN);
    /**
     * Releases CPU access to the HardwareBuffer if it is currently locked.
     */
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