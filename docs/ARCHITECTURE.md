# RoadVision 技术架构方案

> 本文是设计与目标方案，描述“应当如何”。仓库当前代码实际如何组织、有哪些实现偏差与已知技术债，
> 见 [当前技术架构（As-Built）](ARCHITECTURE_CURRENT.md)。

## 1. 设计原则

1.  本地优先：首阶段推理、结果融合和事件存储均在设备端完成。
2.  模型可替换：不将业务逻辑绑定到某一个 YOLO 导出版本或 OCR 框架。
3.  有界实时流水线：优先处理最新帧，避免队列堆积造成延迟持续增长。
4.  可测量：每一阶段暴露耗时、帧时间戳、异常和资源指标。
5.  合规优先：模型、权重、数据、SDK 与依赖许可证审查是模型接入门禁。
6.  安全失败：模型不可用时明确提示，不用旧结果伪装当前结果。

## 2. 逻辑架构

``` text
┌────────────────────────────── UI Layer ──────────────────────────────┐
│ RoadScreen │ Camera Preview │ Overlay │ Event List │ Diagnostics     │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ UI State / Flow
┌───────────────────────────────▼──────────────────────────────────────┐
│ Application / Orchestration                                          │
│ FrameScheduler │ PipelineCoordinator │ ResultFusion │ Settings       │
└───────────────┬───────────────────────────┬──────────────────────────┘
                │ Frame                      │ PerceptionResult
┌───────────────▼──────────────┐  ┌──────────▼─────────────────────────┐
│ Camera Layer                 │  │ Perception Layer                    │
│ CameraX / FrameConverter     │  │ VehicleDetector (ONNX)               │
│ Rotation / Crop / Backpressure│ │ LaneDetector (ONNX)                  │
└──────────────────────────────┘  │ PlateDetector (ONNX)                 │
                                  │ PlateRecognizer (OCR adapter)         │
                                  └──────────┬───────────────────────────┘
                                             │ detections / timestamps
                                  ┌──────────▼───────────────────────────┐
                                  │ Tracking & Fusion                    │
                                  │ VehicleTracker │ PlateVehicleMatcher │
                                  │ Multi-frame OCR fusion               │
                                  └──────────┬───────────────────────────┘
                                             │
                                  ┌──────────▼───────────────────────────┐
                                  │ Data Layer                           │
                                  │ Room │ Repository │ Retention/Delete │
                                  └──────────────────────────────────────┘
```

## 3. 建议工程目录

``` text
RoadVision/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/<package>/roadsense/
│       │   ├── ui/
│       │   │   ├── RoadScreen.kt
│       │   │   ├── RoadOverlay.kt
│       │   │   ├── SettingsSheet.kt
│       │   │   ├── PlateEventList.kt
│       │   │   └── DiagnosticsPanel.kt
│       │   ├── camera/
│       │   │   ├── CameraController.kt
│       │   │   └── FrameConverter.kt
│       │   ├── capture/
│       │   │   ├── CaptureService.kt
│       │   │   └── CaptureBridge.kt
│       │   ├── inference/
│       │   │   ├── OrtRuntimeManager.kt
│       │   │   ├── ModelContract.kt
│       │   │   ├── VehicleDetector.kt
│       │   │   ├── LaneDetector.kt
│       │   │   ├── PlateDetector.kt
│       │   │   └── PlateRecognizer.kt
│       │   ├── tracking/
│       │   │   ├── VehicleTracker.kt
│       │   │   ├── PlateVehicleMatcher.kt
│       │   │   └── OcrTemporalFusion.kt
│       │   ├── pipeline/
│       │   │   ├── FrameScheduler.kt
│       │   │   ├── PipelineCoordinator.kt
│       │   │   └── ResultFusion.kt
│       │   ├── data/
│       │   │   ├── AppDatabase.kt
│       │   │   ├── DetectionEventEntity.kt
│       │   │   └── DetectionRepository.kt
│       │   └── diagnostics/
│       │       ├── PerformanceRecorder.kt
│       │       └── DeviceInfo.kt
│       └── res/
├── models/
│   ├── vehicle/README.md
│   ├── lane/README.md
│   ├── plate/README.md
│   └── ocr/README.md
├── docs/
└── README.md
```

`<package>`
在工程初始化时替换为真实包名。模型文件不应在未经许可证审查前直接提交到仓库。

## 4. 关键数据契约

### FrameInput

-   `frameId: Long`
-   `timestampNs: Long`
-   `width: Int`
-   `height: Int`
-   `rotationDegrees: Int`
-   `image/tensor buffer`
-   `sourceCrop` 与预处理元数据

### Detection

-   `classId: Int`
-   `label: String`
-   `confidence: Float`
-   `box: RectF`（统一映射到原始预览坐标）
-   `frameId`、`timestampNs`

### LaneResult

-   车道线点集/分割掩码或模型定义的结果
-   `confidence` 或质量标志
-   `frameId`、`timestampNs`

### PlateReadResult

-   车牌区域坐标
-   OCR 文本（内存中使用；日志默认脱敏）
-   字符/整牌置信度
-   `trackId?`
-   `frameId`、`timestampNs`
-   `qualityFlags`

### FusedPerceptionResult

-   同一时间基准下的车辆、车道、车牌与跟踪结果
-   结果新鲜度与模型版本
-   不允许旧结果无标记地覆盖新帧结果

## 5. 推理运行时策略

-   第一步以 ONNX Runtime Android CPU 执行提供器建立基线。
-   若构建与设备支持，单独对 XNNPACK、NNAPI 等执行提供器做 A/B
    基准；不能仅因 API 可用就认定更快。
-   设备专用加速（例如 Qualcomm
    QNN）作为后续候选，前提是设备、运行时版本、算子支持、模型转换链路和许可均通过核验。
-   每个 Session 只初始化一次并复用；明确关闭资源。
-   不在 UI 线程运行推理。
-   推理线程/协程有界；处理速度跟不上时丢弃旧帧，而不是排队。
-   记录预处理、推理、后处理和融合各自耗时。

## 6. 实时流水线

1.  CameraX 输出帧。
2.  FrameConverter 处理 YUV/RGB 转换、旋转和缩放。
3.  FrameScheduler 决定该帧执行哪些模型。
4.  车辆检测与车道识别按策略运行。
5.  VehicleTracker 更新 Track ID。
6.  车牌检测按频率/候选区域触发。
7.  OCR 仅对满足质量条件的车牌候选执行。
8.  OcrTemporalFusion 对同一车辆多帧结果投票/加权。
9.  PlateVehicleMatcher 关联车牌与车辆轨迹。
10. ResultFusion 生成统一结果并更新 UI；符合存储条件的事件写入 Room。

为达到 15
FPS，先建立测量再优化。可采用分层调度：车辆检测为主、车道低频更新、车牌/OCR
按需触发。调度频率必须写入测试报告。

## 7. 生命周期与熄屏运行

首阶段优先验证亮屏运行；熄屏连续运行当前由 `capture/CaptureService.kt` 以前台 `camera`
类型服务持有 CameraX 生命周期实现：Activity/预览停止后 `ImageAnalysis` 继续送入
`FrameScheduler`，通知栏常驻提示，用户在设置页可随时停止。必须按目标 Android 版本核验
Camera 前台服务类型、运行时权限、通知和系统限制；荣耀 MagicOS 的电源管理行为必须真机
验证，不可承诺一定不被系统终止。

## 8. 存储与隐私

-   Room 存储事件元数据与用户选择保留的识别结果。
-   原始视频帧默认不落盘。
-   完整车牌不进入普通日志、崩溃日志或分析事件。
-   用户可以清除事件记录。
-   网络权限应遵循最小化原则；首阶段无自动上传。
-   将来增加上传时，必须有独立的显式操作、上传预览、目的说明、失败处理、取消和删除机制。

## 9. 许可证与模型准入

每个模型必须具备：来源 URL、版本/commit、权重文件
hash、代码许可证、权重许可证、训练数据许可说明、商用/再分发条款、导出与修改条款、归档证据。

YOLO/ONNX 仅描述模型结构或格式，不能代替许可证结论。若使用 Ultralytics
AGPL-3.0
路线，需在纳入闭源分发前取得合适授权并经审查，或改用许可证明确适合该用途的替代模型。模型权重和数据集必须单独核验。

## 10. 可观测性

最低指标： - camera FPS / processed FPS / dropped frames - preprocess /
inference / postprocess / fusion latency - end-to-end latency P50/P95 -
OCR invocation rate - memory / CPU / battery delta / thermal
status（设备允许时） - crash / exception / model load failure counts

所有报告需附构建版本、Git commit、设备型号、系统版本、模型 hash
和执行提供器。
