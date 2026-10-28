# RoadVision 当前技术架构（As-Built）

**文档定位：** 描述仓库当前代码**实际**如何组织与运行，不是目标方案。目标方案与设计原则见
[技术架构方案](ARCHITECTURE.md)；本文与其冲突时，以本文的“代码事实”为准。

**基线：** `main` 分支 commit `91497a5`，版本 `0.1.0`（versionCode 1），2026-10-27 通读全部源码。

**标记约定：**

- 【代码】可直接在当前源码复核的实现事实。
- 【实测·STATUS】来自 [项目状态说明](../STATUS.md) 记录的真机/开发机数据，不构成架构承诺。
- 【待确认】尚无证据，不得当作已实现或已验证。

---

## 1. 一句话总览

RoadVision 是一个**单模块 Android 应用**：CameraX 在**前台 `camera` 服务**中绑定后置相机，每帧
`YUV_420_888` 在分析线程内一次遍历完成转 RGB + 旋转，随后交给**单工作线程、最多缓存一帧**的
`FrameScheduler`；该线程依次执行 ONNX Runtime（CPU 为基线）的车辆检测、可选车道检测、可选车牌检测 +
OCR，再经纯 Java 的 IoU 跟踪、车牌-车辆几何关联、多帧 OCR 融合，最后把结果推给 Compose 状态与
`View` 叠加层，并在满足条件时写入 Room。**没有任何网络权限、没有后端、没有云端 SDK。**

```text
┌──────────────────────── Android 进程（单进程，无网络权限） ─────────────────────────┐
│                                                                                     │
│  MainActivity ── Compose(RoadScreen)                                                │
│      │                │                                                             │
│      │                ├── PreviewView + RoadOverlayView（FrameLayout，独立 View）   │
│      │                ├── PortraitControlPanel / LandscapeSidePanel                 │
│      │                └── SettingsSheet → SharedPreferences                         │
│      │                                                                              │
│      └── RoadViewModel（AndroidViewModel，进程内唯一编排者）                         │
│              ├── FrameScheduler  ── 单线程 roadvision-frame-processor               │
│              ├── CameraController ── 单线程 roadvision-camera-analyzer              │
│              ├── ActivePipeline → PipelineCoordinator → OrtRuntimeManager           │
│              ├── PerformanceRecorder（StateFlow 指标）                              │
│              └── Room(roadvision.db) ← DetectionRepository                          │
│                                                                                     │
│  CaptureService（前台 camera 服务，LifecycleOwner=RESUMED）                          │
│      └── 通过 CaptureBridge（进程级单例）取得 CameraController 并 bindToLifecycle   │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 技术栈与构建基线【代码】

| 类别 | 选型 | 版本/取值 | 说明 |
| --- | --- | --- | --- |
| 语言 | Kotlin | 2.0.21 | `jvmTarget = 17` |
| 构建 | Gradle Wrapper | 8.9 | `gradle/wrapper/gradle-wrapper.properties` |
| 构建 | Android Gradle Plugin | 8.7.3 | 插件在根 `build.gradle.kts` 声明 |
| 构建 | KSP | 2.0.21-1.0.28 | 仅用于 Room 编译器 |
| SDK | compileSdk / targetSdk / minSdk | 35 / 35 / 26 | `app/build.gradle.kts` |
| UI | Jetpack Compose | ui 1.7.7、material3 1.3.1 | 无 Navigation、无 Paging |
| UI | activity-compose / lifecycle | 1.10.0 / 2.8.7 | `collectAsState` + `StateFlow` |
| 相机 | CameraX | 1.4.1（core、camera2、lifecycle、view） | 后置相机，`ImageAnalysis` |
| 推理 | ONNX Runtime Android | 1.20.0 | `com.microsoft.onnxruntime:onnxruntime-android` |
| 存储 | Room | 2.6.1（runtime、ktx、compiler） | `roadvision.db`，schema v2 |
| 存储 | SharedPreferences | — | `roadvision-settings`，无 DataStore |
| 并发 | kotlinx-coroutines-android | 1.9.0 + `java.util.concurrent` | 手写线程/执行器，非 Flow 驱动流水线 |
| 测试 | JUnit 4 | 4.13.2 | 仅 JVM 单元测试 |

**明确未采用**（避免误读）：Hilt/Koin 等 DI、Retrofit/OkHttp、任何序列化库（诊断报告为手写 JSON）、
ML Kit、CameraX `ImageCapture`、WorkManager、Compose Navigation、androidTest/instrumentation 源码。

应用 ID 与包名均为 `com.roadvision.roadsense`。

---

## 3. 包结构与职责【代码】

```text
app/src/main/java/com/roadvision/roadsense/
├── MainActivity.kt            单 Activity，仅 setContent { RoadScreen() }
├── camera/                    CameraX 绑定、状态、YUV→RGB 旋转、显示方向归一化
├── capture/                   前台 camera 服务 + ViewModel↔Service 交接桥
├── inference/                 ONNX Runtime、模型契约、预处理、四类检测/识别器、解码器
├── pipeline/                  帧调度、模型清单校验与工厂、逐帧编排、OCR 限频、解码参数
├── tracking/                  IoU 跟踪、车牌-车辆关联、OCR 多帧融合
├── data/                      Room 实体/DAO/仓储
├── diagnostics/               性能计数、设备健康采样、报告生成与导出
├── privacy/                   车牌脱敏日志
└── ui/                        Compose 界面、AndroidView 叠加层、设置、布局策略
```

| 包 | 关键类型 | 实际职责 |
| --- | --- | --- |
| `camera` | `CameraController`、`FrameConverter`、`ConvertedFrame`、`DisplayRotation` | CameraX 用例绑定与解绑；分析帧转换；把显示方向下发给 `ImageAnalysis.targetRotation` |
| `capture` | `CaptureService`、`CaptureBridge` | 前台服务持有 CameraX 生命周期；进程级静态交接 `CameraController` |
| `inference` | `OrtRuntimeManager`、`ModelContract`、`TensorPreprocessor`、`OutputValidator`、`ModelAssetVerifier`、`ModelBundleConfigLoader`、`OnnxVehicleDetector`、`OnnxLaneDetector`、`OnnxPlateDetector`、`OnnxPlateRecognizer`、`ClassicalLaneDetector`、`LaneMaskDecoder`、`UfldLaneDecoder`、`CtcDecoder`、`PlateCropper`、`CoordinateMapper` | 会话缓存与执行提供器；输入/输出契约与校验；预处理；解码；权重兜底检测；车牌透视裁剪 |
| `pipeline` | `FrameScheduler`、`PipelineFactory`、`ModelBundleValidator`、`PipelineCoordinator`、`OcrScheduler`、`DecodeConfig` | 背压调度；装配与预热；清单合法性门禁；单帧编排；OCR 限频；后处理参数 |
| `tracking` | `VehicleTracker`、`PlateVehicleMatcher`、`OcrTemporalFusion` | 临时 Track ID；车牌归属确认；多帧文本投票 |
| `data` | `AppDatabase`、`DetectionEventEntity`、`DetectionEventDao`、`DetectionRepository` | 事件持久化、保留期清理、单条/全部删除 |
| `diagnostics` | `PerformanceRecorder`、`DiagnosticsReportBuilder`、`DeviceHealthSampler`、`ReportExporter` | 指标口径；报告 schema v3；PSS/整机内存/热状态；FileProvider 分享 |
| `ui` | `RoadScreen`、`RoadOverlayView`、`ControlPanels`、`DiagnosticsPanel`、`PlateEventList`、`SettingsSheet`、`AppSettings`、`SettingsOverrides`、`PreviewCoordinateMapper`、`ScreenLayout`、`ModelLabels` | 布局、叠加、设置覆盖、坐标映射、模型角色文案 |
| `debug` | `BenchmarkActivity` | 仅 debug 的合成基准，不经相机 |

**注意：** [ARCHITECTURE.md](ARCHITECTURE.md) 中提到的 `ResultFusion` **不存在**。融合逻辑内联在
`PipelineCoordinator.process()` 中（跟踪 → 车道节流 → 车牌关联 → OCR 融合 → 结果组装）。

---

## 4. 线程与执行模型【代码】

| 执行体 | 谁创建 | 承载内容 | 约束 |
| --- | --- | --- | --- |
| Android 主线程 | 系统 | Compose 重组、CameraX 用例绑定/解绑、`PreviewView` 挂载、设置写盘 | 不做推理、不做像素转换 |
| `roadvision-camera-analyzer`（单线程） | `CameraController` | `ImageProxy` → `recorder.onCaptured()` → `FrameConverter.convert()` → `FrameScheduler.submit()`；`finally` 中 `image.close()` | **绝不把 `ImageProxy` 交给其他线程**；转换异常计为错误并上报 `CameraStatus.Error` |
| `roadvision-frame-processor`（单线程） | `FrameScheduler` | 整条 `PipelineCoordinator.process()`：预处理 → 推理 → 解码 → 跟踪 → 融合 → 回调 | **队列中最多 1 个待处理帧**；新帧直接替换旧帧并计入 `droppedFrames` |
| ORT 内部线程池 | ONNX Runtime | CPU 内核执行；XNNPACK 指定 `intra_op_num_threads=2` | 同一时刻只允许一个 worker 进入 `run()` |
| `Dispatchers.Default` | `RoadViewModel.viewModelScope` | `ModelBundleConfigLoader.load()`、`PipelineFactory.create()`（含 SHA-256 与预热）、模型重载、保留期清理 | 结果通过 `StateFlow` 回主线程 |
| Room 事务执行器 | Room | insert / delete / 查询 Flow | 事件写入由 `viewModelScope` 协程发起 |

跨线程一致性由三处锁/原子量保证：

- `OrtRuntimeManager` 的 `ReentrantReadWriteLock`：`run()` 持读锁，`close()` 持写锁并等待在飞推理返回，
  避免在原生 `run()` 执行中释放 Session 导致 `libonnxruntime.so` 内 SIGSEGV / Scudo abort。
- `FrameScheduler.close()`：先丢弃待处理帧，再 `awaitTermination(2s)`，超时才 `shutdownNow()`。
- `RoadViewModel.pipelineLock` + `modelGeneration`：先摘除 `activePipeline` 再关闭，防止新帧进入已退役流水线；
  过期的异步加载结果会被 `generation` 丢弃。

---

## 5. 启动、模型装载与失败路径【代码】

### 5.1 启动

1. `MainActivity.onCreate()` → `setContent { RoadScreen() }`；Activity 声明
   `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`，
   旋屏不重建界面。
2. `RoadViewModel` 构造时创建 `PerformanceRecorder`、`AppDatabase`、`SettingsStore`、`FrameScheduler`、
   `CameraController`，写入 `CaptureBridge.controller`，并异步执行一次保留期清理。
3. `configureModels()`：若 `assets/models/model-bundle.json` 不存在 → `ModelRuntimeState.NotConfigured`，
   应用只采集不推理；存在则进入 `Loading`，在 `Dispatchers.Default` 上装载。
4. 首次进入 `PreviewPane` 时请求 `CAMERA`；Android 13+ 再请求 `POST_NOTIFICATIONS`；取得预览 surface 后
   调用 `viewModel.startCaptureService()` 启动前台服务。
5. `CaptureService.onCreate()` 先 `startForeground(type=CAMERA)`，再取 `CaptureBridge.controller`，
   将自身 `LifecycleRegistry` 置为 `RESUMED` 并 `controller.start(this, this)`。取不到 controller 直接
   `stopSelf()`。

### 5.2 模型装配顺序（`PipelineFactory.create`）

```text
model-bundle.json
   │ ModelBundleConfigLoader.load()           反序列化为 PipelineModelConfig（末尾已校验一次）
   │ SettingsOverrides.apply()                设置页覆盖 provider / 三个阈值
   ▼
ModelBundleValidator.validate()              覆盖后再校验：车辆必填；车道/车牌成对；形状与资产路径
   ▼
ModelAssetVerifier.verify() × 每个已声明资产  SHA-256 全文件校验
   ▼
providerCandidates(requested)                 请求 CPU 只试 CPU；XNNPACK/NNAPI 失败则回退 CPU
   │ 对每个候选：
   │   OrtRuntimeManager.session() 预建全部 Session（失败即换候选）
   │   warmUp() 用全零输入对每个模型跑一次并校验输出形状（拦截“能建 Session、首次 run 失败”的提供器）
   ▼
ActivePipeline(runtime, coordinator, provider, fallbackToCpu, enabledModels)
```

任一步骤抛异常 → `ModelRuntimeState.Failed(message)`，`activePipeline = null`，感知结果清空、
错误计数 +1；UI 显示“模型不可用”并提供“重新加载”。

**省略语义是显式配置，不是文件缺失自动降级：** 清单省略 `lane` / `plate`+`ocr` → 对应能力不建 Session、
不产出结果、UI 标注降级；清单声明了资产但文件缺失或哈希不符 → **整条流水线失败**。

### 5.3 逐帧处理

```text
CameraX ImageAnalysis (YUV_420_888, STRATEGY_KEEP_ONLY_LATEST, analyzer 单线程)
  ├─ ingressNs = System.nanoTime()            ← 延迟计时起点，早于转换
  ├─ recorder.onCaptured()
  ├─ FrameConverter.convert(image, frameId, ingressNs)
  │    └─ 单遍 YUV→RGB + 旋转（输出即显示方向；90/270 交换宽高）
  ├─ FrameScheduler.submit(frame)             ← 新帧替换旧待处理帧，旧帧计 dropped
  └─ image.close()

roadvision-frame-processor
  └─ RoadViewModel.processFrame(frame)
       ├─ recorder.onFrameGeometry(w,h,rotation)
       ├─ pipeline == null → recorder.onSkipped() + 每 30 帧采一次设备健康
       └─ pipeline.process(frame)
            └─ PipelineCoordinator.process(frame)
                 1. VehicleTracker.update(vehicleDetector.detect(frame))
                 2. 清理已消失 Track 的 OCR/关联/融合/已落库状态
                 3. vehicles = 本帧命中的 Track（missedFrames == 0）
                 4. frameId % laneEveryFrames（默认 3）== 0 → 车道检测（否则沿用 lastLane）
                 5. frameId % plateEveryFrames（默认 3）== 0 → 车牌检测 → 关联 → 裁剪 → OCR 限频 → 识别 → 融合
                 6. recorder.onInference(整段耗时, provider)
                 7. onResult(FusedPerceptionResult) → StateFlow → 叠加层
                 8. 满足条件 → onEvent(DetectionEventEntity) → Room
       ├─ recorder.onProcessed(ingressNs, sensorNs)   ← 延迟 = now - ingress
       └─ 每 30 帧 DeviceHealthSampler.sample()
```

任一帧抛异常 → 在锁内摘除该 `ActivePipeline` 并关闭，`ModelRuntimeState.Failed`；若该实例已被
重载/停止退役，则不视为运行时失败（避免重复报错）。

### 5.4 停止与重载

- 用户停止采集 / 权限丢失：`stopCaptureService()` → `CameraController.stop()`（`unbindAll`）；调度器与
  已加载管线保留，恢复采集时直接复用，只有 `onCleared()` 才按
  `cameraController.close()` → `scheduler.close()` → `pipeline.close()` → `database.close()` 全量释放。
- 设置页改 provider 或阈值：`reloadModels()` 递增 `modelGeneration`，摘除并关闭旧管线，再重新装配。
  进程级 `OrtEnvironment` 不关闭，保证重试链路仍可用。

---

## 6. 模型接入契约与预处理【代码】

### 6.1 清单到内存模型

`app/src/main/assets/models/model-bundle.json`（本地评测配置，已被 `.gitignore` 排除；仓库只提交
`*.example`）：

```json
{
  "provider": "CPU",
  "schedule": { "laneEveryFrames": 3, "plateEveryFrames": 3 },
  "vehicle": {
    "assetPath": "models/vehicle/model.onnx",
    "labels": { "2": "car", "3": "motorcycle", "5": "bus", "7": "truck" },
    "decode": { "confidence": 0.35, "nmsIou": 0.5, "maxDetections": 100 },
    "contract": {
      "id": "…", "version": "…", "sha256": "…",
      "input":  { "name": "images", "shape": [1, 3, 352, 352] },
      "outputs": [{ "name": "normalised", "shape": [1, -1, 6] }],
      "layout": "NCHW", "colorOrder": "RGB", "scale": 0.0039215686,
      "mean": [0, 0, 0], "std": [1, 1, 1], "padPixel": 114
    }
  },
  "lane": {
    "assetPath": "models/lane/model.onnx",
    "decode": { "threshold": 0.5, "type": "mask" }
  },
  "plate": { "assetPath": "models/plate/model.onnx",
             "contract": { "…": "同 vehicle，输出 [1, N, 6]" } },
  "ocr":   { "assetPath": "models/ocr/model.onnx", "alphabet": ["…"], "blankIndex": 0,
             "contract": { "…": "输出 [1, T, alphabet+1]" } }
}
```

> 上例为**字段合并示意**：实际本地清单目前只含 `vehicle`；`lane` 也可以写成
> `{ "mode": "classical" }` 走后无权重检测器；`plate` 与 `ocr` 必须成对出现，否则加载即失败。

| 概念 | 类型 | 约束 |
| --- | --- | --- |
| `ModelContract` | id/version/sha256/input/outputs/layout/colorOrder/scale/mean/std/padPixel/resizeMode | sha256 必须 64 位十六进制；输入 rank 4；`std > 0` |
| `TensorLayout` | `NCHW` / `NHWC` | 决定输入通道位置与写入顺序 |
| `ColorOrder` | `RGB` / `BGR` | 通过首/末通道交换实现，不额外拷贝 |
| `ResizeMode` | `LETTERBOX`（默认）/ `STRETCH` | UFLD 用拉伸，其余用 letterbox |
| `DetectionDecodeConfig` | confidence / nmsIou / maxDetections | 代码默认车辆 0.4 / 0.5 / 100（当前本地清单覆盖为 0.35）、车牌 0.5 / 0.4 / 50 |
| `LaneDecodeConfig` | threshold / type / rowAnchors | `MASK` 或 `UFLD`；UFLD 必须给 ≥3 个行锚点 |
| `PipelineSchedule` | laneEveryFrames / plateEveryFrames | 均 ≥1，默认 3 |

### 6.2 预处理（`TensorPreprocessor`）

- 由 `contract.input.shape` 与 `layout` 推出 `inputWidth/Height`，校验 batch=1、RGB 缓冲长度自洽。
- **letterbox**：等比缩放 + 居中填充，填充值 `padPixel`（默认 114 灰）经 `scale/mean/std` 换算后写入；
  逐列/逐行预计算 `xMap/yMap`，避免每像素除法。
- **stretch**：x/y 独立缩放，行/列映射按比例取整。
- 归一化以 `gain = scale/std`、`bias = -mean/std` 的增益-偏置形式应用。
- 每个检测器**复用一个 scratch `FloatArray`**，避免每帧分配数 MB；`prepare()` 返回的数组在下一次
  `prepare()` 前有效。
- 只有 `FLOAT32` 数据类型；量化模型（INT8/UINT8 输出）当前不受支持。

### 6.3 输出契约与解码

| 角色 | 输出形状 | 解码器 | 后处理 |
| --- | --- | --- | --- |
| vehicle | `[1, N, 6]`（x1,y1,x2,y2,score,classId，归一化） | `VehicleOutputDecoder` | 置信度阈值 → **标签表即类别白名单**（COCO 的人/自行车被丢弃）→ 按分数降序取前 N → 同类别逐框 NMS |
| plate | `[1, N, 6]`，类别固定 `0=plate` | 复用 `VehicleOutputDecoder` | 同上，阈值 0.5 / IoU 0.4 / 最多 50 |
| lane-mask | `[1, 1, H, W]` 概率图 | `LaneMaskDecoder` | 每 8 行取行内非极大值峰，按最近 x 连线，最多 3 条折线，每条 ≥3 点 |
| lane-UFLD | `[1, cells+1, rows, lanes]` | `UfldLaneDecoder` | 位置 bin 上 softmax 求期望，`argmax == cells` 视为该行无线，峰值概率低于阈值丢弃 |
| ocr | `[1, T, alphabet+1]`（CTC logits） | `CtcDecoder` | 逐步 argmax → 去重 → 去 blank；字符置信度取均值；空串返回 null |

> **坑（代码事实）：** UFLD 的解码器与 stretch 预处理已实现，但 `ModelBundleValidator.validateLaneOutput`
> 仍只接受 `[1, 1, H, W]` 掩码形状。写入 `type = "ufld"` 的清单会在**装载前**被校验器以
> “Lane mask must be [1, 1, H, W]” 拒绝。启用 UFLD 前必须先把校验器按 `LaneDecodeType` 分支放行该形状。

坐标回映射：`FrameTransform`（`LetterboxTransform` / `StretchTransform`）把模型输入归一化坐标还原到
**源帧归一化坐标**，再 `clamp()` 到 `[0,1]`。检测框、车道折点都经过这一层；车牌裁剪在源帧上按框做
投影采样。

### 6.4 输入/输出校验门禁

- 资产路径必须位于 `models/` 下且不含 `..` 或反斜杠。
- 输入必须固定维度、正值、batch=1、3 通道。
- 输出必须是单输出；动态维只允许 `-1`；形状在每次 run 后与契约逐维比对。
- 输出数值必须有限（拒绝 NaN/Inf）；置信度必须在 `[0,1]`；框必须在归一化范围附近（±0.01）。
- 车道掩码每个像素必须在 `[0,1]`；OCR logits 行宽必须等于 `alphabet.size + 1`。

---

## 7. 调度、阈值与状态参数【代码】

| 机制 | 参数（默认值） | 行为 |
| --- | --- | --- |
| 帧背压 | 待处理帧上限 1 | 新帧覆盖旧帧，`droppedFrames++`；关闭时先丢弃再等 2 s |
| 车道节流 | `laneEveryFrames = 3` | 非推理帧沿用 `lastLane`（不会伪造新结果，但也不清空） |
| 车牌节流 | `plateEveryFrames = 3` | 仅在整除帧执行车牌检测 |
| OCR 限频 | 同 Track 最小间隔 400 ms，最多 6 次 | 裁剪不合格直接跳过；Track 消失即清理计数 |
| 跟踪 | IoU ≥ 0.25、最多漏检 8 帧、空闲 1 s 删除、同类别匹配 | 贪心最近 IoU；`trackId` 从 1 递增 |
| 车牌-车辆关联 | containment ≥ 0.35，连续 2 个车牌帧确认，间隔 ≤1.5 s，框 IoU ≥ 0.2 | 车牌中心须落在车辆框内；以 72% 车高处为期望纵向位置加权 |
| OCR 融合 | 2 s 窗口、≥2 次观测、平均置信度 ≥0.75 | 按规范化文本分组，取置信度和最大组；不达标标记 `uncertain` |
| 事件落库 | 融合已确认 + 关联已确认 + 该 Track 首次 | 每个 Track 最多一条，Track 活跃期内不重复写 |
| 执行提供器 | 请求 → 失败回退 `CPU` | 回退后 `fallbackToCpu = true`，UI 与报告均显示 |
| 保留期 | 30 天（可在 1–365 调整） | 启动时与修改设置时按 `createdAtEpochMs` 删除 |

---

## 8. UI 架构【代码】

- **Compose 负责“壳”，CameraX 负责“画”**：`PreviewView` 与 `RoadOverlayView` 放在同一个
  `FrameLayout` 里，通过 `AndroidView` 挂进 Compose。叠加层是普通 `View` 而非 Compose Canvas：
  代码注释记录的原因是部分设备上 AndroidView 子视图会盖在 Compose 层之上。
- **坐标映射**：`PreviewCoordinateMapper` 复刻 `PreviewView.ScaleType.FILL_CENTER`（等比放大后居中裁剪），
  以 `FusedPerceptionResult.sourceWidth/Height` 为源尺寸，因此叠加框与预览画面一致。
- **状态来源**：`RoadViewModel` 暴露 `StateFlow`（相机状态、诊断、感知结果、事件、模型状态、设置）；
  Compose 侧只 `collectAsState`，不直接触碰推理对象。
- **布局策略**：按**窗口尺寸**（非设备方向）在 `PORTRAIT` / `LANDSCAPE` 间切换——横屏把诊断与事件放进
  右侧 360 dp 固定栏，画面占满剩余区域；竖屏折叠为两行摘要，展开面板最高占窗口 55% 且可滚动。
- **旋屏**：`DisplayManager.DisplayListener` → `CameraController.updateTargetRotation()` 更新
  `ImageAnalysis.targetRotation`，保证分析帧方向与 `PreviewView` 自转后的画面一致。
- **设置**：`SettingsSheet` 控制采集启停、保留期、执行提供器、车辆置信度、车牌置信度、车道阈值、
  屏幕常亮；写 `SharedPreferences`，阈值通过 `SettingsOverrides` 叠加到清单配置上（不改清单文件）。

---

## 9. 数据、隐私与合规架构【代码】

### 9.1 本地存储

```text
detection_events
  id                 INTEGER PK AUTOINCREMENT
  trackId            INTEGER
  plateText          TEXT NULL          ← 可空；未确认时为 null
  confidence         REAL
  uncertain          INTEGER (bool)
  observedAtEpochMs  INTEGER
  createdAtEpochMs   INTEGER            ← v1→v2 迁移新增，DEFAULT 0
```

- 数据库 `roadvision.db`，版本 2，显式 `MIGRATION_1_2`，开启 `exportSchema`（`app/schemas`）。
- DAO：`observeAll()`（按观测时间倒序 Flow）、`insert`、`delete`、`deleteAll`、`deleteOlderThan`。
- **原始帧、裁剪图、OCR 中间结果均不落盘**；只有确认后的事件元数据进入 Room。

### 9.2 隐私边界

- 清单**不申请 `INTERNET`**；`usesCleartextTraffic="false"`、`allowBackup="false"`。
- 日志脱敏：`PrivacyLog.maskPlate()` 只保留首尾字符；完整车牌只在 UI 中由用户主动“查看详情”临时显示。
- 诊断报告由用户显式点击导出，写入 `cacheDir/exports` 并通过 `FileProvider` 分享，不自动上传。
- 前台服务通知文案明确“本地处理，不上传”。

### 9.3 模型准入

- SHA-256 不符 → 流水线失败，不会“带病运行”。
- 车辆权重（Ultralytics YOLO11n，AGPL-3.0）审查结论为 `BLOCKED`：仅用于内部链路与性能评测，
  `.onnx` 与本地 `model-bundle.json` 都被 `.gitignore` 排除；干净 clone 只有 `*.example` 清单。
- 车道走**无权重兜底**：清单写 `"lane": { "mode": "classical" }` 时使用 `ClassicalLaneDetector`，
  不需要权重与许可审查；出现 `assetPath` 则切回 ONNX 路径，上层调度与叠加不变。

---

## 10. 可观测性【代码】

`PerformanceRecorder` 是唯一指标源，`StateFlow<DiagnosticsState>` 同时供 UI 与报告使用：

| 指标组 | 字段 | 口径 |
| --- | --- | --- |
| 帧率 | `cameraFps`、`processedFps` | 2 s 滑动窗口内的到达速率 |
| 计数 | captured / processed / skipped / dropped / errors | “跳过”= 无活跃管线时的帧，不计入已处理 |
| 延迟 | `last/P50/P95 latencyMs` | **从分析器入口 `System.nanoTime()` 到处理结束**；负值计入 `clockAnomalies` 并钳为 0 |
| 时钟 | `sensorClockOffsetMs`、`sensorClockMismatch` | 传感器时间戳与 `nanoTime` 偏差 >1000 ms 时标记，提示不能混用 |
| 推理 | `inferenceP50/P95Ms`、`executionProvider` | 整段 `process()`（含预处理、全部模型与融合） |
| 设备 | `appPssMb`、`deviceUsedMemoryMb`、`thermalStatus` | 每 30 帧采样；PSS 与整机已用内存分开 |
| 几何 | `analysisWidth/Height`、`rotationDegrees`、`analysisFormat` | 来自真实分析帧，不假设分辨率 |
| 会话 | `sessionStartedAtEpochMs`、`sessionElapsedSeconds` | 从首个分析帧开始计，不从应用启动计 |

报告为**手写 JSON**（无第三方序列化），`schemaVersion = 3`，含应用版本、设备/SoC、请求与实际提供器、
模型角色/id/version/sha256、诊断、事件列表。

---

## 11. 测试与验证架构【代码】

- **仅 JVM 单元测试**：`app/src/test` 下 26 个测试类、90 个 `@Test`，覆盖旋转、YUV 转换、张量预处理、
  解码器（车辆/掩码/UFLD/CTC）、清单校验、调度背压、OCR 限频、跟踪、关联、融合、诊断口径、报告 JSON、
  设置覆盖、布局选择。`testOptions.unitTests.isReturnDefaultValues = true` 用于替身 Android 类。
- **无 androidTest 源码**：相机、前台服务、ONNX Runtime、Room 迁移都没有自动化 instrumentation 覆盖。
- **debug-only 合成基准**：`BenchmarkActivity` 生成 480×640 合成 YUV，直接喂生产管线（不经 CameraX），
  warm-up 3 帧 + 30 帧计时，写出 `files/benchmark.json`，用于把模型/预处理成本与相机、场景、热状态分离。
- **工具链**：`tools/export_vehicle_model.py`（YOLO 端到端导出并把 `[1,300,6]` 像素框归一化为 `[1,N,6]`）、
  `tools/evaluate_vehicle_models.py`（固定图集速度/召回对比）、`tools/prepare_lane_model.py`（CULane 裁剪 + 量化）。
- **验证命令**：`gradlew.bat testDebugUnitTest assembleDebug`。

【实测·STATUS】当前记录：开发机构建通过，生成 `app/build/outputs/apk/debug/app-debug.apk`（约 104 MB）；
车辆-only 明亮场景端到端 28.1 FPS、352 输入推理 P50/P95 ≈33/37 ms、延迟 ≈83 ms；暗光 6.4 FPS，
开启 classical 车道后约 6 FPS。**跨场景稳定 15 FPS 仍未验证，不能对外宣称达标。**

---

## 12. 已知技术债与架构风险

| # | 问题 | 影响 | 现状 |
| --- | --- | --- | --- |
| 1 | 所有模型在同一单线程 worker 上串行 | 车道/车牌会直接挤占车辆检测预算；无法按模型并行或分级超时 | 已用节流（1/3 帧）缓解，未解决 |
| 2 | `run()` 每帧把输出张量递归拍平成 `FloatArray` | 额外分配与拷贝，输出越大越明显 | 未优化 |
| 3 | 跟踪为纯 IoU 贪心，无运动模型/卡尔曼 | 遮挡、急加减速时 ID 切换 | 已知限制 |
| 4 | 车牌检测与 OCR 无权重，融合/关联/裁剪逻辑未被真实数据验证 | 车牌链路“代码就绪、能力未验证” | 2026-10-27 决定暂缓 |
| 5 | 车道无学习权重；且 UFLD 清单会被 `ModelBundleValidator` 的掩码形状校验拒绝 | 夜间、雨天、急弯丢线；UFLD 目前无法真正装载 | 解码器/stretch 已实现；候选权重 165 MB 未裁剪；启用前需改校验器 |
| 6 | 无 DI，ViewModel 直接 new 数据库/调度器/相机控制器 | 可测试性差，替换实现需改 ViewModel | 未重构 |
| 7 | `CaptureBridge` 是进程级可变单例 | 只支持单 Activity/单实例；进程内状态隐式耦合 | 未抽象 |
| 8 | 执行提供器通过反射调用 `addXnnpack`/`addNnapi` | ORT 升级改名即运行期失败 | 已有失败回退 CPU |
| 9 | 分析分辨率交给 CameraX 默认 | 不同机型分析尺寸不同，性能不可直接横比 | 仅记录不约束 |
| 10 | 仅支持 FLOAT32 输出 | 无法直接跑 INT8 量化模型 | 导出侧做量化，运行侧未扩展 |
| 11 | 无 instrumentation 测试 | 相机/服务/FGS 限制/DB 迁移回归靠手工 | 有设备测试计划 |
| 12 | 事件列表一次性加载全部行 | 事件量大时内存与重组成本上升 | 单机测试期可接受 |

---

## 13. 与目标架构文档的差异

| [ARCHITECTURE.md](ARCHITECTURE.md) 的描述 | 实际实现 |
| --- | --- |
| `ResultFusion` 独立组件 | 无此类；融合内联在 `PipelineCoordinator` |
| `diagnostics/DeviceInfo.kt` | `DeviceInfo` 是 `DiagnosticsReport.kt` 中的数据类 |
| “建议工程目录”含 `models/*/README.md` | 目录存在，但模型权重按许可策略不入库 |
| 目标为 ONNX 车道模型 | 掩码路径可用（`type = "mask"`）；UFLD 受校验器限制暂无法装载；默认走无权重 `ClassicalLaneDetector` |
| 车道/车牌均视为首版能力 | 车牌+OCR 暂缓；车辆-only 为当前可运行形态 |
| 未提及前台服务 | 已实现 `CaptureService` 持有 CameraX 生命周期，支持熄屏继续分析 |
| 未提及降级与回退 | 已实现“可选模型省略 + 提供器回退 CPU + 失败即停”的三层降级 |

---

## 14. 待确认（不得写成已完成）

1. 验收目标机是否就是已记录的 HONOR MLM-AN00 / SM8850 / Android 17（SDK 37）。
2. 暗光、夜间多车、逆光等场景能否稳定 15 FPS；当前仅有明亮场景复现。
3. 熄屏采集在 MagicOS 电源策略、Android 14/15 前台服务限制下的真实存活时间。
4. 车牌检测/OCR 权重、数据集许可与训练算力何时具备。
5. 车辆权重（AGPL-3.0）的商用授权或替代模型选型。
6. 固定视频集上的跟踪 ID 稳定性与车道准确率阈值。

---

## 附录：关键文件索引

| 文件 | 作用 |
| --- | --- |
| `app/build.gradle.kts` | 依赖与 SDK/Java 版本基线 |
| `app/src/main/AndroidManifest.xml` | 权限、前台服务、FileProvider、旋屏配置 |
| `.../capture/CaptureService.kt` | 前台 camera 服务与生命周期 |
| `.../camera/CameraController.kt` | CameraX 用例、分析器、旋转下发 |
| `.../camera/FrameConverter.kt` | YUV→RGB + 旋转单遍转换 |
| `.../pipeline/FrameScheduler.kt` | 单 worker + 一帧背压 + 安全关闭 |
| `.../pipeline/PipelineFactory.kt` | 装配、哈希校验、提供器回退、预热 |
| `.../pipeline/PipelineCoordinator.kt` | 单帧编排与融合 |
| `.../inference/OrtRuntimeManager.kt` | Session 缓存、执行提供器、读写锁 |
| `.../inference/TensorPreprocessor.kt` | letterbox/stretch、NCHW/NHWC、复用缓冲 |
| `.../inference/ModelBundleConfigLoader.kt` | 清单解析与显式降级语义 |
| `.../tracking/*.kt` | 跟踪、车牌关联、OCR 融合 |
| `.../data/*.kt` | Room v2 与仓储 |
| `.../diagnostics/PerformanceRecorder.kt` | 指标口径 |
| `.../ui/RoadScreen.kt` | 布局、权限、预览与叠加装配 |
| `app/src/debug/.../BenchmarkActivity.kt` | 合成管线基准 |
