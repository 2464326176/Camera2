#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "camera_engine/camera_engine.h"
#include "camera_engine/camera_engine_android.h"

#define LOG_TAG "CameraEngineJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Android ImageFormat.YUV_420_888 integer value, used when constructing
// HardwareBuffer-backed frames from Java Image objects.
constexpr int32_t kAndroidYuv420Format = 35;

// Shared across threads so the UI thread can query the status produced by the
// camera callback thread. Replacing the previous thread_local with an atomic
// avoids returning a stale CAMERA_ENGINE_NOT_READY on the querying thread.
std::atomic<CameraEngineStatus> g_lastStatus{CAMERA_ENGINE_NOT_READY};

CameraEngineContext* fromHandle(jlong handle) {
    return reinterpret_cast<CameraEngineContext*>(static_cast<intptr_t>(handle));
}

jlong toHandle(CameraEngineContext* context) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
}

CameraEngineFrameMetadata parseMetadata(JNIEnv* env, jobject object) {
    CameraEngineFrameMetadata metadata;
    camera_engine_frame_metadata_init(&metadata);
    if (object == nullptr) {
        metadata.approximate = 1;
        return metadata;
    }

    jclass cls = env->GetObjectClass(object);
    auto longField = [&](const char* name) -> jlong {
        const jfieldID id = env->GetFieldID(cls, name, "J");
        if (id == nullptr) {
            env->ExceptionClear();
            return 0;
        }
        return env->GetLongField(object, id);
    };
    auto intField = [&](const char* name) -> jint {
        const jfieldID id = env->GetFieldID(cls, name, "I");
        if (id == nullptr) {
            env->ExceptionClear();
            return 0;
        }
        return env->GetIntField(object, id);
    };
    auto floatField = [&](const char* name) -> jfloat {
        const jfieldID id = env->GetFieldID(cls, name, "F");
        if (id == nullptr) {
            env->ExceptionClear();
            return 0.0f;
        }
        return env->GetFloatField(object, id);
    };
    auto boolField = [&](const char* name) -> bool {
        const jfieldID id = env->GetFieldID(cls, name, "Z");
        if (id == nullptr) {
            env->ExceptionClear();
            return false;
        }
        return env->GetBooleanField(object, id) == JNI_TRUE;
    };

    metadata.timestamp_ns = longField("timestampNs");
    metadata.iso = intField("iso");
    metadata.exposure_time_ns = longField("exposureTimeNs");
    metadata.flash_state = intField("flashState");
    metadata.aperture = floatField("lensAperture");
    metadata.ae_state = intField("aeState");
    metadata.af_state = intField("afState");
    metadata.awb_state = intField("awbState");
    metadata.focal_length = floatField("focalLength");
    metadata.focus_distance = floatField("focusDistance");
    metadata.rotation = static_cast<CameraEngineRotation>(intField("rotation"));
    metadata.lens_facing = static_cast<CameraEngineLensFacing>(intField("lensFacing"));
    metadata.frame_number = static_cast<uint32_t>(intField("frameNumber"));
    metadata.approximate = boolField("approximate") ? 1 : 0;
    env->DeleteLocalRef(cls);
    return metadata;
}

bool parseControl(
        JNIEnv* env,
        jobject object,
        CameraEngineSessionControl* control) {
    if (object == nullptr || control == nullptr) return false;
    camera_engine_session_control_init(control);
    jclass cls = env->GetObjectClass(object);
    auto intField = [&](const char* name) -> jint {
        const jfieldID id = env->GetFieldID(cls, name, "I");
        if (id == nullptr) {
            env->ExceptionClear();
            return 0;
        }
        return env->GetIntField(object, id);
    };
    auto boolField = [&](const char* name, bool fallback) -> bool {
        const jfieldID id = env->GetFieldID(cls, name, "Z");
        if (id == nullptr) {
            env->ExceptionClear();
            return fallback;
        }
        return env->GetBooleanField(object, id) == JNI_TRUE;
    };
    control->mode = intField("mode") == 1
        ? CAMERA_ENGINE_MODE_VIDEO : CAMERA_ENGINE_MODE_PHOTO;
    control->lens_facing =
        static_cast<CameraEngineLensFacing>(intField("lensFacing"));
    control->flash_mode = intField("flashMode");
    control->prefer_face_detect = boolField("preferFaceDetect", true);
    control->prefer_denoise = boolField("preferDenoise", true);
    control->prefer_sharpen = boolField("preferSharpen", true);
    control->prefer_hdr = boolField("preferHdr", false);
    control->prefer_clahe = boolField("preferClahe", false);
    control->prefer_saturation = boolField("preferSaturation", false);
    control->prefer_bokeh = boolField("preferBokeh", false);
    control->jpeg_quality = static_cast<uint32_t>(
        std::clamp(intField("jpegQuality"), 1, 100));
    control->analysis_max_side = static_cast<uint32_t>(
        std::max(64, intField("analysisMaxSide")));
    env->DeleteLocalRef(cls);
    return true;
}

jfloatArray facesToJava(
        JNIEnv* env,
        const CameraEngineFace* faces,
        uint32_t count) {
    jfloatArray output = env->NewFloatArray(static_cast<jsize>(count * 15));
    if (output == nullptr || count == 0) return output;
    std::vector<float> values(count * 15);
    for (uint32_t i = 0; i < count; ++i) {
        const size_t base = i * 15;
        values[base] = faces[i].rect.left;
        values[base + 1] = faces[i].rect.top;
        values[base + 2] = faces[i].rect.right - faces[i].rect.left;
        values[base + 3] = faces[i].rect.bottom - faces[i].rect.top;
        values[base + 4] = faces[i].score;
        const CameraEnginePoint points[5] = {
            faces[i].left_eye, faces[i].right_eye, faces[i].nose,
            faces[i].mouth_left, faces[i].mouth_right};
        for (int p = 0; p < 5; ++p) {
            values[base + 5 + p * 2] = points[p].x;
            values[base + 6 + p * 2] = points[p].y;
        }
    }
    env->SetFloatArrayRegion(
        output, 0, static_cast<jsize>(values.size()), values.data());
    return output;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreateEngine(
        JNIEnv* env, jobject, jstring modelDir) {
    CameraEngineCreateInfo info;
    camera_engine_create_info_init(&info);
    std::string directory;
    if (modelDir != nullptr) {
        const char* chars = env->GetStringUTFChars(modelDir, nullptr);
        if (chars != nullptr) {
            directory = chars;
            env->ReleaseStringUTFChars(modelDir, chars);
        }
    }
    info.asset_dir = directory.c_str();
    info.cache_dir = directory.c_str();
    CameraEngineContext* context = nullptr;
    g_lastStatus = camera_engine_create(&info, &context);
    return g_lastStatus == CAMERA_ENGINE_OK ? toHandle(context) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeDestroyEngine(
        JNIEnv*, jobject, jlong engineHandle) {
    camera_engine_destroy(fromHandle(engineHandle));
    g_lastStatus = CAMERA_ENGINE_NOT_READY;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreatePreviewPipeline(
        JNIEnv*, jobject, jlong engineHandle,
        jint width, jint height, jint format, jint maxFaces) {
    CameraEnginePreviewConfig config;
    camera_engine_preview_config_init(&config);
    config.width = static_cast<uint32_t>(width);
    config.height = static_cast<uint32_t>(height);
    config.format = format == 0
        ? CAMERA_ENGINE_PIXEL_FORMAT_NV21
        : CAMERA_ENGINE_PIXEL_FORMAT_YUV_420_888;
    config.max_faces = static_cast<uint32_t>(std::max(1, maxFaces));
    g_lastStatus =
        camera_engine_configure_preview(fromHandle(engineHandle), &config);
    return g_lastStatus == CAMERA_ENGINE_OK ? engineHandle : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreateCapturePipeline(
        JNIEnv*, jobject, jlong engineHandle,
        jint width, jint height, jint format) {
    CameraEngineCaptureConfig config;
    camera_engine_capture_config_init(&config);
    config.width = static_cast<uint32_t>(width);
    config.height = static_cast<uint32_t>(height);
    config.format = format == 0
        ? CAMERA_ENGINE_PIXEL_FORMAT_NV21
        : CAMERA_ENGINE_PIXEL_FORMAT_YUV_420_888;
    g_lastStatus =
        camera_engine_configure_capture(fromHandle(engineHandle), &config);
    return g_lastStatus == CAMERA_ENGINE_OK ? engineHandle : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeDestroyPipeline(
        JNIEnv*, jobject, jlong, jlong) {
    // Pipelines are owned by CameraEngineContext and released by destroyEngine.
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeUpdateSessionControl(
        JNIEnv* env, jobject, jlong engineHandle, jobject controlObject) {
    CameraEngineSessionControl control;
    if (!parseControl(env, controlObject, &control)) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return;
    }
    g_lastStatus = camera_engine_update_session_control(
        fromHandle(engineHandle), &control);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opencv_camera_NativeEngine_nativeAdviseCaptureFrameCount(
        JNIEnv* env, jobject, jlong engineHandle, jobject metadataObject) {
    const CameraEngineFrameMetadata metadata =
        parseMetadata(env, metadataObject);
    CameraEngineCaptureAdvice advice;
    camera_engine_capture_advice_init(&advice);
    g_lastStatus = camera_engine_advise_capture(
        fromHandle(engineHandle), &metadata, &advice);
    return g_lastStatus == CAMERA_ENGINE_OK
        ? static_cast<jint>(advice.burst_frame_count) : 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opencv_camera_NativeEngine_nativeGetLastStatus(
        JNIEnv*, jobject) {
    return static_cast<jint>(g_lastStatus.load());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_opencv_camera_NativeEngine_nativeProcessPreviewFrame(
        JNIEnv* env, jobject, jlong pipelineHandle,
        jobject hardwareBuffer, jobject metadataObject) {
    if (hardwareBuffer == nullptr) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return env->NewFloatArray(0);
    }
    CameraEngineAndroidHardwareBufferFrame frame{};
    frame.struct_size = sizeof(frame);
    frame.hardware_buffer =
        AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    frame.metadata = parseMetadata(env, metadataObject);
    frame.android_format = kAndroidYuv420Format;

    CameraEngineFace faces[32]{};
    CameraEnginePreviewResult result;
    camera_engine_preview_result_init(&result);
    result.faces = faces;
    result.face_capacity = 32;
    g_lastStatus = camera_engine_android_process_preview_hardware_buffer(
        fromHandle(pipelineHandle), &frame, &result);
    return facesToJava(env, faces, result.face_count);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_NativeEngine_nativeProcessCapture(
        JNIEnv* env, jobject, jlong pipelineHandle,
        jobjectArray hardwareBuffers, jobjectArray metadataArray,
        jint jpegQuality) {
    if (hardwareBuffers == nullptr || metadataArray == nullptr) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return nullptr;
    }
    const jsize count = env->GetArrayLength(hardwareBuffers);
    if (count <= 0 || env->GetArrayLength(metadataArray) != count) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return nullptr;
    }

    std::vector<CameraEngineAndroidHardwareBufferFrame> frames(
        static_cast<size_t>(count));
    uint64_t maxOutputSize = 0;
    AHardwareBuffer_Desc firstDesc{};
    bool hasFirstDesc = false;
    for (jsize i = 0; i < count; ++i) {
        jobject bufferObject =
            env->GetObjectArrayElement(hardwareBuffers, i);
        if (bufferObject == nullptr) {
            g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
            return nullptr;
        }
        jobject metadataObject =
            env->GetObjectArrayElement(metadataArray, i);
        auto& frame = frames[static_cast<size_t>(i)];
        std::memset(&frame, 0, sizeof(frame));
        frame.struct_size = sizeof(frame);
        frame.hardware_buffer =
            AHardwareBuffer_fromHardwareBuffer(env, bufferObject);
        frame.metadata = parseMetadata(env, metadataObject);
        frame.android_format = kAndroidYuv420Format;
        if (frame.hardware_buffer != nullptr) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(frame.hardware_buffer, &desc);
            if (!hasFirstDesc) {
                firstDesc = desc;
                hasFirstDesc = true;
            }
            maxOutputSize = std::max<uint64_t>(
                maxOutputSize,
                static_cast<uint64_t>(desc.width) * desc.height * 3u + 65536u);
        }
        env->DeleteLocalRef(bufferObject);
        env->DeleteLocalRef(metadataObject);
    }

    // Configure capture using the real frame dimensions so a jpeg_quality-only
    // update never overwrites a previously configured width/height with 0.
    CameraEngineCaptureConfig captureConfig;
    camera_engine_capture_config_init(&captureConfig);
    captureConfig.jpeg_quality = static_cast<uint32_t>(
        std::clamp(static_cast<int>(jpegQuality), 1, 100));
    if (hasFirstDesc) {
        captureConfig.width = static_cast<uint32_t>(firstDesc.width);
        captureConfig.height = static_cast<uint32_t>(firstDesc.height);
    }
    g_lastStatus = camera_engine_configure_capture(
        fromHandle(pipelineHandle), &captureConfig);
    if (g_lastStatus != CAMERA_ENGINE_OK) return nullptr;

    std::vector<uint8_t> jpeg(
        static_cast<size_t>(std::max<uint64_t>(maxOutputSize, 1024u * 1024u)));
    CameraEngineMutableBuffer mutableBuffer{};
    mutableBuffer.struct_size = sizeof(mutableBuffer);
    mutableBuffer.data = jpeg.data();
    mutableBuffer.capacity = static_cast<uint32_t>(
        std::min<size_t>(jpeg.size(), UINT32_MAX));
    CameraEngineCaptureResult result;
    camera_engine_capture_result_init(&result);
    result.jpeg_output = &mutableBuffer;

    g_lastStatus = camera_engine_android_process_capture_hardware_buffers(
        fromHandle(pipelineHandle), frames.data(),
        static_cast<uint32_t>(frames.size()), &result);
    if (g_lastStatus != CAMERA_ENGINE_OK) {
        LOGE("Capture processing failed: %s",
             camera_engine_status_message(g_lastStatus));
        return nullptr;
    }
    jbyteArray output =
        env->NewByteArray(static_cast<jsize>(mutableBuffer.size));
    if (output != nullptr) {
        env->SetByteArrayRegion(
            output, 0, static_cast<jsize>(mutableBuffer.size),
            reinterpret_cast<const jbyte*>(jpeg.data()));
    }
    return output;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_NativeEngine_nativeProcessCaptureNv21(
        JNIEnv* env, jobject, jlong pipelineHandle,
        jobjectArray frameArrays, jobjectArray metadataArray,
        jint width, jint height, jint jpegQuality) {
    if (frameArrays == nullptr || metadataArray == nullptr ||
        width <= 0 || height <= 0) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return nullptr;
    }
    const jsize count = env->GetArrayLength(frameArrays);
    if (count <= 0 || env->GetArrayLength(metadataArray) != count) {
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return nullptr;
    }
    CameraEngineCaptureConfig captureConfig;
    camera_engine_capture_config_init(&captureConfig);
    captureConfig.width = static_cast<uint32_t>(width);
    captureConfig.height = static_cast<uint32_t>(height);
    captureConfig.format = CAMERA_ENGINE_PIXEL_FORMAT_NV21;
    captureConfig.jpeg_quality = static_cast<uint32_t>(
        std::clamp(static_cast<int>(jpegQuality), 1, 100));
    g_lastStatus = camera_engine_configure_capture(
        fromHandle(pipelineHandle), &captureConfig);
    if (g_lastStatus != CAMERA_ENGINE_OK) return nullptr;

    std::vector<jbyteArray> javaFrames(static_cast<size_t>(count));
    std::vector<jbyte*> pinnedFrames(static_cast<size_t>(count), nullptr);
    std::vector<CameraEngineFrame> frames(static_cast<size_t>(count));
    const jsize requiredFrameSize = width * height * 3 / 2;
    bool valid = true;
    for (jsize i = 0; i < count; ++i) {
        javaFrames[static_cast<size_t>(i)] = static_cast<jbyteArray>(
            env->GetObjectArrayElement(frameArrays, i));
        jobject metadataObject =
            env->GetObjectArrayElement(metadataArray, i);
        if (javaFrames[static_cast<size_t>(i)] == nullptr ||
            env->GetArrayLength(javaFrames[static_cast<size_t>(i)]) <
                requiredFrameSize) {
            valid = false;
        } else {
            pinnedFrames[static_cast<size_t>(i)] = env->GetByteArrayElements(
                javaFrames[static_cast<size_t>(i)], nullptr);
            valid = valid && pinnedFrames[static_cast<size_t>(i)] != nullptr;
        }

        CameraEngineFrame& frame = frames[static_cast<size_t>(i)];
        std::memset(&frame, 0, sizeof(frame));
        frame.struct_size = sizeof(frame);
        frame.image.struct_size = sizeof(frame.image);
        frame.image.format = CAMERA_ENGINE_PIXEL_FORMAT_NV21;
        frame.image.width = static_cast<uint32_t>(width);
        frame.image.height = static_cast<uint32_t>(height);
        frame.image.plane_count = 1;
        frame.image.planes[0].data = reinterpret_cast<uint8_t*>(
            pinnedFrames[static_cast<size_t>(i)]);
        frame.image.planes[0].row_stride = static_cast<uint32_t>(width);
        frame.image.planes[0].pixel_stride = 1;
        frame.image.planes[0].size_bytes =
            static_cast<uint32_t>(requiredFrameSize);
        frame.metadata = parseMetadata(env, metadataObject);
        env->DeleteLocalRef(metadataObject);
    }

    auto releaseFrames = [&]() {
        for (size_t i = 0; i < javaFrames.size(); ++i) {
            if (pinnedFrames[i] != nullptr) {
                env->ReleaseByteArrayElements(
                    javaFrames[i], pinnedFrames[i], JNI_ABORT);
            }
            if (javaFrames[i] != nullptr) env->DeleteLocalRef(javaFrames[i]);
        }
    };
    if (!valid) {
        releaseFrames();
        g_lastStatus = CAMERA_ENGINE_ERROR_INVALID_ARGUMENT;
        return nullptr;
    }

    const size_t outputCapacity =
        static_cast<size_t>(width) * height * 3u + 65536u;
    std::vector<uint8_t> jpeg(outputCapacity);
    CameraEngineMutableBuffer mutableBuffer{};
    mutableBuffer.struct_size = sizeof(mutableBuffer);
    mutableBuffer.data = jpeg.data();
    mutableBuffer.capacity = static_cast<uint32_t>(
        std::min<size_t>(jpeg.size(), UINT32_MAX));
    CameraEngineCaptureResult result;
    camera_engine_capture_result_init(&result);
    result.jpeg_output = &mutableBuffer;
    g_lastStatus = camera_engine_process_capture(
        fromHandle(pipelineHandle), frames.data(),
        static_cast<uint32_t>(frames.size()), &result);
    releaseFrames();
    if (g_lastStatus != CAMERA_ENGINE_OK) return nullptr;

    jbyteArray output =
        env->NewByteArray(static_cast<jsize>(mutableBuffer.size));
    if (output != nullptr) {
        env->SetByteArrayRegion(
            output, 0, static_cast<jsize>(mutableBuffer.size),
            reinterpret_cast<const jbyte*>(jpeg.data()));
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeEnableAlgorithm(
        JNIEnv*, jobject, jlong handle, jint algorithmId, jboolean enable) {
    CameraEngineAlgorithm algorithm =
        static_cast<CameraEngineAlgorithm>(algorithmId);
    const CameraEnginePipelineType pipeline =
        algorithm == CAMERA_ENGINE_ALGORITHM_FACE_DETECT
            ? CAMERA_ENGINE_PIPELINE_PREVIEW
            : CAMERA_ENGINE_PIPELINE_CAPTURE;
    g_lastStatus = camera_engine_enable_algorithm(
        fromHandle(handle), pipeline, algorithm, enable == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeSetAlgorithmParam(
        JNIEnv*, jobject, jlong, jint, jstring, jfloat) {
    // Product code sends user intent through SessionControl. This legacy debug
    // entry point intentionally no longer participates in normal decisions.
}
