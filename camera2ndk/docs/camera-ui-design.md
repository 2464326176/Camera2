# 现代相机 App UI —— 设计规格与 Jetpack Compose 实现框架

> 面向 MTK Camera / Camera2 项目的前端交互架构。遵循 Material Design 3，深色调、高对比、全面屏 + 折叠屏/平板响应式。

---

## 1. 整体布局与分层架构（Wireframe）

采用 **分层叠加（layered overlay）** 而非 Scaffold 式布局：底层是唯一全屏 `PreviewView`，所有控制元素以半透明浮层叠加其上，保证预览不被裁切、交互浮层可独立进出动画。

```
┌───────────────────────────────────────────────┐  ← 全面屏 / 挖孔屏安全区 (WindowInsets)
│  ┌─────────────────────────────────────────┐  │
│  │  L0  PreviewView  (Camera2 全屏流)        │  │  ← 底层，match_parent，不响应点击穿透
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │ L1  PreviewOverlay                   │ │  │  ← 网格线 / 水平仪 / AF框 / EV滑块
│  │  │   · 九宫格·黄金分割·对角线             │ │  │
│  │  │   · 陀螺仪水平仪(绿/红)               │ │  │
│  │  │   · AF Box + 竖向 EV 滑块             │ │  │
│  │  └─────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │ L2  TopActionBar  (flash/HDR/AI/ratio│ │  │  ← 顶部，半透明，右侧设置入口
│  │  │     /settings)                        │ │  │
│  │  │      [AI Scene Chip 浮动提示]          │ │  │
│  │  └─────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │ L3  ZoomIndicator (0.6/1/2/5 + 刻度盘)│ │  │  ← 顶部下方 pill，缩放时弹出刻度盘
│  │  └─────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │ L4  ProModePanel (ISO/S/EV/AF/WB)    │ │  │  ← 仅在专业模式 AnimatedVisibility 展开
│  │  │     [弯曲弧线 Slider] [直方图]         │ │  │
│  │  └─────────────────────────────────────┘ │  │
│  │  ┌─────────────────────────────────────┐ │  │
│  │  │ L5  BottomControlRegion              │ │  │
│  │  │  [缩略图] [模式Tab] [快门] [翻转]     │ │  │  ← 底部三段式
│  │  └─────────────────────────────────────┘ │  │
│  └─────────────────────────────────────────┘  │
│   ↕ 上滑/下滑 → 半屏 More Sheet (定时/滤镜/美颜) │  ← 覆盖下半屏的 BottomSheet
└───────────────────────────────────────────────┘
```

### 屏幕适配策略
- **画幅切换过渡**：`4:3 / 16:9 / 1:1 / Full` 通过修改 `PreviewView` 的 `scaleType`（FIT_CENTER）+ 动态裁剪遮罩实现。切换时用 `animateRectAsState` 让上下黑边（letterbox）平滑收展，避免拉伸；预览流本身按比例裁剪而非缩放。
- **折叠屏 / 平板响应式**：以 `WindowSizeClass`（`EXPANDED` / `MEDIUM`）驱动布局——展开态把 `TopActionBar` 与 `ProModePanel` 改为左右分栏，`ZoomIndicator` 改为左侧纵向滑轨；竖屏手机维持上下叠加。
- **安全区**：`Modifier.windowInsetsPadding(WindowInsets.systemBars)` 保证挖孔/手势条不被遮挡；`implementationMode = PERFORMANCE` 让 PreviewView 走 Surface 合成，浮层 Compose 在其上。

### 配色（M3 动态配色）
深色调主背景 `#1B1B1F`，浮层用 `colorSurface` 的 85% 透明度。Accent 来自 `dynamicDarkColorScheme(context).primary`（M3 动态取色，跟随壁纸）。状态色：水平仪水平=`tertiary` 绿、倾斜=`error` 红；AI Chip 用 `primaryContainer`。

---

## 2. 顶部快捷工具栏（TopActionBar）

| 控件 | 状态 | 反馈 |
|------|------|------|
| 闪光灯 | 关 / 自动 / 开 / 常亮 | 图标循环切换 + tint 高亮当前态 |
| HDR | 自动 / 开 / 关 | 同上，自动态显示 "A" 角标 |
| AI 场景识别 | 检测态 | 检测到人像/夜景/文档时滑入动态 Chip |
| 画幅比例 | 4:3 / 16:9 / 1:1 / Full | 点击弹出 SegmentedButton 或直接循环 |
| 设置 | — | 点击弹出半屏/全屏 Setting 面板 |

---

## 3. 取景框增强（PreviewOverlay）

- **网格线**：九宫格 / 黄金分割 / 对角线三选一，Canvas 绘制，可淡入淡出。
- **水平仪**：读取 `SensorManager` / `RotationVector`，倾斜角 `|θ|<2°` 时指示器变绿并锁定中线，超出变红并随角度旋转指示方向。
- **AF/AE**：点击出现 `AF Box`（缩放淡入动画），右侧竖向 `EV` 滑块联动；长按触发 `LockAF-AE`，框体变色（黄→橙）并显示锁标。

---

## 4. 专业模式面板（ProModePanel）

底部一排悬浮 Chip：`ISO / Shutter(S) / EV / Focus(AF) / WB`。点击任一 → 上方展开 **弯曲弧线刻度尺**（Canvas 绘制弧 + 可拖拽 thumb），支持 `Auto` 一键还原。实时 **直方图** 由 `YuvImage` 统计亮度分量绘制。

---

## 5. 底部核心控制区（BottomControlRegion）

- **模式 Tab**：横向 `Portrait / Photo / Video`，选中项加粗高亮 + 滑动指示器（`animateOffset`）+ 切换时 `HapticFeedback` 轻震。
- **缩略图**：左下圆形，新拍摄完成后弹簧缩放（pop-in）动画，点击沉浸式进 Gallery。
- **快门**：Photo 为白色圆环；Video 变红点录制键。`pointerInput` 区分短按（拍照）/ 长按（录视频）。按下时 `scale` 下压。
- **翻转**：右侧按钮，点击 180° 旋转动画。

---

## 6. 手势与悬浮弹窗

- 左右滑动 → 切换拍摄模式（`detectHorizontalDragGestures`）。
- 双指捏合/张开 → Zoom（`detectTransformGestures` 的 `zoom` 因子）。
- 顶部下滑 / 取景框上滑 → 展开半屏 `More Sheet`（定时、滤镜强度、美颜磨皮/瘦脸滑块）。
- 音量键 → 物理快门（`onKeyDown KEYCODE_VOLUME_DOWN/UP`）。

---

## 7. Jetpack Compose 实现框架

### 7.1 状态模型（状态提升）

```kotlin
data class CameraUiState(
    val previewView: PreviewView? = null,
    val flashMode: FlashMode = OFF,
    val hdrMode: HdrMode = AUTO,
    val ratio: AspectRatio = RATIO_4_3,
    val aiScene: AiScene? = null,            // null = 不显示 Chip
    val isProMode: Boolean = false,
    val focusPoint: Offset? = null,          // AF Box 位置
    val aeLocked: Boolean = false,
    val ev: Float = 0f,
    val zoom: Float = 1f,
    val captureMode: CaptureMode = PHOTO,
    val showMoreSheet: Boolean = false,
    val lastThumbnailUri: Uri? = null
)

sealed interface CameraUiEvent {
    data object ToggleFlash : CameraUiEvent
    data object ToggleHdr : CameraUiEvent
    data class SetRatio(val r: AspectRatio) : CameraUiEvent
    data class TapToFocus(val p: Offset) : CameraUiEvent
    data class DragEv(val v: Float) : CameraUiEvent
    data class ZoomBy(val factor: Float) : CameraUiEvent
    data class SetMode(val m: CaptureMode) : CameraUiEvent
    data object ShutterClick : CameraUiEvent
    data object FlipCamera : CameraUiEvent
    data object ToggleMoreSheet : CameraUiEvent
}
```

### 7.2 主屏（分层叠加容器）

```kotlin
@Composable
fun CameraScreen(
    state: CameraUiState,
    onEvent: (CameraUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .pointerInput(Unit) {
                // 双指缩放 + 左右滑切换模式（放在最外层，预览优先消费）
                detectTransformGestures { _, _, zoom, _ -> onEvent(ZoomBy(zoom)) }
            }
    ) {
        // L0
        CameraPreview(state.previewView, Modifier.fillMaxSize())

        // L1
        PreviewOverlay(state, onEvent)

        // L2 + AI chip
        TopActionBar(state, onEvent, Modifier.align(Alignment.TopCenter))
        AiSceneChip(state.aiScene, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))

        // L3
        ZoomIndicator(state, onEvent, Modifier.align(Alignment.TopCenter).padding(top = 140.dp))

        // L5 始终存在
        BottomControlRegion(state, onEvent, Modifier.align(Alignment.BottomCenter))

        // L4 条件展开
        AnimatedVisibility(
            state.isProMode,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp),
            enter = ExpandVertically + fadeIn(), exit = shrinkVertically + fadeOut()
        ) { ProModePanel(state, onEvent) }

        // 半屏 More Sheet
        MoreSettingsSheet(state.showMoreSheet, onEvent,
            Modifier.align(Alignment.BottomCenter))
    }
}
```

### 7.3 L0 预览（PreviewView 桥接）

```kotlin
@Composable
fun CameraPreview(previewView: PreviewView?, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            previewView ?: PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        modifier = modifier
    )
}
```

### 7.4 L1 取景框增强（网格 / 水平仪 / AF 框 / EV）

```kotlin
@Composable
fun PreviewOverlay(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit) {
    Canvas(Modifier.fillMaxSize()) {
        if (state.gridType != NONE) drawGrid(gridType, size, Color(0x55FFFFFF))
        // 水平仪：tilt 来自 ViewModel（-45..45），绿色水平，红色倾斜
        val tint = if (abs(tiltDeg) < 2f) Color(0xFF639922) else Color(0xFFA32D2D)
        drawLine(tint, start, end, strokeWidth = 3f,
                 blendMode = BlendMode.Screen)
    }

    // AF Box
    AnimatedVisibility(state.focusPoint != null,
        enter = scaleIn(initialScale = 1.4) + fadeIn()) {
        val p = state.focusPoint!!
        Box(Modifier.offset { IntOffset(p.x.roundToInt() - 32, p.y.roundToInt() - 32) }
            .size(64.dp).border(2.dp, if (state.aeLocked) Color(0xFFFFB300) else Color(0xFF7F77DD), RoundedCornerShape(6.dp)))
    }

    // 竖向 EV 滑块（旋转 Slider）
    Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(200.dp).height(36.dp)) {
        Slider(
            value = state.ev, onValueChange = { onEvent(DragEv(it)) },
            valueRange = -3f..3f, steps = 12,
            modifier = Modifier.fillMaxWidth().rotate(-90f)
        )
    }
}
```

### 7.5 L2 顶部栏（含状态切换）

```kotlin
@Composable
fun TopActionBar(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit, modifier: Modifier) {
    Row(modifier.padding(16.dp).height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {

        IconToggleButton(state.flashMode != OFF,
            onClick = { onEvent(ToggleFlash) }) {
            Icon(flashIcon(state.flashMode), "flash",
                tint = if (state.flashMode == OFF) Color.Gray else MaterialTheme.colorScheme.primary)
        }
        IconToggleButton(state.hdrMode == ON, onClick = { onEvent(ToggleHdr) }) {
            Icon(Icons.Default.FilterHdr, "hdr",
                tint = if (state.hdrMode == ON) MaterialTheme.colorScheme.primary else Color.Gray)
        }
        // 画幅比例循环
        TextButton(onClick = { onEvent(SetRatio(nextRatio(state.ratio))) }) {
            Text(state.ratio.label, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { /* open settings */ }) {
            Icon(Icons.Default.Settings, "settings")
        }
    }
}

@Composable
fun AiSceneChip(scene: AiScene?, modifier: Modifier) {
    AnimatedVisibility(scene != null,
        enter = slideInVertically { -40 } + fadeIn(),
        exit = slideOutVertically { -40 } + fadeOut()) {
        AssistChip(
            onClick = {}, label = { Text(scene!!.label) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)
        )
    }
}
```

### 7.6 L3 变焦指示器（pill + 刻度盘）

```kotlin
@Composable
fun ZoomIndicator(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(4.dp)) {
            listOf(0.6f, 1f, 2f, 5f).forEach { z ->
                val selected = state.zoom == z
                TextButton(onClick = { onEvent(ZoomTo(z)) },
                    colors = if (selected) ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary) else ButtonDefaults.textButtonColors()) {
                    Text("${z}x")
                }
            }
        }
    }
    // 双指缩放时弹出的刻度盘：用 Canvas 画弧 + animateFloat 控制出现
}
```

### 7.7 L4 专业模式面板

```kotlin
@Composable
fun ProModePanel(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                ProChip("ISO", state.isoAuto) { /* 展开弧线滑块 */ }
                ProChip("S", state.ssAuto) { /* 快门速度 */ }
                ProChip("EV", false) { }
                ProChip("AF", state.afAuto) { }
                ProChip("WB", state.wbAuto) { }
            }
            // 选中参数后在此用 Canvas 画弯曲弧线刻度尺 + 直方图
            Histogram(state.histogram)
        }
    }
}
```

### 7.8 L5 底部控制区

```kotlin
@Composable
fun BottomControlRegion(state: CameraUiState, onEvent: (CameraUiEvent) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {

        // 模式 Tab（横向 + 滑动指示器 + 触觉反馈）
        ModeTabs(state.captureMode, onEvent)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {

            // 左下缩略图
            AsyncImage(state.lastThumbnailUri, "thumb",
                Modifier.size(48.dp).clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                modifier = Modifier.animateScalePop(state.lastThumbnailUri != null))

            // 中间快门（短按拍照 / 长按录像）
            val pressed = remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (pressed.value) 0.88f else 1f)
            Box(Modifier.size(76.dp).scale(scale).pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onEvent(ShutterClick) },
                    onLongPress = { onEvent(StartRecording) },
                    onPress = { pressed.value = true; awaitRelease(); pressed.value = false }
                )
            }) {
                if (state.captureMode == VIDEO)
                    Canvas(Modifier.fillMaxSize()) { drawCircle(Color.Red, radius = 22.dp.toPx()) }
                else
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, radius = 30.dp.toPx())
                        drawCircle(Color.White, radius = 38.dp.toPx(), style = Stroke(4.dp.toPx()))
                    }
            }

            // 右侧翻转（180° 旋转动画）
            var flipped by remember { mutableStateOf(false) }
            val rot by animateFloatAsState(if (flipped) 180f else 0f)
            IconButton(onClick = { flipped = !flipped; onEvent(FlipCamera) },
                modifier = Modifier.rotate(rot)) {
                Icon(Icons.Default.FlipCameraAndroid, "flip")
            }
        }
    }
}

@Composable
fun ModeTabs(mode: CaptureMode, onEvent: (CameraUiEvent) -> Unit) {
    val tabs = listOf(PORTRAIT, PHOTO, VIDEO)
    val idx = tabs.indexOf(mode)
    val indicatorX by animateIntOffsetAsState(IntOffset(idx * 80, 0))
    Box(Modifier.width(240.dp).height(30.dp)) {
        Row(Modifier.fillMaxSize(), Arrangement.SpaceEvenly) {
            tabs.forEach { m ->
                TextButton(onClick = {
                    onEvent(SetMode(m))
                    LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }) {
                    Text(m.label, fontWeight = if (m == mode) FontWeight.Medium else FontWeight.Normal,
                        color = if (m == mode) MaterialTheme.colorScheme.primary else Color.Gray)
                }
            }
        }
        Box(Modifier.offset { indicatorX }.size(80.dp, 30.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(15.dp)))
    }
}
```

---

## 8. 关键交互状态与动画说明

| 交互 | 状态机 | 动画实现 |
|------|--------|----------|
| **模式切换** | `Portrait ↔ Photo ↔ Video` 有限状态 | `ModeTabs` 指示器 `animateIntOffsetAsState` 滑动；触发 `HapticFeedback.TextHandleMove`；Video 态快门 morph 为红点（Crossfade） |
| **变焦刻度盘** | 静止 pill ↔ 缩放时弹出弧形刻度 | 捏合/拖拽触发，`AnimatedVisibility` + `animateFloatAsState` 控制弧线角度与透明度；3 秒无操作自动收起 |
| **拍摄缩略图更新** | 无图 → 新图 pop-in → 点击进 Gallery | 新 URI 到达时 `spring()` 缩放弹入（scale 0→1.1→1）；点击 `SharedElement` / `Activity` 转场沉浸式预览 |
| **镜头翻转** | 后摄 ↔ 前摄 | 按钮 `rotate(180°)`（`animateFloatAsState`）；同时 `PreviewView` 重建并交叉淡入 |
| **AF/AE 锁定** | 解锁(紫) → 长按锁定(橙+锁标) → 再次长按解锁 | AF Box 颜色 `animateColorAsState`；锁标缩放淡入 |
| **专业面板展开** | 普通 ↔ 专业 | `AnimatedVisibility` 高度 `expandVertically + fadeIn`；弧线滑块随后 `slideIn` |
| **More Sheet** | 收起 ↔ 半屏展开 | `ModalBottomSheet` / 自定义 `AnchoredDraggable`；下滑手势 `detectVerticalDragGestures` 拖动锚点 |

**性能要点**：预览走 `PERFORMANCE` 模式（Surface 直接合成，不进 Compose 渲染树）；浮层动画尽量用 `animate*AsState`/`Animatable` 而非重组整棵子树；直方图统计放在后台线程，结果通过 `mutableStateListOf` 节流刷新。

---

## 9. 落地建议（对接 MTK Camera2 项目）

1. `PreviewView` 绑定 `camera2ndk` 的 `SessionConfiguration`，画幅切换复用现有 `OutputConfiguration` 重建。
2. AI 场景识别结果从 `mtkcam3` 的 `IFeaturePipe` 回调映射到 `CameraUiState.aiScene`。
3. EV / ISO / 快门映射到 `CaptureRequest` 的 `CONTROL_AE_EXPOSURE_COMPENSATION` / `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME`。
4. 折叠屏用 `WindowManager` 的 `FoldingFeature` 监听铰链状态切换布局。
