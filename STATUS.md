# RoadVision 项目状态说明

本仓库包含立项文档及首批 Android 实现。2026-10-27 已完成 P1
工程骨架的开发机基线验证；这不代表完整感知流水线或真机验收已经完成。

**已实现并通过开发机构建：**

- Kotlin/Compose Android 工程与 Gradle Wrapper；
- CameraX 后置相机预览和 `ImageAnalysis`；
- 相机权限请求、拒绝提示和生命周期释放；
- YUV_420_888 到 RGB 转换及 0/90/180/270 度旋转；
- 最多保留一个待处理帧的 `FrameScheduler`；
- 采集 FPS、处理 FPS、丢帧数和端到端延迟诊断面板；
- 帧旋转、调度背压和性能计数单元测试。
- ONNX Runtime Android Session 复用、CPU/XNNPACK/NNAPI 配置入口和模型 SHA-256 校验；
- NCHW/NHWC、RGB/BGR、letterbox 张量预处理以及命名输出提取；
- 模型张量形状、NaN/Inf、置信度、类别和坐标范围校验；
- 车辆检测 NMS、车道概率图、车牌检测和 CTC OCR 适配器；
- 临时 Track ID、丢帧/超时生命周期、车牌与车辆几何关联；
- 车牌四角裁剪、清晰度拒识、OCR 限频、多帧融合与不确定状态；
- P2–P5 端到端 `PipelineCoordinator` 及分频调度；
- Room v2 事件库、显式迁移、保留策略、单条/全部删除和日志脱敏；
- 车辆/车道叠加、事件列表，以及 E2E/推理 P50/P95、内存、热状态诊断字段；
- 模型清单结构校验（资产路径、NCHW/NHWC 通道、检测/掩码/CTC 输出形状）与启动时配置加载；
- 置信度、NMS IoU、最大候选数、车道概率阈值可从 `model-bundle.json` 配置并在设置页覆盖；
- 车牌-车辆关联要求连续多帧几何一致，未确认的关联不会写入本地事件；
- OCR 调度、融合和已落库标记按活跃 Track 清理，长稳运行不随轨迹数无界增长；
- 推理失败后关闭并重载流水线时复用进程级 ORT 环境，避免重试链路失效；
- XNNPACK/NNAPI 初始化失败自动回退 CPU，Session 在开始采集前预加载并失败即停；
- 诊断面板增加错误计数；设置页支持停止/开始采集、保留期、Provider、阈值和模型信息；
- 诊断报告导出（JSON，含设备/构建/模型 SHA-256/性能/事件），通过 FileProvider 分享；
- 保持屏幕常亮开关，避免 30 分钟测试因系统熄屏中断；
- 前台 `CaptureService` 持有 CameraX 生命周期，Activity 停止或熄屏后继续采集，通知栏可返回应用；
  UI 只负责挂载预览 surface，不再直接绑定相机；
- 车辆-only 降级模式：清单中 `vehicle` 必填，`lane` 与 `plate`+`ocr` 可整段省略；省略的能力不加载、
  不产生结果，UI 明确显示降级模式；
- 诊断口径区分采集、分析、跳过：没有活跃流水线时帧只计为跳过，不再计为已处理，分析 FPS 和延迟显示
  “—”，并显示“推理未启用”；
- 车辆检测已接入评测权重：`tools/export_vehicle_model.py` 把 YOLO11 内置 NMS 的 `[1,300,6]`
  像素坐标归一化为契约要求的 `[1,N,6]`，app 侧零改动；`labels` 同时作为类别白名单，COCO
  中的人、自行车等不会进入车辆跟踪；letterbox 填充值改为可配置（默认 114 灰），与 YOLO
  训练预处理一致；
- 车道当前走**无权重兜底**：清单里 `"lane": { "mode": "classical" }` 选用 `ClassicalLaneDetector`
  （下半幅垂直边缘 → (斜率, 截距) 投票 → 左右各一条线），不需要权重也不需要许可审查；同一份
  代码里 `assetPath` 一旦出现就切回 ONNX 车道模型，上层调度与叠加层不变。掩码解码同时升级为
  多车道：行内非极大值抑制 + 邻近行连线，最多输出 3 条折线，不再把左右车道平均成一条；
- 叠加层改为与 CameraX `PreviewView` 同一 Android 视图层级的 `RoadOverlayView`，避免 Compose Canvas
  被相机预览遮挡；已用临时常量输出模型在真机确认车辆框、Track ID 和置信度标签可见；
- 横竖屏自适应布局：按窗口尺寸切换；横屏把诊断/事件放进右侧栏、相机画面占满左侧剩余区域，竖屏折叠为
  紧凑摘要（状态 + 采集/分析 FPS + 延迟 + 检测数），按“详情”展开受高度上限约束的完整诊断与事件列表；
  控件按状态栏/挖孔/手势条安全区内缩；
- 屏幕旋转时把显示方向下发给 `ImageAnalysis`，避免分析帧与叠加层停留在旋转前的方向；Activity 声明
  `configChanges`，旋屏不重建界面、不中断已绑定的预览与前台采集。

验证命令：`gradlew.bat testDebugUnitTest assembleDebug`。开发机结果为
`BUILD SUCCESSFUL`，当前共有 83 项单元测试，生成
`app/build/outputs/apk/debug/app-debug.apk`。本地评测构建已接入车辆检测权重
（Ultralytics YOLO11n，APK 内含 `model-bundle.json` 与 `models/vehicle/model.onnx`）；
车道 classical 实现已提供，可通过清单 `lane.mode = "classical"` 启用；当前性能评测 APK 使用
vehicle-only 清单。车牌与 OCR 仍无权重，清单中省略后不加载也不产生结果。该车辆权重为
AGPL-3.0、审查结论 `BLOCKED`，只用于内部链路与性能评测，权重文件与本地评测清单均不入库（见
`docs/DEPENDENCIES.md` 5.1）；干净 clone 只保留示例清单，需本地放置清单和权重。
车辆-only 模板见 `app/src/main/assets/models/model-bundle.vehicle-only.json.example`，
用于在其余权重未就绪时先验证车辆检测链路。

布局验证：2026-10-27 在 Android 37 模拟器（1080×2400、420 dpi）上确认竖屏紧凑面板、展开/收起、
横屏右侧诊断栏（画面占满剩余区域），以及旋屏后 `analysis.rotationDegrees` 从 90° 更新为 180°、
分析尺寸从 480×640 变为 640×480；多次旋转未崩溃。模拟器 CPU 推理约 1.5 s/帧，其 FPS 与延迟不能作为
性能数据。目标荣耀真机上的横竖屏布局、叠加对齐和旋屏行为仍未验证（见 `docs/DEVICE_TEST_PLAN.md`
第 3 节第 9 项）。

真机数据现状：2026-10-27 在 HONOR MLM-AN00（Android 17 / SDK 37）导出过一次**仅采集**
基线，`cameraFps` 7.87、约 7 分钟、无推理；该导出为 `schemaVersion` 1，其
`processedFps`、延迟与内存口径错误，不能作为 15 FPS 或延迟结论。导出已升级到
`schemaVersion` 3：延迟改由分析器入口单调时钟计算并附带相机时钟偏移/异常计数，内存区分
进程 PSS 与整机已用内存，新增实际分析尺寸/旋转/格式与采集时长，字段口径见
`docs/DEVICE_TEST_PLAN.md` 第 5.1 节。

同一天另做了一次车辆-only 短时真机验证：同一台 MLM-AN00 上加载 YOLO11n 评测权重，
CPU 推理，采集 5.7 FPS、分析 4.9 FPS，推理 P50/P95 154.3/216.3 ms，丢弃 33 帧；当前镜头内
无车辆，检测计数为 0。该结果只证明车辆-only 链路可运行，**不构成 15 FPS 或准确率结论**。

同一模型的执行提供器对比（均为短时手持、同一台 MLM-AN00，不能替代正式基准）：
CPU 推理 P50/P95 约 154/216 ms，表现最好；XNNPACK 约 323/497 ms，明显更慢；NNAPI
在首次推理时报 `ORT_FAIL`。`PipelineFactory` 现在会在开始采集前用零输入预热每个已声明模型，
因此 NNAPI 这类“Session 能建、第一次 run 失败”的提供器会回退 CPU，而不是把整条流水线切成
Failed。15 FPS 目标仍未达到。

输入尺寸与预处理对比已单独记录在 `docs/VEHICLE_PERFORMANCE.md`：固定集上 416/384/352 都是
5/5 通过，320 降到 4/5；真机车辆-only 从 640 的约 230 ms 推理降到 416 的约 50–70 ms，持续
30 秒约 13.7 FPS（瞬时 15.4）；352 实际检出 1 辆车（82%），416 在夜间多车画面实际检出 5 辆
（置信度 35%–64%），但该密集场景只有 3.4 FPS、推理 P50 146.7 ms。开启 classical lane 后约
6 FPS，车道成为新的主要开销。15 FPS 目前只在较简单场景瞬时可达到，不能宣称稳定达标。
该文档同时记录了固定集是单图合成缩放、不是道路数据集的限制。

**已确定的项目决定：** 项目名
RoadVision；默认本地处理，未来允许用户主动上传；首版车辆检测、车道识别、跟踪、车牌
OCR；交付内部测试 APK；持续稳定 15 FPS 为目标；技术路线 Kotlin +
CameraX + YOLO ONNX + ONNX Runtime；闭源商业用途。

**尚未完成的前置任务：** 1. 确认荣耀真机型号、SoC、Android/MagicOS
版本；导出已记录 MLM-AN00 / SM8850 / Android 17（SDK 37），仍需确认该机即为验收目标机。
2. 完成模型代码、权重、训练数据、OCR 字库及所有依赖许可证审查。
3. 为已批准模型填写精确输入输出契约、标签/字符集并加入资产。 4.
在真机验证连续采集、旋转、权限撤销和资源释放。 5.
建立真机性能基准；目前只有仅采集基线（7.87 FPS、无推理），15 FPS 与延迟仍无实测结论。 6.
冻结测试视频/数据集与准确率验收阈值。 7.
车牌与 OCR：推理、矫正、融合、关联代码已实现但无权重；2026-10-27 决定**暂缓开发**（不新建训练
工程），方案、路线对比、前置条件与工作拆解见 `docs/PLATE_RECOGNITION_PLAN.md`，恢复前需先确认
数据集许可与训练算力。

**仍需真机验证：** 熄屏采集依赖前台 `camera` 类型服务，通知权限、Android 14/15
前台服务限制和 MagicOS 电源策略必须在目标荣耀机型上实测；当前没有真机结果。
固定视频上的跟踪/关联准确率评估、真实模型下的静态图基准和 P6 长稳/场景测试也尚未执行。

文档中所有模型、版本、性能和准确率未被明确标记为实测的内容，均不可对外宣称为已验证事实。
