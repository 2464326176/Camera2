/**
 * Shared camera engine type definitions.
 *
 * This header defines lightweight enums and geometry structures used across
 * frame processing, algorithms, encoding, and JNI boundaries.
 */
#pragma once
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace camera_engine {

enum class YuvFormat { NV21 = 0, NV12 = 1, I420 = 2 };

enum class AlgorithmId {
    FACE_DETECT = 0,
    DENOISE = 1,
    SHARPEN = 2,
    BOKEH = 3,
    HDR = 4,
    CLAHE = 5,
    SATURATION = 6
};

enum class ResultCode {
    OK = 0,
    ERROR = 1,
    FRAME_SKIPPED = 2,
    NOT_READY = 3,
    INIT_FAILED = 4
};

enum class PipelineType { PREVIEW = 1, CAPTURE = 2, VIDEO = 3 };

enum class CameraMode { PHOTO = 0, VIDEO = 1 };

struct AlgorithmConfig {
    bool enabled = true;
    float strength = 1.0f;
};

struct PipelineConfig {
    int width = 0;
    int height = 0;
    YuvFormat format = YuvFormat::NV21;
    int maxFaces = 10;
    int faceDetectIntervalMs = 180;
    int jpegQuality = 95;
    bool faceDetectEnabled = true;
    std::string assetDir;
};

/**
 * Runtime state collected by the Java layer. Values express user intent and
 * camera state only; the native decision layer owns all algorithm choices.
 */
struct SessionControl {
    CameraMode mode = CameraMode::PHOTO;
    int lensFacing = 0;
    int flashMode = 0;
    bool preferFaceDetect = true;
    bool preferDenoise = true;
    bool preferSharpen = true;
    bool preferHdr = false;
    bool preferClahe = false;
    bool preferSaturation = false;
    bool preferBokeh = false;
    int jpegQuality = 95;
    int analysisMaxSide = 320;
};

struct AlgorithmStage {
    AlgorithmId id = AlgorithmId::DENOISE;
    std::unordered_map<uint32_t, float> params;
};

struct DecisionPlan {
    bool skipEntireFrame = false;
    std::vector<AlgorithmId> needInit;
    std::vector<AlgorithmStage> stages;
    std::vector<AlgorithmId> needUninit;
    uint32_t reasonFlags = 0;
};

struct FaceRect {
    float x, y, w, h;
    float confidence;
    float landmarks[10]; // 5 landmarks: x0,y0,x1,y1,...,x4,y4
};

struct PreviewResult {
    std::vector<FaceRect> faces;
    int64_t timestampNs = 0;
    ResultCode status = ResultCode::OK;
};

struct CaptureResult {
    std::vector<uint8_t> jpegData;
    int32_t iso = 0;
    int64_t timestampNs = 0;
    ResultCode status = ResultCode::OK;
};

} // namespace camera_engine