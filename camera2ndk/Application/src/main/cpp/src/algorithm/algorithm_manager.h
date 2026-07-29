#pragma once

#include "src/core/metadata.h"
#include "src/core/types.h"

#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include <opencv2/core.hpp>

namespace camera_engine {

struct AlgorithmInitInfo {
    std::string assetDir;
    PipelineType pipeline = PipelineType::PREVIEW;
    int width = 0;
    int height = 0;
};

struct AlgorithmContext {
    std::vector<cv::Mat> sourceFrames;
    cv::Mat image;
    std::vector<FrameMetadata> metadata;
    std::vector<FaceRect> faces;
    int originalWidth = 0;
    int originalHeight = 0;
    std::unordered_map<uint32_t, float> params;
};

class IAlgorithm {
public:
    virtual ~IAlgorithm() = default;
    virtual AlgorithmId id() const = 0;
    virtual ResultCode init(const AlgorithmInitInfo& info) = 0;
    virtual ResultCode process(AlgorithmContext& context) = 0;
    virtual void uninit() = 0;
    virtual bool isInitialized() const = 0;
};

class AlgorithmManager {
public:
    explicit AlgorithmManager(PipelineType pipeline);
    ~AlgorithmManager();

    ResultCode applyInit(
        const std::vector<AlgorithmId>& algorithms,
        const AlgorithmInitInfo& info);
    ResultCode execute(
        const std::vector<AlgorithmStage>& stages,
        AlgorithmContext& context);
    void applyUninit(const std::vector<AlgorithmId>& algorithms);
    void uninitAll();

private:
    IAlgorithm* getOrCreate(AlgorithmId id);

    PipelineType m_pipeline;
    std::unordered_map<AlgorithmId, std::unique_ptr<IAlgorithm>> m_algorithms;
};

} // namespace camera_engine
