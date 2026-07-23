/**
 * Android HardwareBuffer wrapper implementation.
 *
 * This file manages native hardware buffer lifetime and safe CPU-side locking
 * for image processing paths that consume Android camera buffers.
 */
#include "hardware_buffer.h"
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "HardwareBuffer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

// Runtime API level check
static inline bool isApi26OrAbove() {
    static int apiLevel = -1;
    if (apiLevel < 0) {
        apiLevel = android_get_device_api_level();
    }
    return apiLevel >= 26;
}

HardwareBufferRef::~HardwareBufferRef() {
    unlock();
}

/**
 * Locks an Android HardwareBuffer and records width, height, stride, and plane pointers.
 */
bool HardwareBufferRef::lock(AHardwareBuffer* buffer, uint64_t usage) {
    if (!isApi26OrAbove()) {
        LOGE("AHardwareBuffer not available on API < 26");
        return false;
    }
    if (m_locked) unlock();
    if (!buffer) return false;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    int ret = AHardwareBuffer_lock(buffer, usage, -1, nullptr, &m_data);
    if (ret != 0) {
        LOGE("AHardwareBuffer_lock failed: %d", ret);
        return false;
    }

    m_buffer = buffer;
    m_locked = true;
    width = (int)desc.width;
    height = (int)desc.height;
    format = (int)desc.format;

    // YUV_420_888 plane layout (NV21 compatible)
    // AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420 = 0x23 (commonly used for YUV_420_888)
    if (desc.format == AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420) {
        yPlane.data = static_cast<uint8_t*>(m_data);
        yPlane.rowStride = (int)desc.width;
        yPlane.pixelStride = 1;

        // UV interleaved plane: stride aligned to width
        int uvStride = desc.width;
        uPlane.data = static_cast<uint8_t*>(m_data) + desc.width * desc.height;
        uPlane.rowStride = uvStride;
        uPlane.pixelStride = 2;

        vPlane.data = uPlane.data + 1;
        vPlane.rowStride = uvStride;
        vPlane.pixelStride = 2;
    }

    LOGD("Locked buffer %dx%d fmt=%d", width, height, format);
    return true;
}

/**
 * Unlocks the HardwareBuffer and clears cached CPU-side plane references.
 */
void HardwareBufferRef::unlock() {
    if (m_locked && m_buffer) {
        if (isApi26OrAbove()) {
            AHardwareBuffer_unlock(m_buffer, nullptr);
        }
        m_buffer = nullptr;
        m_data = nullptr;
        m_locked = false;
        memset(&yPlane, 0, sizeof(yPlane));
        memset(&uPlane, 0, sizeof(uPlane));
        memset(&vPlane, 0, sizeof(vPlane));
    }
}

} // namespace camera_engine