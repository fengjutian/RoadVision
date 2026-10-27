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
  “—”，并显示“推理未启用”。

验证命令：`gradlew.bat testDebugUnitTest assembleDebug`。开发机结果为
`BUILD SUCCESSFUL`，当前共有 59 项单元测试，生成
`app/build/outputs/apk/debug/app-debug.apk`。APK 已包含 P2–P5 运行时代码，
但不包含车辆、车道、车牌或 OCR 权重；在合法模型资产与模型卡就绪前，UI 明确保持模型禁用状态。
车辆-only 模板见 `app/src/main/assets/models/model-bundle.vehicle-only.json.example`，
用于在其余权重未就绪时先验证车辆检测链路。

真机数据现状：2026-10-27 在 HONOR MLM-AN00（Android 17 / SDK 37）导出过一次**仅采集**
基线，`cameraFps` 7.87、约 7 分钟、无推理；该导出为 `schemaVersion` 1，其
`processedFps`、延迟与内存口径错误，不能作为 15 FPS 或延迟结论。导出已升级到
`schemaVersion` 3：延迟改由分析器入口单调时钟计算并附带相机时钟偏移/异常计数，内存区分
进程 PSS 与整机已用内存，新增实际分析尺寸/旋转/格式与采集时长，字段口径见
`docs/DEVICE_TEST_PLAN.md` 第 5.1 节。

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
冻结测试视频/数据集与准确率验收阈值。

**仍需真机验证：** 熄屏采集依赖前台 `camera` 类型服务，通知权限、Android 14/15
前台服务限制和 MagicOS 电源策略必须在目标荣耀机型上实测；当前没有真机结果。
固定视频上的跟踪/关联准确率评估、真实模型下的静态图基准和 P6 长稳/场景测试也尚未执行。

文档中所有模型、版本、性能和准确率未被明确标记为实测的内容，均不可对外宣称为已验证事实。
