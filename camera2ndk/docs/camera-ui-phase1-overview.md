# 相机 UI 改造 · Phase-1 概览（纯 UI / 传感器缺口补齐）

> 仓库：`Application/`（Java + XML Views，非 Jetpack Compose）。本阶段只改 UI 与传感器接线，**不触碰 Camera2 采集管线**，风险低。

## 已完成的五项功能

### 1. 陀螺仪实时水平仪（Task #7）
- `CameraOverlayView`：已实现 `drawLeveler()` —— 仅当设备倾斜 `|roll| > 0.5°` 才绘制，倾斜 `< 2°` 显示绿色、否则红色参考线，跟随取景框旋转。
- `CameraFragment`：注册 `TYPE_GRAVITY`（无则回退 `TYPE_ACCELEROMETER`）传感器；`onResume`/`onPause` 配对注册/注销；`updateRollFromGravity()` 依据屏幕旋转换算 roll 角度并 `setRoll()` 注入 overlay。默认开启。

### 2. 左右滑动切换拍摄模式（Task #8）
- `GestureDetector.onFling`：水平滑动（`|dx| > 80` 且 `|velocityX| > 300`）左滑→视频、右滑→照片，复用 `selectMode()`（自带录制中/拍摄中保护），并触发 `VIRTUAL_KEY` 触觉。双指捏合缩放不受影响。

### 3. 音量键作为物理快门（Task #9）
- `MainActivity.dispatchKeyEvent`：拦截 `VOLUME_DOWN/UP`（仅 `ACTION_DOWN` 且 `repeatCount == 0`，且返回栈为空即非设置页），转发 `CameraFragment.triggerVolumeShutter()` → `onShutterClick()`（照片=拍照、视频=开始/停止录制）。

### 4. 顶部独立 HDR 开关（Task #10）
- 矢量图标 `res/drawable/ic_hdr_auto.xml`（本次补建）。
- `fragment_camera.xml` 顶栏已布局 `btn_hdr` / `hdr_label`（关 / 自动 / 开 三态）。
- `CameraFragment` 增加 `HDR_OFF / HDR_AUTO / HDR_ON` 三态 `cycleHdr()` / `updateHdrUi()`，并把 `buildSessionControl()` 中 `preferHdr` 从 `isAiEnabled` **解耦**为 `hdrMode != HDR_OFF`。AI 开关仍独立控制 CLAHE / Saturation / 人脸等。

### 5. 变焦预设 1× / 2× / 5×（Task #11）
- `fragment_camera.xml` 底部新增 `zoom_presets` 横排三档（1× / 2× / 5×）。
- `CameraFragment` 新增 `applyZoom()` 直接调用既有 `CameraEngine.setZoom()`（数字变焦，min 1.0、上限 `maxDigitalZoom`，超出自动 clamp）；`syncZoomPresets()` 按当前 zoom 高亮激活档；捏合 `onScale` 中也调用 `syncZoomPresets()` 保持同步。
- 说明：`CameraEngine.setZoom` 仅支持 ≥1.0 数字变焦，故未提供 0.6× 广角档。

## 本轮编译 Bug 修复（关键）
1. **漏 import**：`CameraFragment` 用到 `Context.SENSOR_SERVICE` 但未 `import android.content.Context;`（仓库原本只 import 了 Intent/SharedPreferences/pm）。已补。
2. **漏建资源**：`updateHdrUi()` 引用 `R.drawable.ic_hdr_auto`，但 `ic_hdr_auto.xml` 此前漏建。本次补建。该问题在增量构建里因 `processDebugResources` 被缓存跳过而没暴露，**clean 构建必报资源缺失**——本轮新增 `zoom_preset_*` 资源 ID 会强制资源重生，正好把 `ic_hdr_auto` 一并纳入 `R.java`。

## 改动文件清单
| 文件 | 改动 |
|---|---|
| `Application/.../CameraFragment.java` | HDR 三态、陀螺仪传感器、onFling 滑动切模式、triggerVolumeShutter()、变焦预设 applyZoom/syncZoomPresets、补 import Context |
| `Application/.../MainActivity.java` | dispatchKeyEvent 拦截音量键→物理快门、补 import KeyEvent |
| `Application/.../CameraOverlayView.java` | 已含 drawLeveler / setRoll / setLevelerEnabled（更早一轮） |
| `Application/res/layout/fragment_camera.xml` | 顶栏 HDR 按钮布局 + 底部变焦预设 + 扁平化（更早一轮） |
| `Application/res/drawable/ic_hdr_auto.xml` | 新增 HDR 自动图标 |
| `Application/res/values/strings.xml` | hdr_off / hdr_on / hdr_auto（更早一轮） |

## 待办（需改 CameraEngine 采集管线，风险较高，未在本环境实现）
- ~~EV 曝光滑块~~ ✅ 已完成（第三轮）
- ~~人像第三模式~~ ✅ 已完成（第四轮：复用 `CameraEngine.MODE_PHOTO` + `SessionControl.preferBokeh=true`，无新采集模式）
- ~~专业模式面板（ISO/快门/EV/对焦/WB + 实时直方图）~~ ✅ 已完成（第四轮：`ArcSliderView` 弧线滑块 + `HistogramView` + `CameraEngine` 手动控制 setter）
- 上滑/下滑 More Sheet（定时器 / 滤镜 / 美颜）—— 定时器已有，More Sheet 外壳未做
- 动态 AI 场景 chip（人像/夜景/文档）
- 折叠屏 / 平板响应式布局（WindowSizeClass + FoldingFeature）

## ⚠️ 编译验证
本环境未安装 Android SDK / NDK，**无法在此编译验证**，但已定位并修复上一轮 `assembleDebug` 的两个真实报错（缺 `Context` import、缺 `ic_hdr_auto` 资源）。请在本地执行一次 **clean** 构建以确保资源重生：
```
cd Application && ../gradlew clean assembleDebug
```
所有改动仅涉及 UI 控件绑定、传感器、手势、既有 `setZoom` API，未触及 Camera2 capture 逻辑。

---

## 第二轮：UI Bug 审查修复（用户要求"仔细审查 uibug 完善修复"）

静态通读 `CameraFragment(1594行)` / `fragment_camera.xml` / `CameraOverlayView` / `MainActivity` / `colors` / `styles` 后，修复以下**真实 bug**（非隐患、非风格）：

### Bug-1 布局重叠：处理指示器压住变焦预设
`processing_indicator` 与 `zoom_presets` 原本都锚 `bottom_toTopOf="@id/mode_container"`，导致二者 + `zoom_label` 在垂直方向互相叠压（变焦档位、处理转圈、变焦提示三者糊在一起）。
**修复**：`processing_indicator` 改锚 `bottom_toTopOf="@id/zoom_presets"`，形成清晰竖向链：
`bottom_bar ← mode_container ← zoom_presets ← processing_indicator ← zoom_label`。

### Bug-2 窄屏顶栏重叠：AI 按钮压住 HDR
`btn_ai` 原居中父布局（`start/end_toStartOf=parent`）。加入 HDR 键后左组变宽，窄屏（≈360dp）上 AI 会和 HDR 重叠。
**修复**：`btn_ai` 约束改为 `start_toEndOf="@id/top_left_group"` + `end_toStartOf="@id/top_right_group"`，夹在左右两组之间居中；`iso_label` 仍锚 `btn_ai`，自动跟随。

### Bug-3 手势冲突：捏合缩放误触切换模式
两指捏合缩放时，抬其中一指，剩余手指的位移会被 `GestureDetector` 当成横向 fling，意外切到照片/视频模式。
**修复**：新增 `scalingInProgress`（在 `ScaleGestureDetector.onScaleBegin/onScaleEnd` 置位）+ `lastScaleEndTime`，`onFling` 开头拦截「缩放进行中」与「缩放结束 250ms 内」的 fling。

### Bug-4 切换模式/从后台返回时控制栏闪烁
`onCameraOpened` 每次开相机都会调用 `animateControlsIn()`，把顶/底栏 alpha 重置为 0 再淡入——**每次切模式、每次从后台回来控制栏都会闪一下**。
**修复**：新增 `controlsShown` 守卫，仅首次淡入，之后只确保 `alpha=1` 可见，不再重播淡入动画。

### 清理
- 删除 `fragment_camera.xml` 末尾 3 个空 `<Guideline>` + 2 个空 `<Barrier>` 无效占位（无约束引用，纯噪音）。

### 校验结论
- `getCurrentZoom / setZoom / getMaxZoom` 均在 `CameraEngine` 且 `setZoom` 内部已 clamp；`applyZoom` 读回钳制值正确。
- `colors.xml` 所有引用色（`gc_accent`/`gc_primary` 等）齐备；`ic_hdr_auto`/`bg_ai_top_button` 合法。
- 新增字段 `scalingInProgress` / `lastScaleEndTime` / `controlsShown` 定义与引用一致，无新增未定义符号。
- 全部改动仍停留在 UI / 布局 / 手势层，**未触碰 Camera2 采集管线**，编译风险低。

### 本轮刻意未改（保留既有行为）
- 原仓库 `onResume` 会再次 `openCameraWithPermission`（与 `onViewCreated` 已开相机叠加）属既有逻辑，未动。
- HDR / AI 在录制中推 `updateSessionControl` 与既有 AI 行为一致，未额外加录制守卫（避免改变可用行为）。

---

## 第三轮：EV 滑块 + 长按 AF/AE 锁定（spec 第3点预览层交互）

本轮开始触碰 `CameraEngine` 采集会话，但仅**增量新增**公开方法，沿用既有 `setRepeatingRequest` 模式，不重构既有拍照/录像流程。

### 新增：垂直 EV 曝光滑块
- 新增自定义 `EvSliderView`（继承 `View`，自绘竖线轨道 + accent 圆形手柄，触摸精确——**不用旋转 SeekBar**，规避 Android 上旋转后触摸热区不跟随导致的拖动错位 bug）。
- `CameraEngine` 新增 `setExposureCompensation(int)` / `getExposureCompensation()` / `getExposureCompensationRange()`，经 `CONTROL_AE_EXPOSURE_COMPENSATION` 下发，范围由 `CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE` 钳制。
- `fragment_camera.xml` 右侧新增 `ev_slider`(EvSliderView) + `ev_label`；仅照片模式可见（录像模式隐藏，由 `updateEvSliderVisibility()` 控制）。
- `onCameraOpened` 按设备真实 EV 范围初始化滑块并恢复当前值；拖动实时调 EV 并显示 `EV ±x.x`。

### 新增：长按锁定 AF/AE
- `CameraEngine` 新增 `lockAe()` / `unlockAe()` / `isAeLocked()`，经 `CONTROL_AE_LOCK` 下发（复用仓库既有 AE 锁逻辑）。
- `GestureDetector` 补 `onDown` 返回 `true`（否则 `onLongPress` 永不触发）+ `onLongPress`：长按点 `focusOnPoint` + `lockAe()`，显示 `ae_lock_hint`（AE/AF LOCKED 绿标）+ `LONG_PRESS` 触觉。
- 解锁交互：**点按对焦时若已锁定则先解锁**（onSingleTapUp 里 `unlockAe` + 隐藏提示）再重新对焦——"长按锁、点按解并重构图"。
- 切视频模式（`updateModeUi`）自动 `unlockAe` 并隐藏提示。

### 文件清单（本轮新增/改动）
| 文件 | 改动 |
|---|---|
| `Application/.../CameraEngine.java` | 新增 EV 补偿三方法 + AE 锁定三方法（沿用 setRepeatingRequest 模式，未改既有流程） |
| `Application/.../EvSliderView.java` | 新增自定义垂直 EV 滑块 View |
| `Application/.../CameraFragment.java` | onDown/onLongPress、EV 滑块绑定与可见性、点按解锁 AE、onCameraOpened 初始化 EV 范围 |
| `Application/res/layout/fragment_camera.xml` | ev_slider / ev_label / ae_lock_hint 三控件 |
| `Application/res/values/strings.xml` | ev_0 / ae_af_locked |

---

## 第四轮：四模式 + 专业模式面板（核心模式全部自主完善）

用户指令："不用问我了 全部自己完善 核心模式 照片 人像 video 专业模式 功能全部自主完善修复 有 bug 自己修复"。本轮一次性补齐四个核心模式（照片 / 人像 / 视频 / 专业）并修复发现的 bug，全程不中断用户。

### 关键架构决策（低风险）
- **人像模式 ≠ 新采集模式**：经核对 `startCameraSession()` 把非视频模式统一映射为 `CameraEngine.MODE_PHOTO`，故人像仅需在 `buildSessionControl()` 置 `control.preferBokeh = uiMode == MODE_PORTRAIT;`（SessionControl 已有 `preferBokeh` 字段，native 层走 bokeh 虚化），**不新增任何 Capture 模式常量、不碰拍照/录像流程**。
- **专业模式 = 照片模式 + 手动 setter**：`MODE_PRO` 仍走 `CameraEngine.MODE_PHOTO` 采集会话，仅叠加手动控制（`CONTROL_AE_MODE_OFF` + 手动 ISO/快门 + 手动 WB + 手动对焦距离）。离开 Pro 时自动 `setAutoExposure()+setAutoFocus()` 复位，避免状态污染。

### CameraEngine.java（Task #13，新增手动控制能力）
- 新增字段：`manualIso`(默认100) / `manualShutterNs`(默认 ~1/30s) / `manualFocusDistance`(0=连续对焦) / `awbMode`(默认 AUTO) / `manualExposure` / `manualFocus` 布尔。
- 新增公开方法（纯增量，沿用 `setRepeatingRequest` 模式）：
  - `getIsoRange()` ← `SENSOR_INFO_SENSITIVITY_RANGE`
  - `getShutterRange()` ← `SENSOR_INFO_EXPOSURE_TIME_RANGE`
  - `getMinimumFocusDistance()` ← `LENS_INFO_MINIMUM_FOCUS_DISTANCE`
  - `getAvailableAwbModes()` ← `CONTROL_AWB_AVAILABLE_MODES`
  - `setManualExposure(int iso, long shutterNs)` / `setAutoExposure()` / `isManualExposure()` / `getManualIso()` / `getManualShutterNs()`
  - `setManualFocusDistance(float)` / `setAutoFocus()` / `isManualFocus()` / `getManualFocusDistance()`
  - `setWhiteBalance(int)` / `getWhiteBalance()`
- `applyCommonPreviewControls()` 末尾注入 `applyManualControlsLocked()`（手动设置随模式切换 / 重新预览存活）。
- 新增 `applyManualControls()`(重发 setRepeatingRequest) / `applyManualControlsLocked()` / `applyManualControlsToBuilder()`。
  - `applyManualControlsLocked()` 逻辑：手动曝光 → `CONTROL_AE_MODE_OFF` + `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME` + `FLASH_MODE_OFF`；否则 `CONTROL_AE_MODE_ON` + `CONTROL_AE_EXPOSURE_COMPENSATION`。WB 恒为 `awbMode`。手动对焦 → `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`；否则 `CONTROL_AF_MODE_CONTINUOUS_PICTURE`。
  - **静态+视频拍照落盘**：still-capture burst 的每条 request 在 build 前调用 `applyManualControlsToBuilder(builder)`，确保 Pro 曝光参数写入成片（不止预览）。

### ArcSliderView.java + HistogramView.java（Task #14，新增）
- `ArcSliderView`（继承 `View`，自绘弧形/弓形竖向滑块，accent #6EA8FE）：`setRange(min,max)` / `setLogarithmic(bool)`（ISO/快门用对数刻度，EV/对焦线性）/ `setFormatter(ValueFormatter)` / `setOnArcChangeListener` / `setValue` / `getFormattedValue`。触摸按 Y 坐标映射（命中判定稳健，不依赖 X）。
- `HistogramView`（继承 `View`）：`setHistogram(int[256])`，按最大值归一化绘制填充+描边面积图。

### fragment_camera.xml（Task #15）
- `mode_container` 由 2 档扩为 **4 档**：`mode_photo`(照片) / `mode_portrait`(人像) / `mode_video`(视频) / `mode_pro`(专业)，档间距 28dp→22dp。
- 新增 `pro_panel`（`bg_flash_options` 背景，`visibility=gone`，`constraintBottom_toTopOf=mode_container`）：
  - `histogram_view`(HistogramView, 56dp)
  - `pro_chips` 横排 5 个 chip：`pro_chip_iso` / `pro_chip_shutter` / `pro_chip_ev` / `pro_chip_focus` / `pro_chip_wb`
  - `pro_slider_row`（`visibility=gone`，含 `pro_arc`(ArcSliderView 56×220dp) + `pro_value` + `pro_auto`）

### strings.xml
- 新增：mode_portrait(人像) / mode_pro(专业) / pro_iso(ISO) / pro_shutter(快门) / pro_ev(EV) / pro_focus(对焦) / pro_wb(白平衡) / pro_auto(自动)

### CameraFragment.java（Task #16，接线）
- 常量：`MODE_PORTRAIT=2` / `MODE_PRO=3`；字段：`proPanel / proSliderRow / histogramView / proArc / proValue / proAuto / modePortrait / modePro / proChips[5] / activeProParam / awbCycleIndex / histogramLastTime / zoomPresets`。
- `initViews` 绑定全部 Pro 面板控件；`proChips[i]→selectProParam(i+1)`；`proAuto→applyProAuto()`；`proArc→onProArcChanged`；`zoomPresets=findViewById(R.id.zoom_presets)`。
- `setupModeSelector`：`modePortrait→selectMode(MODE_PORTRAIT)`、`modePro→selectMode(MODE_PRO)`。
- `selectMode` 重写：记录 `prevMode`；离开 Pro（`prevMode==MODE_PRO`）时 `setAutoExposure()+setAutoFocus()` 复位；`modeName()` 辅助。
- `updateModeUi` 重写：4 档循环设 `ModeSelectorText_Selected`；`modeIndicator` 移到激活档；视频隐藏计时器；Pro 显示 `proPanel` 且隐藏 `zoomPresets`；离开照片/人像解锁 AE。
- `updateEvSliderVisibility`：照片 **或** 人像显示 EV 滑块（Pro 改用面板 EV）。
- `buildSessionControl`：`control.preferBokeh = uiMode == MODE_PORTRAIT;`
- Pro 方法：`selectProParam(int)`（按参数类型从 `CameraEngine` 取真实 range 配置 `proArc`：ISO/快门对数、EV/对焦线性、WB 切 cycle）/ `onProArcChanged(double)`（调对应 setter + `updateProValueLabel`）/ `applyProAuto()`（复位手动/自动 + 刷新标签）/ `cycleWhiteBalance()` / `updateProValueLabel()` / `formatShutter(long)` / `awbName(int)`。
- 🐞 **修复预览 ImageReader 内存泄漏**：`previewImageListener` 在 `faceBusy` CAS 竞争失败的早期 `return` 分支原本没有 `image.close()`，导致 YUV Image 泄漏。已在 `return` 前补 `image.close()`；并把 `updateHistogram(image,w,h)` 调用提前到 `image.close()` **之前**（先喂直方图再释放）；`finally` 仍保底 `image.close()`。`updateHistogram` 采样 plane0(Y) 256 bin，节流 80ms，仅 `uiMode==MODE_PRO` 时 `post` 给 `histogramView.setHistogram()`。

### 文件清单（本轮新增/改动）
| 文件 | 改动 |
|---|---|
| `Application/.../CameraEngine.java` | 手动控制字段 + 17 个公开方法 + applyManualControls{}/Locked/ToBuilder；still-capture 应用手动参数 |
| `Application/.../ArcSliderView.java` | 新增自定义弧形滑块 View |
| `Application/.../HistogramView.java` | 新增实时直方图 View |
| `Application/.../CameraFragment.java` | 四模式 + Pro 面板全部接线 + 预览泄漏修复 |
| `Application/res/layout/fragment_camera.xml` | 四模式标签 + `pro_panel` 完整布局 |
| `Application/res/values/strings.xml` | 8 个新字符串 |

### 静态校验结论（本环境无 SDK，无法编译，仅符号/引用一致性核对）
- 所有新符号（`proPanel/proArc/histogramView/selectProParam/onProArcChanged/applyProAuto/cycleWhiteBalance/updateProValueLabel/formatShutter/awbName/updateHistogram/MODE_PORTRAIT/MODE_PRO` 等）在 `CameraFragment` 定义且引用一致；`CameraEngine` 手动控制方法定义齐全并正确调用。
- XML 的 `mode_portrait/mode_pro/pro_panel/.../pro_auto` 等资源 ID 与 `findViewById` 的 snake_case ID 完全对齐；`strings.xml` 8 项齐备。
- `applyManualControlsLocked()` 已注入 `applyCommonPreviewControls()` 末尾；`applyManualControlsToBuilder()` 已注入 still-capture burst。
- 改动虽触达 `CameraEngine` 采集会话，但**纯增量公开方法 + 既有 setRepeatingRequest 模式**，未重构既有拍照/录像流程；人像/Pro 均未新增 Capture 模式常量，风险可控。
- ⚠️ 仍须本地 `cd Application && ../gradlew clean assembleDebug` 做一次完整编译（含资源重生），确认无遗漏 import / 签名 / 资源缺失。

---

## 第五轮：深度自审 + 真问题修复（用户指令：自己好好审查检查、自己好好完善）

通读 CameraFragment / CameraEngine / ArcSliderView / HistogramView 全文，未走形式，定位并修复 7 个真实问题：

### 修复清单
1. **[严重] 专业模式对焦滑块方向反了 + 标错**：原代码 `proArc.setRange(minF, far=10m)` 并把 10m 标成"∞"。但 Camera2 `LENS_FOCUS_DISTANCE` 语义是 **0=无穷远、minFocusDistance=最近**，设 10m 会被相机钳到最近对焦——等于完全反了。改为 `setRange(0f, maxF)`（maxF=max(minFocusDistance,0.05)），默认 0=∞，标签 `v<=0.01` 显示"∞"。`onProArcChanged` case4 经 `setManualFocusDistance` 落盘（0=infinity 合法）。
2. **[中] EV 曝光补偿步长写死 `/3f`**：设备 `CONTROL_AE_COMPENSATION_STEP` 未必是 1/3。CameraEngine 新增 `getExposureCompensationStep()`（读 RATIONAL，缺省回退 1/3）；CameraFragment 新增字段 `evStep`，于 `onCameraOpened` 初始化，EV 标签（面板 + 竖向滑块）全部改用 `ev * evStep`，标签数值与真实曝光一致。
3. **[中] 离开专业模式不复位白平衡**：Pro 内手动设的 WB 会经 `applyManualControlsLocked` 泄漏到照片模式。selectMode 离开 Pro 时追加 `setWhiteBalance(AUTO)` + `awbCycleIndex=0`。
4. **[中] 手动曝光下 EV 滑块无效却误导**：`AE_MODE_OFF` 时 `CONTROL_AE_EXPOSURE_COMPENSATION` 无作用。现 `selectProParam` case3 在手动曝光下 `proArc.setEnabled(false)`（滑块置灰、禁拖），`updateProValueLabel`/`onProArcChanged` case3 给出"手动曝光下无效"提示并 no-op；切回自动曝光后滑块自动恢复可用（`selectProParam` 顶部统一 `proArc.setEnabled(true)`）。
5. **[低] 直方图采样未用 pixelStride**：`updateHistogram` 原 `buf.get(rowOff + x)` 假设像素步长=1。`plane.getPixelStride()` 已并入索引 `rowOff + x*pixelStride`，与 YUV_420_888 plane0 规范一致。
6. **[低] "自动"键对白平衡无效**：原 `applyProAuto` 无 WB 分支，点"自动"会误触发 `selectProParam(5)`→再切一档 WB。现补 case5：`setWhiteBalance(AUTO)`+`awbCycleIndex=0` 并直接 return，不再误 cycle。
7. **[低] 重新进入 Pro 时 chip 高亮残留**：`updateModeUi` 进入 Pro 时统一把 `proChips` alpha 复位为 1，避免上一次选择的灰态残留。

### 校验结论
- grep 确认无遗留 `far` 变量、无写死 `/3f`（仅剩字段默认回退 `1f/3f` 与 4:3 画比例 `4f/3f`，均无关）；`evStep`/`proArc.setEnabled` 用法一致。
- `startCameraSession()` 映射 `uiMode==VIDEO?VIDEO:PHOTO`，人像/专业均走 `MODE_PHOTO` 会话，无模式映射 bug；`preferBokeh` 仅人像置位。
- `ArcSliderView` 数学自洽：顶=max、底=min，`fraction`/`setFraction`/`bez` 三者对齐，触摸按 Y 映射稳健。
- 本环境无 SDK，仍须本地 clean 构建验证。

