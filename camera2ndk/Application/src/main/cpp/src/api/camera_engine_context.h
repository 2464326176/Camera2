#pragma once

#include <memory>
#include <string>

#include "src/pipeline/capture_pipeline.h"
#include "src/pipeline/preview_pipeline.h"

struct CameraEngineContext {
    std::string assetDir;
    std::string cacheDir;
    std::unique_ptr<camera_engine::PreviewPipeline> previewPipeline;
    std::unique_ptr<camera_engine::CapturePipeline> capturePipeline;
};
