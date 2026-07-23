#pragma once

#include <stdint.h>

#define CAMERA_ENGINE_API_VERSION_MAJOR 1
#define CAMERA_ENGINE_API_VERSION_MINOR 0
#define CAMERA_ENGINE_API_VERSION_PATCH 0
#define CAMERA_ENGINE_ABI_VERSION 1

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CameraEngineVersion {
    uint32_t struct_size;
    uint32_t api_major;
    uint32_t api_minor;
    uint32_t api_patch;
    uint32_t abi_version;
    const char* build_commit;
    const char* build_time;
    uint32_t reserved[8];
} CameraEngineVersion;

/**
 * Returns the current API and ABI version of the native camera engine.
 */
void camera_engine_get_version(CameraEngineVersion* out_version);

#ifdef __cplusplus
}
#endif
