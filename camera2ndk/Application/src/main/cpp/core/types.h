/**
 * Shared camera engine type definitions.
 *
 * This header defines lightweight enums and geometry structures used across
 * frame processing, algorithms, encoding, and JNI boundaries.
 */
#pragma once
#include <cstdint>
#include <string>

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

enum class ResultCode { OK = 0, ERROR = 1, FRAME_SKIPPED = 2 };

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
};

struct FaceRect {
    float x, y, w, h;
    float confidence;
    float landmarks[10]; // 5 landmarks: x0,y0,x1,y1,...,x4,y4
};

struct PreviewResult {
    std::vector<FaceRect> faces;
    int64_t timestampNs = 0;
};

struct CaptureResult {
    std::vector<uint8_t> jpegData;
    int32_t iso = 0;
    int64_t timestampNs = 0;
};

} // namespace camera_engine