# Camera2 整合示例（Camera Samples Integration）

一个把 Google 官方多个独立 Android 相机示例（Camera2 / CameraX 系列共 11 个模块）**整合进单一 Android 应用**的工程。打开 App 后通过一个统一的主菜单即可进入任意一个示例，无需在多个工程之间切换。

> 整合基于 AOSP `camera-samples` 中的下列示例，并对包名、资源名、Manifest 做了统一重命名，使它们能在同一个 `applicationId` 下共存。

---

## 目录

- [整合的模块与功能](#整合的模块与功能)
- [工程结构](#工程结构)
- [代码架构与流程图](#代码架构与流程图)
- [各模块技术要点](#各模块技术要点)
- [资源与依赖整合说明](#资源与依赖整合说明)
- [构建与运行](#构建与运行)
- [已知限制](#已知限制)

---

## 整合的模块与功能

应用主菜单（`MainActivity`）列出以下 11 个示例，点击卡片即可启动对应模块：

| 序号 | 模块名 | 入口类 | 功能简介 | 技术栈 |
|------|--------|--------|----------|--------|
| 1 | Camera2Basic | `basic.CameraActivity` | 通过 Camera2 API 拍摄 JPEG / RAW / DEPTH 照片 | Camera2 + Fragment 导航 |
| 2 | Camera2Extensions | `extensions.CameraActivity` | Camera2 扩展（如 HDR、散景、夜景）实时预览与拍照 | Camera2 Extension |
| 3 | Camera2SlowMotion | `slowmo.CameraActivity` | 高速（慢动作）视频采集，受限于设备的慢动作能力 | Camera2 + MediaRecorder |
| 4 | Camera2Video | `video.CameraActivity` | 用 Camera2 API + MediaRecorder 录制视频（含多种参数调节） | Camera2 + EGL + MediaCodec |
| 5 | CameraXBasic | `cameraxbasic.MainActivity` | CameraX 入门：拍照、预览、图库、图像分析 | CameraX core/camera2/lifecycle/view |
| 6 | CameraXAdvanced (TFLite) | `advanced.CameraActivity` | 用 CameraX + TensorFlow Lite 做实时目标检测 | CameraX + TFLite + NNAPI |
| 7 | CameraXVideo | `cameraxvideo.MainActivity` | 用 CameraX `VideoCapture` 录制视频 | CameraX video |
| 8 | CameraX-MLKit | `mlkit.MainActivity` | 用 CameraX `MlKitAnalyzer` + ML Kit 扫描二维码 | CameraX + ML Kit Barcode |
| 9 | CameraXExtensions | `cameraxext.MainActivity` | CameraX 扩展（传统 View + RecyclerView UI）实时预览与拍照 | CameraX Extensions + Coil + Dynamic Animation |
| 10 | Camera2HdrViewfinder | `hdr.HdrViewfinderActivity` | 用 Camera2 + 手动曝光合成实时 HDR 取景器 | Camera2 + RenderScript Allocation（Java 重写） |
| 11 | （主菜单） | `MainActivity` | 统一启动器，列出并跳转上述所有示例 | RecyclerView + 导航 |

> 第 10 项在源码树中目录名为 `HdrViewfinder`，整合后包名为 `hdr`，本文统称为 HDR Viewfinder。

---

## 工程结构

```
Camera2/
├── app/
│   ├── build.gradle                      # 模块构建脚本（含全部依赖）
│   ├── src/main/
│   │   ├── AndroidManifest.xml           # 统一 Manifest，注册 11 个 Activity + FileProvider
│   │   ├── assets/
│   │   │   ├── coco_ssd_mobilenet_v1_1.0_quant.tflite   # TFLite 模型（CameraXAdvanced）
│   │   │   └── coco_ssd_mobilenet_v1_1.0_labels.txt     # 模型标签
│   │   ├── java/com/example/android/camera2/
│   │   │   ├── utils/                    # 共享工具类（原各模块 utils 库合并）
│   │   │   │   ├── AutoFitSurfaceView.kt
│   │   │   │   ├── CameraSizes.kt
│   │   │   │   ├── ExifUtils.kt
│   │   │   │   ├── GenericListAdapter.kt
│   │   │   │   ├── OrientationLiveData.kt
│   │   │   │   ├── Yuv.kt
│   │   │   │   └── YuvToRgbConverter.kt
│   │   │   └── integration/              # 整合后的全部示例
│   │   │       ├── MainActivity.kt       # 统一主菜单（入口）
│   │   │       ├── SampleAdapter.kt      # 主菜单列表适配器
│   │   │       ├── basic/                # Camera2Basic
│   │   │       ├── extensions/           # Camera2Extensions
│   │   │       ├── slowmo/               # Camera2SlowMotion
│   │   │       ├── video/                # Camera2Video
│   │   │       ├── advanced/             # CameraXAdvanced (TFLite)
│   │   │       ├── cameraxbasic/         # CameraXBasic
│   │   │       ├── cameraxvideo/         # CameraXVideo
│   │   │       ├── mlkit/                # CameraX-MLKit
│   │   │       ├── cameraxext/           # CameraXExtensions (Compose)
│   │   │       └── hdr/                  # HdrViewfinder (Java)
│   │   └── res/                          # 统一资源（按模块前缀命名）
│   │       ├── layout/  layout-land/     # 各模块布局（如 basic_activity_camera.xml）
│   │       ├── drawable/  drawable-*/    # 图标与矢量图
│   │       ├── values/                   # 按模块拆分的 strings/dimens/colors/styles
│   │       ├── navigation/               # 各 Navigation 模块的 nav_graph
│   │       ├── menu/  color/  mipmap-*/  xml/
│   └── build/                            # 构建产物
├── gradle/  gradle.properties  settings.gradle  local.properties
└── README.md
```

### 包结构说明

- **`com.example.android.camera.utils`**：所有模块共用的底层工具（SurfaceView 自适应、EXIF、YUV 转换、方向监听等）。原各独立工程的 `utils` 模块统一合并到此处。
- **`com.example.android.camera2.integration.<模块>`**：每个示例的代码，目录名与上方表格一一对应。
- **`com.example.android.camera2.integration`**（根）：主菜单 `MainActivity` 与列表适配器 `SampleAdapter`。

### 资源命名约定

为避免多模块资源冲突，所有布局/字符串/尺寸等资源按模块加前缀：

| 模块 | 布局前缀示例 | 字符串文件 |
|------|--------------|-----------|
| basic | `basic_activity_camera.xml` | `basic_strings.xml` |
| extensions | `ext_activity_camera.xml` | `ext_strings.xml` |
| slowmo | `slowmo_activity_camera.xml` | `slowmo_strings.xml` |
| video | `video_activity_camera.xml` | `video_strings.xml` |
| advanced | `adv_activity_camera.xml` | `adv_strings.xml` |
| cameraxbasic | `cx_activity_main.xml` | `cx_strings.xml` |
| cameraxvideo | `cxv_activity_main.xml` | `cxv_strings.xml` |
| mlkit | `mlkit_activity_main.xml` | （内联/共享） |
| cameraxext | `cxext_activity_main.xml` | `cxext_strings.xml` |
| hdr | `hdr_main.xml` | `hdr_strings.xml` |

---

## 代码架构与流程图

### 1. 整体启动流程

```
                  ┌─────────────────────────────┐
   用户点击桌面图标 │  MainActivity (统一启动器)    │
                  └──────────────┬──────────────┘
                                 │ setContentView(R.layout.activity_main)
                                 │ 构建 SampleItem 列表（11 个模块）
                                 ▼
                  ┌─────────────────────────────┐
                  │  RecyclerView (2 列网格)      │
                  │  SampleAdapter               │
                  └──────────────┬──────────────┘
                                 │ 点击卡片 → startActivity(Intent)
              ┌──────────────────┼──────────────────────────────────────┐
              │                  │                                        │
              ▼                  ▼                                        ▼
     basic.CameraActivity  extensions.CameraActivity  ...  hdr.HdrViewfinderActivity
              │                  │                                        │
              ▼                  ▼                                        ▼
     各模块内部 Fragment 导航 / 直接预览                               直接预览
```

- `MainActivity` 在 `onCreate` 中通过 `import ... as` 别名收集 11 个模块的入口 `Class<?>`，放入 `SampleItem` 列表。
- `SampleAdapter` 负责渲染卡片并在点击时 `startActivity`。

### 2. 典型 CameraX 模块内部流程（以 CameraXBasic 为例）

```
cameraxbasic.MainActivity
        │
        ▼ 启动 nav_graph (cx_nav_graph.xml)
┌──────────────────┐   action   ┌──────────────────┐   action   ┌──────────────────┐
│ PermissionsFragment│ ───────▶ │   CameraFragment  │ ───────▶ │  GalleryFragment  │
│ (申请 CAMERA 权限) │           │ (预览/拍照/分析)  │           │  (查看已拍照片)   │
└──────────────────┘           └──────────────────┘           └──────────────────┘
                                          │
                                          ▼
                                 ProcessCameraProvider
                                 绑定 Preview / ImageCapture / ImageAnalysis
```

### 3. 典型 Camera2 模块内部流程（以 Camera2Video 为例）

```
video.CameraActivity
        │
        ▼ 启动 nav_graph (video_nav_graph.xml) → SelectorFragment → PreviewFragment
┌──────────────────┐   ┌──────────────────────────────────────────────┐
│ SelectorFragment │   │ PreviewFragment                                │
│ (选择录制参数)    │──▶│  通过 HardwarePipeline / SoftwarePipeline       │
└──────────────────┘   │  绑定 Camera2 会话 + EGL 渲染 + MediaCodec 编码 │
                       └──────────────────────────────────────────────┘
                                        │
                          EncodeApi / Codec / DynamicRange / Filter /
                          ColorSpace / Transfer / Stabilization / RecordMode
                          （11 个参数调节 Fragment）
```

### 4. HDR Viewfinder 处理流程（Java）

```
hdr.HdrViewfinderActivity
        │
        ├─ 打开两个 Camera2 输出 Surface（HDR / Normal）
        ├─ ViewfinderProcessor（Java 重写，原 RenderScript 内核）
        │     ├─ 接收 YUV 帧 (Allocation ioReceive)
        │     ├─ mergeHdrFrames() 做 HDR 平均 / 分屏合成 / YUV→RGB
        │     └─ 输出到显示 Surface (Allocation ioSend)
        └─ 用户滑动调节左右半屏手动曝光
```

> 注：原 `HdrViewfinder` 使用 RenderScript `.rs` 内核，但因新版 Android Gradle Plugin 已移除 RenderScript 编译器，整合版在 `ViewfinderProcessor.java` 中用**纯 Java 重新实现了同样的融合/色彩转换算法**，仍借助 AndroidX RenderScript `Allocation` 做零拷贝传输。

---

## 各模块技术要点

- **Camera2Basic**：演示 `CameraDevice` / `CaptureSession` / `ImageReader` 的 JPEG、RAW、DEPTH 输出；通过 `SelectorFragment` 选择相机与像素格式。
- **Camera2Extensions**：基于 `CameraExtensionCharacteristics` 查询并启用厂商扩展（夜景、散景、HDR、人脸美颜、自动），动态切换预览/拍照扩展。
- **Camera2SlowMotion**：构造高速 `CaptureRequest`，在受支持的设备上录制慢动作片段。
- **Camera2Video**：最复杂模块。`HardwarePipeline`（EGL + `MediaCodec` Surface 编码）与 `SoftwarePipeline`（CPU 拷贝）两套管线；`SelectorFragment` 可切换编码 API、编解码器、动态范围、滤镜、色彩空间、传输方式、预览防抖、录制模式。
- **CameraXBasic**：展示 CameraX 标准用法：`Preview` + `ImageCapture` + `ImageAnalysis`，含图库浏览与权限请求。
- **CameraXAdvanced (TFLite)**：用 `ImageAnalysis` 拿到帧，交给 TensorFlow Lite `Interpreter`（可选 NNAPI 加速）做 COCO SSD 目标检测，叠加检测框。
- **CameraXVideo**：使用 CameraX `VideoCapture` 录制，含权限、录制控制、视频回看。
- **CameraX-MLKit**：使用 CameraX `MlKitAnalyzer` + `BarcodeScanning` 实时识别二维码并绘制到 `QrCodeDrawable`。
- **CameraXExtensions**：用传统 Android View 体系（`AppCompatActivity` + `RecyclerView` + ViewBinding）实现 UI，`CameraExtensionsViewModel` 管理扩展会话状态，配合 Coil 加载扩展图标，`CameraExtensionsApplication` 提供自定义 `ImageLoader` 与 `CameraXConfig`。
- **HdrViewfinder**：手动曝光 + 双流合成实现实时 HDR 取景器。

---

## 资源与依赖整合说明

- **依赖统一**：`app/build.gradle` 集中声明所有模块所需依赖（CameraX 1.4.1 全套、ML Kit Barcode、TensorFlow Lite、Jetpack Compose、Coil、Dynamic Animation、EGL `graphics-core`、Glide、ExifInterface、Window 等）。
- **工具类统一**：原各模块的 `utils` 库合并为 `com.example.android.camera.utils`。
- **FileProvider**：三个模块（`Camera2Video`/`Camera2SlowMotion`/`CameraXExtensions`）拍照/录像后需要把媒体通过 `content://` URI 分享给其它应用。整合工程在 `AndroidManifest.xml` 中注册了**全局唯一的 `FileProvider`**（authority = `${applicationId}.provider`），并配以 `res/xml/file_paths.xml`。
- **Application**：`CameraExtensionsApplication` 被注册为全局 `Application`，提供 CameraX 配置与 Coil 自定义 `ImageLoader`。
- **TFLite 资产**：`assets/` 下的模型与标签文件直接被 `CameraXAdvanced` 加载。

---

## 构建与运行

### 环境要求

- Android SDK（API 34 编译，minSdk 21）
- JDK 17+（本工程 `gradle.properties` 中通过 `org.gradle.java.home` 指定，例如 `D:\lyh\java\jdk-21`）
- `local.properties` 中 `sdk.dir` 指向本机 Android SDK 目录

### 构建

```bash
cd Camera2
./gradlew assembleDebug      # 生成 debug APK
```

首次构建会下载 Gradle 与依赖（CameraX、Compose、TFLite 等体积较大，请确保网络通畅或已配置代理/Gradle 缓存）。

### 运行

将生成的 APK 安装到设备或模拟器，点击桌面图标进入主菜单，选择任意示例即可。

---

## 已知限制

- **模块间 Application 配置取舍**：整合工程仅注册 `CameraExtensionsApplication` 为全局 `Application`。`CameraXBasic` / `CameraXVideo` 原本各自的 `MainApplication`（`CameraXConfig.Provider`）未单独注册，CameraX 使用统一配置运行，不影响功能。
- **图标**：统一使用 `mipmap-*` 下的 `ic_launcher.png`，未引入各源工程的 `ic_launcher_round` 自适应图标变体。
- **HDR Viewfinder** 的 RenderScript 内核已在 Java 层重写，算法等价，但不再依赖 `.rs` 编译。
- 各模块共享同一个 `applicationId`，因此它们在设备上表现为同一个 App 内的不同页面，而非独立 App。

---

## 原示例来源

本整合工程的内容来自 AOSP `camera-samples` 仓库中的独立示例，版权归 The Android Open Source Project 所有，遵循 Apache License 2.0。
