# Camera Engine SDK

Boundary:

- Java collects `SessionControl`, frame metadata and image buffers.
- C++ owns algorithm decisions, throttling, lifecycle, ordering, parameters,
  image processing and capture advice.
- JNI only converts Java objects to the stable C ABI in
  `Application/src/main/cpp/include/camera_engine`.

## Build modes

`:camera-engine/build.gradle` auto-selects:

1. **Source mode** (monorepo): `Application/src/main/cpp/**/*.cpp` exists
   → CMake builds `libcamera_engine.so`.
2. **Prebuilt / split-repo mode**: cpp tree is absent
   → packages `camera-engine/src/main/jniLibs/<abi>/libcamera_engine.so`
   produced by `Application/src/main/cpp/build-ndk.bat`.

```bat
Application\src\main\cpp\build-ndk.bat Release all
```

## Algorithm extension

1. Implement or wrap the algorithm behind `IAlgorithm::init/process/uninit`.
2. Register its factory in `AlgorithmManager::getOrCreate`.
3. Add policy and ordering only in `PreviewDecision`, `CaptureDecision` or
   `VideoDecision`.
4. Do not add algorithm branches to the App module.

## Runtime assets

App installs `res/raw/face_detection_yunet_2023mar.onnx` into `filesDir`
via `ModelAssets.ensureModelDir()` before `CameraAlgoSdk.create(assetDir)`.

## Thread and buffer contract

- Processing APIs are synchronous.
- Java closes `HardwareBuffer` only after an API call returns.
- Native code locks/unlocks buffers inside the call and never retains them.
- A `CameraAlgoSdk` instance serializes calls to one native context.
