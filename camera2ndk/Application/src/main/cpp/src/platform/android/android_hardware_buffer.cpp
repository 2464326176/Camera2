/**
 * Android HardwareBuffer wrapper implementation.
 *
 * This file manages native hardware buffer lifetime and safe CPU-side locking
 * for image processing paths that consume Android camera buffers.
 */
#include "android_hardware_buffer.h"
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "HardwareBuffer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace camera_engine {

// Runtime API level check. Uses a magic static so the device API level is
// read exactly once in a thread-safe manner.
static inline bool isApi26OrAbove() {
    static const int apiLevel = android_get_device_api_level();
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

    int ret = 0;
    AHardwareBuffer_Planes planes{};
    const bool canLockPlanes =
        android_get_device_api_level() >= 29 &&
        desc.format == AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420;
    if (canLockPlanes) {
        ret = AHardwareBuffer_lockPlanes(
            buffer, usage, -1, nullptr, &planes);
    } else {
        ret = AHardwareBuffer_lock(buffer, usage, -1, nullptr, &m_data);
    }
    if (ret != 0) {
        LOGE("AHardwareBuffer_lock failed: %d", ret);
        return false;
    }

    m_buffer = buffer;
    m_locked = true;
    width = (int)desc.width;
    height = (int)desc.height;
    format = (int)desc.format;

    // API 29+ exposes the real YUV plane row/pixel strides.
    if (canLockPlanes && planes.planeCount >= 3) {
        yPlane.data = static_cast<uint8_t*>(planes.planes[0].data);
        yPlane.rowStride = static_cast<int32_t>(planes.planes[0].rowStride);
        yPlane.pixelStride = static_cast<int32_t>(planes.planes[0].pixelStride);
        uPlane.data = static_cast<uint8_t*>(planes.planes[1].data);
        uPlane.rowStride = static_cast<int32_t>(planes.planes[1].rowStride);
        uPlane.pixelStride = static_cast<int32_t>(planes.planes[1].pixelStride);
        vPlane.data = static_cast<uint8_t*>(planes.planes[2].data);
        vPlane.rowStride = static_cast<int32_t>(planes.planes[2].rowStride);
        vPlane.pixelStride = static_cast<int32_t>(planes.planes[2].pixelStride);
    } else if (desc.format == AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420) {
        // API 26-28 does not expose lockPlanes. Respect the allocation stride
        // for Y and keep the legacy semiplanar fallback for compatibility.
        yPlane.data = static_cast<uint8_t*>(m_data);
        yPlane.rowStride = static_cast<int>(desc.stride);
        yPlane.pixelStride = 1;

        const int uvStride = static_cast<int>(desc.stride);
        uPlane.data = static_cast<uint8_t*>(m_data) + desc.stride * desc.height;
        uPlane.rowStride = uvStride;
        uPlane.pixelStride = 2;

        vPlane.data = uPlane.data + 1;
        vPlane.rowStride = uvStride;
        vPlane.pixelStride = 2;
    } else {
        // Neither the API 29+ plane API nor the semiplanar YUV fallback applies
        // (e.g. an RGBA_8888 buffer). YuvFrame::isValid() would fail anyway, but
        // we surface the problem explicitly instead of leaving null plane pointers.
        LOGE("Unsupported HardwareBuffer format %d; cannot map YUV planes", format);
        unlock();
        return false;
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