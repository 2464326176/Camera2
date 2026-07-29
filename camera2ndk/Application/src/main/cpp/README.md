# NDK 功能与编译层级说明

本文说明本项目 native（C++/JNI）侧的职责分层、调用关系，以及两种编译方式。

## 1. 总体边界

| 端 | 职责 |
|----|------|
| **Java（Application）** | Camera2、UI、收集 `SessionControl` / `FrameMetadata`、传帧 |
| **Java Facade（camera-engine）** | `CameraAlgoSdk`：对 App 暴露稳定 API |
| **JNI** | 仅做 Java ↔ C 结构体转换，不含算法逻辑 |
| **C++** | 算法决策、加载、处理、参数、Burst 建议等**全部**在此 |

一句话：**Java 传态与展示；C++ 决策与处理。**

---

## 2. 目录与模块层级

```
camera2ndk/
├── Application/                         # App：Camera / UI
│   ├── build.gradle
│   ├── CMakeLists.txt                   # native 源码的 CMake 入口
│   └── src/main/
│       ├── java/.../camera/             # CameraFragment 等
│       ├── res/raw/*.onnx               # 模型资源（运行时拷到 filesDir）
│       ├── jniLibs/                     # 宿主侧预置库（如 OpenCV）
│       └── cpp/                         # ★ native 源码树（见下）
│           ├── build-ndk.bat            # 独立 NDK 编译脚本
│           ├── include/camera_engine/   # 公开 C ABI 头文件
│           └── src/
│               ├── api/                 # C ABI 实现
│               ├── bindings/jni/        # 薄 JNI
│               ├── decision/            # Preview / Capture / Video 决策
│               ├── algorithm/           # IAlgorithm + AlgorithmManager
│               ├── algorithms/          # 具体算法实现
│               ├── pipeline/            # 按 DecisionPlan 执行
│               ├── core/                # Frame / Metadata / 类型
│               ├── platform/android/    # AHardwareBuffer 适配
│               └── encode/              # JPEG 等编码
│
└── camera-engine/                       # SDK Module（可后续拆仓发 AAR）
    ├── build.gradle                     # 有 cpp → CMake；无 cpp → jniLibs
    ├── src/main/java/...                # CameraAlgoSdk / NativeEngine 等
    └── src/main/jniLibs/                # 预编译 libcamera_engine.so（拆仓用）
```

---

## 3. 运行时调用层级

```text
CameraFragment / CameraEngine
        │  SessionControl + FrameMetadata + HardwareBuffer
        ▼
  CameraAlgoSdk（Java Facade）
        │
        ▼
  NativeEngine（JNI 声明）
        │
        ▼
  camera_engine_jni.cpp          ← 仅类型转换
        │
        ▼
  camera_engine.h（稳定 C ABI）
        │
        ├── update_session_control / advise_capture
        └── process_preview / process_capture
                │
                ▼
        Decision 层（PreviewDecision / CaptureDecision）
                │  DecisionPlan：need_init / stages / need_uninit
                ▼
        AlgorithmManager
                │  init → process → uninit
                ▼
        IAlgorithm 插件（FaceDetect / Denoise / Sharpen / …）
                │
                ▼
        Pipeline 汇总结果 → Status + faces / JPEG
```

### 各层一句话

| 层 | 做什么 | 不做什么 |
|----|--------|----------|
| **Facade** | 传态、持有 handle | 写算法 if / 节流 |
| **JNI** | jobject ↔ C struct | include 内部算法头、决策 |
| **C ABI** | 版本化对外契约 | 依赖 Android UI |
| **Decision** | 开哪些算法、是否跳过、强度、Burst 张数建议 | 抠像素 |
| **AlgorithmManager** | 生命周期与实例池 | 解释 UI 语义 |
| **Algorithm** | init / process / uninit | 读 UI、私自常驻加载 |
| **Pipeline** | 按 Plan 顺序执行并填结果 | 改 Plan |

---

## 4. 编译方式

`:camera-engine/build.gradle` 会自动判断：

### 方式 A：源码同仓编译（当前默认）

- 条件：`Application/src/main/cpp/**/*.cpp` 存在
- 行为：Gradle + CMake 编译 `libcamera_engine.so`
- 日志：`[camera-engine] Native sources found → building with CMake`
- 命令：

```bat
gradlew :Application:assembleDebug
```

### 方式 B：独立 NDK 脚本 → 预编译库

- 脚本：[`build-ndk.bat`](build-ndk.bat)
- 用途：不依赖完整 App 构建，直接打出 `.so`，供拆仓 / 只带 jniLibs 使用
- 产物目录：`camera-engine/src/main/jniLibs/<abi>/`
  - `libcamera_engine.so`
  - `libopencv_java4.so`（一并拷贝）

```bat
REM Debug / 默认 arm64-v8a
Application\src\main\cpp\build-ndk.bat

REM Release 全 ABI
Application\src\main\cpp\build-ndk.bat Release all
```

前置：`local.properties` 中配置 `sdk.dir`、`opencv.dir`（可选 `ndk.dir` / `cmake.dir`）。

### 方式 C：拆仓后只吃 jniLibs

- 条件：去掉 `Application/src/main/cpp`（或 cpp 树为空）
- 行为：Gradle **不再**跑 CMake，直接打包 `camera-engine/src/main/jniLibs`
- 日志：`[camera-engine] No native sources → using prebuilt jniLibs`
- 流程建议：先在有源码环境跑 `build-ndk.bat` → 提交/发布 jniLibs → App 仓只依赖 SDK + 预编译 `.so`

---

## 5. 模型与运行依赖

| 资源 | 位置 | 说明 |
|------|------|------|
| 人脸模型 | `Application/src/main/res/raw/face_detection_yunet_2023mar.onnx` | 启动时由 `ModelAssets` 拷到 `filesDir` |
| OpenCV | App 或 engine 的 `jniLibs` | `System.loadLibrary("opencv_java4")` |
| camera_engine | CMake 产物或预编译 jniLibs | `System.loadLibrary("camera_engine")` |

---

## 6. 扩展算法时改哪里

1. 在 `src/algorithms/` 实现算法（或包装已有静态方法）
2. 在 `AlgorithmManager` 中按 `AlgorithmId` 注册工厂（`init/process/uninit`）
3. 在 `PreviewDecision` / `CaptureDecision` / `VideoDecision` 中写策略与顺序
4. **不要**在 Application Java 里加算法开关分支；UI 只写 `SessionControl.user_pref_*`

---

## 7. 相关文件速查

| 文件 | 作用 |
|------|------|
| [`include/camera_engine/camera_engine.h`](include/camera_engine/camera_engine.h) | 公开 C API |
| [`src/bindings/jni/camera_engine_jni.cpp`](src/bindings/jni/camera_engine_jni.cpp) | JNI 薄封装 |
| [`src/decision/`](src/decision/) | 决策层 |
| [`src/algorithm/algorithm_manager.*`](src/algorithm/) | 算法生命周期 |
| [`../../../../camera-engine/build.gradle`](../../../../camera-engine/build.gradle) | 源码 / 预编译自动切换 |
| [`build-ndk.bat`](build-ndk.bat) | 独立 NDK 编译脚本 |
