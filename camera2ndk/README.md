
Android Camera2Raw Sample
=========================

This repo has been migrated to [github.com/android/camera][1]. Please check that repo for future updates. Thank you!

[1]: https://github.com/android/camera

---

## 项目简介

基于 Android Camera2 API + OpenCV (NDK) 的相机应用，实现实时图像处理和多帧降噪拍照。

## 核心功能

- **Camera2 相机控制** — 前后摄像头切换、闪光灯（关/开/自动）、手动点击对焦、缩放
- **ISO 自适应多帧降噪** — 根据当前 ISO 值自动选择 1-6 帧连拍，通过 ECC 配准 + 加权融合 + NLM 精修实现降噪
- **YuNet 实时人脸检测** — 基于 OpenCV FaceDetectorYN，在预览画面中绘制人脸框与 10 个关键点
- **7 种图像处理算法** — 人脸检测、降噪、锐化、人像虚化(Bokeh)、HDR 色调映射、CLAHE 对比度增强、饱和度调整
- **双 Pipeline 架构** — PreviewPipeline（低延迟实时处理）与 CapturePipeline（高质量拍照处理）分离
- **HardwareBuffer 零拷贝** — Android 8.0+ 使用 AHardwareBuffer 实现帧数据零拷贝传输
- **视频录制** — MediaRecorder 录像，支持麦克风音频
- **拍照定时器** — 3 秒 / 10 秒倒计时
- **画幅比例切换** — FULL / 1:1 / 16:9 / 4:3
- **构图辅助线** — 三分法、黄金分割、对角线三种网格模式

## 技术栈

| 层级 | 技术 |
|---|---|
| Android UI | Java, Camera2 API, TextureView, Fragment |
| 图像处理 | C++17, OpenCV 4, libjpeg-turbo |
| 构建系统 | Gradle 8.5.0 + CMake 3.22.1 |
| 目标平台 | Android SDK 21-34, arm64-v8a / armeabi-v7a |

## 项目结构

```
Application/src/main/
├── java/com/opencv/camera/    # Java 层：UI、相机控制、JNI 桥接
│   ├── CameraFragment.java      # 核心界面：拍照/录像/人脸检测/缩略图
│   ├── CameraEngine.java        # Camera2 API 封装
│   ├── NativeEngine.java        # JNI 单例，暴露 native 方法
│   └── ...
└── cpp/
    ├── include/camera_engine/   # 纯 C 跨平台 API 头文件
    └── src/
        ├── api/                 # C API 接口层 + Android 平台适配
        ├── algorithms/          # 算法模块（人脸检测、降噪、锐化、虚化、HDR、CLAHE、饱和度）
        ├── pipeline/             # Pipeline 管理（预览/拍照管线）
        ├── core/                 # 帧封装、类型定义、元数据
        ├── encode/               # JPEG 编码
        ├── platform/android/     # AHardwareBuffer 封装
        └── bindings/jni/        # JNI 桥接
```
