#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum CameraEngineStatus {
    CAMERA_ENGINE_OK = 0,
    CAMERA_ENGINE_ERROR_UNKNOWN = -1,
    CAMERA_ENGINE_ERROR_INVALID_ARGUMENT = -2,
    CAMERA_ENGINE_ERROR_INVALID_STATE = -3,
    CAMERA_ENGINE_ERROR_NOT_INITIALIZED = -4,
    CAMERA_ENGINE_ERROR_OUT_OF_MEMORY = -5,
    CAMERA_ENGINE_ERROR_BUFFER_TOO_SMALL = -6,
    CAMERA_ENGINE_ERROR_UNSUPPORTED_FORMAT = -7,
    CAMERA_ENGINE_ERROR_UNSUPPORTED_OPERATION = -8,
    CAMERA_ENGINE_ERROR_MODEL_LOAD_FAILED = -9,
    CAMERA_ENGINE_ERROR_PROCESS_FAILED = -10,
    CAMERA_ENGINE_ERROR_ANDROID_BUFFER_LOCK_FAILED = -11
} CameraEngineStatus;

/**
 * Returns a stable English message for a status code.
 * The returned pointer is owned by the library and must not be freed.
 */
const char* camera_engine_status_message(CameraEngineStatus status);

#ifdef __cplusplus
}
#endif
