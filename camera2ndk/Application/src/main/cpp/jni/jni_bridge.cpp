/**
 * JNI bridge for the native camera engine.
 *
 * This file exposes native pipeline creation, configuration, frame processing,
 * JPEG encoding, and HardwareBuffer utilities to the Android Java/Kotlin layer.
 */
#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "../core/types.h"
#include "../core/frame.h"
#include "../core/metadata.h"
#include "../pipeline/preview_pipeline.h"
#include "../pipeline/capture_pipeline.h"

#define LOG_TAG "JniBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace camera_engine;

// ─── Engine internal structures ───

struct EngineContext {
    std::string modelDir;
    std::mutex mutex;
};

struct PipelineEntry {
    std::unique_ptr<PipelineBase> pipeline;
    int type; // 0=preview, 1=capture
};

static std::unordered_map<jlong, std::unique_ptr<EngineContext>> g_engines;
static std::unordered_map<jlong, std::unique_ptr<PipelineEntry>> g_pipelines;
static std::mutex g_engineMutex;
static jlong g_nextEngineId = 1;
static jlong g_nextPipelineId = 1;

// ─── Helper functions ───

/**
 * Converts the Java CaptureMetadata object into the native metadata structure.
 */
static FrameMetadata parseMetadata(JNIEnv* env, jobject metaObj) {
    FrameMetadata meta;
    if (!metaObj) return meta;

    jclass cls = env->GetObjectClass(metaObj);
    meta.timestampNs = env->GetLongField(metaObj, env->GetFieldID(cls, "timestampNs", "J"));
    meta.iso = env->GetIntField(metaObj, env->GetFieldID(cls, "iso", "I"));
    meta.exposureTimeNs = env->GetLongField(metaObj, env->GetFieldID(cls, "exposureTimeNs", "J"));
    meta.flashState = env->GetIntField(metaObj, env->GetFieldID(cls, "flashState", "I"));
    meta.lensAperture = env->GetFloatField(metaObj, env->GetFieldID(cls, "lensAperture", "F"));
    meta.aeState = env->GetIntField(metaObj, env->GetFieldID(cls, "aeState", "I"));
    meta.afState = env->GetIntField(metaObj, env->GetFieldID(cls, "afState", "I"));
    meta.awbState = env->GetIntField(metaObj, env->GetFieldID(cls, "awbState", "I"));
    env->DeleteLocalRef(cls);
    return meta;
}

/**
 * Converts a Java HardwareBuffer object to AHardwareBuffer and locks it for CPU reads.
 */
static std::shared_ptr<HardwareBufferRef> lockHardwareBuffer(JNIEnv* env, jobject hwBuffer) {
    if (!hwBuffer) return nullptr;
    AHardwareBuffer* ahwb = AHardwareBuffer_fromHardwareBuffer(env, hwBuffer);
    if (!ahwb) return nullptr;

    auto ref = std::make_shared<HardwareBufferRef>();
    if (!ref->lock(ahwb)) return nullptr;
    return ref;
}

/**
 * Packs native face rectangles and landmarks into a flat Java float array.
 */
static jfloatArray facesToJni(JNIEnv* env, const std::vector<FaceRect>& faces) {
    if (faces.empty()) return env->NewFloatArray(0);

    int count = (int)faces.size();
    // Per face: x, y, w, h, confidence, 10 landmarks = 15 floats
    jfloatArray arr = env->NewFloatArray(count * 15);
    if (!arr) return nullptr;

    std::vector<float> buf(count * 15);
    for (int i = 0; i < count; i++) {
        int offset = i * 15;
        buf[offset] = faces[i].x;
        buf[offset + 1] = faces[i].y;
        buf[offset + 2] = faces[i].w;
        buf[offset + 3] = faces[i].h;
        buf[offset + 4] = faces[i].confidence;
        for (int j = 0; j < 10; j++) {
            buf[offset + 5 + j] = faces[i].landmarks[j];
        }
    }
    env->SetFloatArrayRegion(arr, 0, count * 15, buf.data());
    return arr;
}

/**
 * Copies a native JPEG byte vector into a Java byte array.
 */
static jbyteArray jpegToJni(JNIEnv* env, const std::vector<uint8_t>& data) {
    if (data.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)data.size());
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, (jsize)data.size(), (const jbyte*)data.data());
    return arr;
}

// ==================== Engine lifecycle ====================

/**
 * Creates a native engine context and returns an opaque handle to Java.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreateEngine(
        JNIEnv* env, jclass /* clazz */, jstring modelDir) {

    const char* dir = env->GetStringUTFChars(modelDir, nullptr);
    auto ctx = std::make_unique<EngineContext>();
    ctx->modelDir = dir;
    env->ReleaseStringUTFChars(modelDir, dir);

    std::lock_guard<std::mutex> lock(g_engineMutex);
    jlong id = g_nextEngineId++;
    g_engines[id] = std::move(ctx);
    LOGD("Engine created: %lld", (long long)id);
    return id;
}

/**
 * Destroys the native engine context and all pipelines associated with it.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeDestroyEngine(
        JNIEnv* /* env */, jclass /* clazz */, jlong engineHandle) {

    std::lock_guard<std::mutex> lock(g_engineMutex);
    auto it = g_engines.find(engineHandle);
    if (it != g_engines.end()) {
        // Destroy associated pipelines
        auto pit = g_pipelines.begin();
        while (pit != g_pipelines.end()) {
            if (pit->first == engineHandle) {
                pit = g_pipelines.erase(pit);
            } else {
                ++pit;
            }
        }
        g_engines.erase(it);
        LOGD("Engine destroyed: %lld", (long long)engineHandle);
    }
}

// ==================== Pipeline creation ====================

/**
 * Creates and configures a preview pipeline for real-time frame processing.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreatePreviewPipeline(
        JNIEnv* env, jclass /* clazz */, jlong engineHandle,
        jint width, jint height, jint format, jint maxFaces) {

    std::lock_guard<std::mutex> lock(g_engineMutex);
    auto it = g_engines.find(engineHandle);
    if (it == g_engines.end()) {
        LOGE("Engine not found: %lld", (long long)engineHandle);
        return 0;
    }

    auto pipeline = std::make_unique<PreviewPipeline>();
    PipelineConfig config;
    config.width = width;
    config.height = height;
    config.format = static_cast<YuvFormat>(format);
    config.maxFaces = maxFaces;
    pipeline->configure(config);

    // Initialize face detector
    std::string modelPath = it->second->modelDir + "/face_detection_yunet_2023mar.onnx";
    pipeline->initFaceDetector(modelPath);

    jlong id = g_nextPipelineId++;
    auto entry = std::make_unique<PipelineEntry>();
    entry->pipeline = std::move(pipeline);
    entry->type = 0;
    g_pipelines[id] = std::move(entry);
    LOGD("Preview pipeline created: %lld (%dx%d)", (long long)id, width, height);
    return id;
}

/**
 * Creates and configures a still-capture pipeline for high-quality processing.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_opencv_camera_NativeEngine_nativeCreateCapturePipeline(
        JNIEnv* /* env */, jclass /* clazz */, jlong engineHandle,
        jint width, jint height, jint format) {

    std::lock_guard<std::mutex> lock(g_engineMutex);
    auto it = g_engines.find(engineHandle);
    if (it == g_engines.end()) {
        LOGE("Engine not found: %lld", (long long)engineHandle);
        return 0;
    }

    auto pipeline = std::make_unique<CapturePipeline>();
    PipelineConfig config;
    config.width = width;
    config.height = height;
    config.format = static_cast<YuvFormat>(format);
    pipeline->configure(config);

    jlong id = g_nextPipelineId++;
    auto entry = std::make_unique<PipelineEntry>();
    entry->pipeline = std::move(pipeline);
    entry->type = 1;
    g_pipelines[id] = std::move(entry);
    LOGD("Capture pipeline created: %lld (%dx%d)", (long long)id, width, height);
    return id;
}

/**
 * Releases a native pipeline handle created for preview or capture.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeDestroyPipeline(
        JNIEnv* /* env */, jclass /* clazz */, jlong /* engineHandle */, jlong pipelineHandle) {

    std::lock_guard<std::mutex> lock(g_engineMutex);
    g_pipelines.erase(pipelineHandle);
    LOGD("Pipeline destroyed: %lld", (long long)pipelineHandle);
}

// ==================== Frame processing ====================

/**
 * Processes one preview HardwareBuffer and returns detected face data to Java.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_opencv_camera_NativeEngine_nativeProcessPreviewFrame(
        JNIEnv* env, jclass /* clazz */, jlong pipelineHandle,
        jobject hardwareBuffer, jobject metadata) {

    // Look up pipeline
    PipelineEntry* entry = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engineMutex);
        auto it = g_pipelines.find(pipelineHandle);
        if (it != g_pipelines.end()) entry = it->second.get();
    }
    if (!entry || entry->type != 0) {
        return env->NewFloatArray(0);
    }

    auto* preview = static_cast<PreviewPipeline*>(entry->pipeline.get());

    // Lock HardwareBuffer (zero-copy)
    auto hwRef = lockHardwareBuffer(env, hardwareBuffer);
    if (!hwRef) return env->NewFloatArray(0);

    // Parse metadata
    FrameMetadata meta = parseMetadata(env, metadata);

    // Create YuvFrame
    YuvFrame frame(hwRef, meta, YuvFormat::NV21);

    // Process
    PreviewResult result = preview->process(frame);

    // Unlock HardwareBuffer
    hwRef->unlock();

    return facesToJni(env, result.faces);
}

/**
 * Processes one or more capture HardwareBuffers and returns encoded JPEG bytes.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_opencv_camera_NativeEngine_nativeProcessCapture(
        JNIEnv* env, jclass /* clazz */, jlong pipelineHandle,
        jobjectArray hardwareBuffers, jobjectArray metadataArray, jint jpegQuality) {

    // Look up pipeline
    PipelineEntry* entry = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engineMutex);
        auto it = g_pipelines.find(pipelineHandle);
        if (it != g_pipelines.end()) entry = it->second.get();
    }
    if (!entry || entry->type != 1) {
        return nullptr;
    }

    auto* capture = static_cast<CapturePipeline*>(entry->pipeline.get());

    jsize frameCount = env->GetArrayLength(hardwareBuffers);
    if (frameCount <= 0) return nullptr;

    // Lock all HardwareBuffers and build YuvFrame list
    std::vector<YuvFrame> frames;
    std::vector<std::shared_ptr<HardwareBufferRef>> refs; // Keep refs alive to prevent premature release

    for (jsize i = 0; i < frameCount; i++) {
        jobject hwBuf = env->GetObjectArrayElement(hardwareBuffers, i);
        jobject metaObj = env->GetObjectArrayElement(metadataArray, i);

        auto hwRef = lockHardwareBuffer(env, hwBuf);
        if (hwRef) {
            FrameMetadata meta = parseMetadata(env, metaObj);
            frames.emplace_back(hwRef, meta, YuvFormat::NV21);
            refs.push_back(hwRef);
        }

        env->DeleteLocalRef(hwBuf);
        env->DeleteLocalRef(metaObj);
    }

    if (frames.empty()) return nullptr;

    // Set JPEG quality
    capture->setAlgorithmParam(AlgorithmId::DENOISE, "jpegQuality", (float)jpegQuality);

    // Process
    CaptureResult result = capture->process(frames);

    // Unlock all HardwareBuffers
    for (auto& ref : refs) {
        ref->unlock();
    }

    return jpegToJni(env, result.jpegData);
}

// ==================== Algorithm control ====================

/**
 * Toggles an algorithm on the selected native pipeline.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeEnableAlgorithm(
        JNIEnv* /* env */, jclass /* clazz */, jlong pipelineHandle,
        jint algorithmId, jboolean enable) {

    std::lock_guard<std::mutex> lock(g_engineMutex);
    auto it = g_pipelines.find(pipelineHandle);
    if (it != g_pipelines.end() && it->second->pipeline) {
        it->second->pipeline->enableAlgorithm(static_cast<AlgorithmId>(algorithmId), enable);
    }
}

/**
 * Updates one floating-point algorithm parameter from Java.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_opencv_camera_NativeEngine_nativeSetAlgorithmParam(
        JNIEnv* env, jclass /* clazz */, jlong pipelineHandle,
        jint algorithmId, jstring paramKey, jfloat paramValue) {

    const char* key = env->GetStringUTFChars(paramKey, nullptr);

    std::lock_guard<std::mutex> lock(g_engineMutex);
    auto it = g_pipelines.find(pipelineHandle);
    if (it != g_pipelines.end() && it->second->pipeline) {
        it->second->pipeline->setAlgorithmParam(
            static_cast<AlgorithmId>(algorithmId), key, paramValue);
    }

    env->ReleaseStringUTFChars(paramKey, key);
}