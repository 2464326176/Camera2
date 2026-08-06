#include "src/algorithm/algorithm_manager.h"

#include "src/algorithms/bokeh.h"
#include "src/algorithms/clahe.h"
#include "src/algorithms/denoise.h"
#include "src/algorithms/face_detect.h"
#include "src/algorithms/hdr.h"
#include "src/algorithms/saturation.h"
#include "src/algorithms/sharpen.h"
#include "src/decision/decision.h"

#include <functional>
#include <utility>

namespace camera_engine {
namespace {

float paramOr(
        const AlgorithmContext& context,
        uint32_t id,
        float fallback) {
    const auto it = context.params.find(id);
    return it == context.params.end() ? fallback : it->second;
}

class StatelessAlgorithm : public IAlgorithm {
public:
    ResultCode init(const AlgorithmInitInfo& /* info */) override {
        m_initialized = true;
        return ResultCode::OK;
    }
    void uninit() override { m_initialized = false; }
    bool isInitialized() const override { return m_initialized; }

protected:
    bool m_initialized = false;
};

class FaceDetectAlgorithm final : public IAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::FACE_DETECT; }
    ResultCode init(const AlgorithmInitInfo& info) override {
        const std::string modelPath =
            info.assetDir + "/face_detection_yunet_2023mar.onnx";
        m_initialized = m_detector.init(modelPath);
        return m_initialized ? ResultCode::OK : ResultCode::INIT_FAILED;
    }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.faces = m_detector.detect(
            context.image, context.originalWidth, context.originalHeight);
        return ResultCode::OK;
    }
    void uninit() override {
        m_detector.release();
        m_initialized = false;
    }
    bool isInitialized() const override { return m_initialized; }

private:
    FaceDetector m_detector;
    bool m_initialized = false;
};

class DenoiseAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::DENOISE; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.sourceFrames.empty()) return ResultCode::NOT_READY;
        const int iso = context.metadata.empty() ? 100 : context.metadata.front().iso;
        context.image = context.sourceFrames.size() >= 2
            ? Denoiser::denoiseMulti(context.sourceFrames, iso)
            : Denoiser::denoiseSingle(context.sourceFrames.front(), iso);
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

class SharpenAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::SHARPEN; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.image = Sharpener::sharpen(
            context.image, paramOr(context, PARAM_SHARPEN_STRENGTH, 0.15f), 2.0f);
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

class HdrAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::HDR; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.image = HdrToneMap::apply(
            context.image, 1.0f, paramOr(context, PARAM_HDR_STRENGTH, 0.7f));
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

class ClaheAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::CLAHE; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.image = ClaheEnhancer::apply(
            context.image, paramOr(context, PARAM_CLAHE_CLIP_LIMIT, 2.0f), 8);
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

class SaturationAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::SATURATION; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.image = SaturationAdjuster::adjust(
            context.image, paramOr(context, PARAM_SATURATION_FACTOR, 1.05f));
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

class BokehAlgorithm final : public StatelessAlgorithm {
public:
    AlgorithmId id() const override { return AlgorithmId::BOKEH; }
    ResultCode process(AlgorithmContext& context) override {
        if (!m_initialized || context.image.empty()) return ResultCode::NOT_READY;
        context.image = BokehEffect::apply(
            context.image, 0.5f, 0.45f, 0.35f,
            paramOr(context, PARAM_BOKEH_STRENGTH, 0.5f));
        return context.image.empty() ? ResultCode::ERROR : ResultCode::OK;
    }
};

// Maps each algorithm id to its constructor so getOrCreate stays free of a
// long switch. Adding an algorithm is a one-line registration here.
using AlgorithmFactory = std::function<std::unique_ptr<IAlgorithm>()>;
const std::unordered_map<AlgorithmId, AlgorithmFactory>& algorithmFactories() {
    static const std::unordered_map<AlgorithmId, AlgorithmFactory> kFactories = {
        {AlgorithmId::FACE_DETECT, [] { return std::make_unique<FaceDetectAlgorithm>(); }},
        {AlgorithmId::DENOISE,     [] { return std::make_unique<DenoiseAlgorithm>(); }},
        {AlgorithmId::SHARPEN,     [] { return std::make_unique<SharpenAlgorithm>(); }},
        {AlgorithmId::HDR,         [] { return std::make_unique<HdrAlgorithm>(); }},
        {AlgorithmId::CLAHE,       [] { return std::make_unique<ClaheAlgorithm>(); }},
        {AlgorithmId::SATURATION,  [] { return std::make_unique<SaturationAlgorithm>(); }},
        {AlgorithmId::BOKEH,       [] { return std::make_unique<BokehAlgorithm>(); }},
    };
    return kFactories;
}

} // namespace

AlgorithmManager::AlgorithmManager(PipelineType pipeline) : m_pipeline(pipeline) {}

AlgorithmManager::~AlgorithmManager() {
    uninitAll();
}

IAlgorithm* AlgorithmManager::getOrCreate(AlgorithmId id) {
    const auto found = m_algorithms.find(id);
    if (found != m_algorithms.end()) return found->second.get();

    const auto& factories = algorithmFactories();
    const auto factory = factories.find(id);
    if (factory == factories.end()) return nullptr;

    std::unique_ptr<IAlgorithm> algorithm = factory->second();
    IAlgorithm* result = algorithm.get();
    m_algorithms.emplace(id, std::move(algorithm));
    return result;
}

ResultCode AlgorithmManager::applyInit(
        const std::vector<AlgorithmId>& algorithms,
        const AlgorithmInitInfo& info) {
    for (AlgorithmId id : algorithms) {
        IAlgorithm* algorithm = getOrCreate(id);
        if (!algorithm) return ResultCode::ERROR;
        if (!algorithm->isInitialized()) {
            const ResultCode status = algorithm->init(info);
            if (status != ResultCode::OK) return status;
        }
    }
    return ResultCode::OK;
}

ResultCode AlgorithmManager::execute(
        const std::vector<AlgorithmStage>& stages,
        AlgorithmContext& context) {
    for (const AlgorithmStage& stage : stages) {
        IAlgorithm* algorithm = getOrCreate(stage.id);
        if (!algorithm || !algorithm->isInitialized()) return ResultCode::NOT_READY;
        context.params = stage.params;
        const ResultCode status = algorithm->process(context);
        if (status != ResultCode::OK) return status;
    }
    return ResultCode::OK;
}

void AlgorithmManager::applyUninit(const std::vector<AlgorithmId>& algorithms) {
    for (AlgorithmId id : algorithms) {
        const auto found = m_algorithms.find(id);
        if (found != m_algorithms.end()) found->second->uninit();
    }
}

void AlgorithmManager::uninitAll() {
    for (auto& item : m_algorithms) item.second->uninit();
}

} // namespace camera_engine
