# Android Camera2 整合示例（Basic + Video + Raw）

一个统一的 AndroidX Java 项目，将 Google 官方的三个 Camera2 示例整合到同一个应用中：

- **Camera2Basic** —— JPEG 拍照，含 3A（AF/AE/AWB）状态机与预览缩放。
- **Camera2Video** —— 使用 `MediaRecorder` 录制 MP4 视频（H.264 + AAC）。
- **Camera2Raw** —— 同时输出 DNG（RAW_SENSOR）与 JPEG 双流，使用 `DngCreator` 保存 RAW，含引用计数资源管理与设备能力检测。

通过底部导航栏在三种模式之间切换，每种模式对应一个独立 Fragment。

## 技术栈

- 语言：**Java**
- 构建：**Gradle 7.5.1 + AGP 7.4.2**，编译兼容 **Java 11**
- 依赖：**AndroidX**（Fragment / FragmentActivity / AppCompat）、**Material Design**（BottomNavigationView）
- 最低 SDK：21（Android 5.0，Camera2 起点）
- 相机 API：`android.hardware.camera2`

## 工程结构

```
android-Camera/
├── build.gradle                         # 项目级构建配置
├── settings.gradle
├── gradle.properties
├── gradle/                              # Gradle Wrapper 7.5.1
├── gradlew / gradlew.bat
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # 合并 CAMERA / RECORD_AUDIO / 存储权限
│       ├── java/com/example/android/camera2all/
│       │   ├── MainActivity.java              # BottomNavigationView 切换三模式
│       │   ├── Camera2BasicFragment.java       # 拍照 + 3A 状态机
│       │   ├── Camera2VideoFragment.java       # 视频录制
│       │   ├── Camera2RawFragment.java         # DNG + JPEG 双流
│       │   ├── AutoFitTextureView.java         # 按比例自适应预览 View
│       │   ├── CompareSizesByArea.java         # 选择最佳预览尺寸
│       │   ├── RefCountedAutoCloseable.java    # 引用计数资源封装
│       │   ├── ImageSaver.java                 # 后台线程保存图片
│       │   ├── ErrorDialog.java                # 错误提示对话框
│       │   ├── ConfirmationDialog.java         # 权限说明对话框
│       │   └── CameraConstants.java            # 权限常量定义
│       └── res/
│           ├── layout/                    # 主界面 + 三个 Fragment 布局
│           ├── layout-land/               # 横屏布局
│           ├── menu/navigation.xml        # 底部导航菜单
│           ├── values/                    # strings / styles / colors
│           └── drawable-{h,m,xh,xxh}dpi/  # 图标资源
```

## 运行

1. 使用 Android Studio 打开 `android-Camera/` 目录。
2. 连接支持 Camera2 的 Android 设备（或配置 Camera2 模拟器）。
3. 点击 **Run** 安装并运行 `app` 模块。
4. 首次启动会请求相机 / 麦克风 / 存储权限，请全部授予。

## 使用说明

- 底部导航栏三个标签页对应 **Basic / Video / Raw**。
- 切换标签时当前 Fragment 会被 `replace`（触发 `onPause` 释放相机、`onResume` 重新打开），避免多 Fragment 同时占用相机设备。
- **Basic**：点击快门按钮拍照，JPEG 保存到应用私有目录的 DCIM 文件夹。
- **Video**：点击按钮开始 / 停止录制，MP4 保存到应用私有目录的 Movies 文件夹。
- **Raw**：点击快门同时保存 `.dng`（RAW）与 `.jpg`（预览 JPEG）到应用私有目录的 DCIM 文件夹；该模式会检测设备 RAW 能力，Legacy 级别设备会给出提示。

## 存储说明（Scoped Storage）

为兼容 Android 10+ 的分区存储，所有输出文件使用应用私有目录：

- 照片：`Context.getExternalFilesDir(Environment.DIRECTORY_DCIM)`
- 视频：`Context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)`

可通过 USB / 文件管理器在以下路径访问：

```
Android/data/com.example.android.camera2all/files/DCIM/
Android/data/com.example.android.camera2all/files/Movies/
```

如需保存到公共媒体库，可改用 `MediaStore` API（示例为保持与原官方 Demo 行为一致，沿用私有目录方案）。

## 整合要点

- 三个官方示例原本各有独立的 Java / Kotlin 子工程，本项目统一为单一 **AndroidX + Java** 工程，包名 `com.example.android.camera2all`。
- 抽取公共组件（`AutoFitTextureView`、`CompareSizesByArea`、`ImageSaver`、`ErrorDialog`、`ConfirmationDialog`、权限常量）供三个 Fragment 复用。
- 合并 `AndroidManifest.xml` 权限声明与 `build.gradle` 依赖。
- 权限请求流程统一：Fragment 发起 `requestPermissions`，并在 `onRequestPermissionsResult` 中授权后重新打开相机；`ConfirmationDialog` 自身直接请求权限，规避 `getParentFragment()` 空指针风险。

## 已知限制

- `ImageSaver` 仍使用已废弃的 `AsyncTask.THREAD_POOL_EXECUTOR`，以保持与原官方示例一致；生产环境建议替换为 `ExecutorService` / `_coroutine` / Kotlin 协程。
- RAW 输出仅 `LEVEL_3` / `FULL` 级别设备支持；`LEGACY` 设备不具备 RAW 能力。
- 未实现实时滤镜、多摄像头切换、人像 / 夜景等高级特性。
