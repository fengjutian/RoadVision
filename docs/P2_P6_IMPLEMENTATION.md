# RoadVision P2–P6 实施与验收说明

更新日期：2026-10-27

## 1. 状态定义

- `IMPLEMENTED`：代码已实现并通过开发机单元测试/构建。
- `READY_FOR_ASSET`：适配层已实现，等待通过许可证审查的模型资产。
- `DEVICE_REQUIRED`：必须在指定荣耀真机上验证，不得以开发机结果代替。
- `BLOCKED`：缺少许可证证据、模型卡、设备或合法数据集，不得进入发布候选。

## 2. P2：ONNX Runtime 与基础模型

| 项目 | 状态 | 产物/说明 |
|---|---|---|
| ORT Session 生命周期 | IMPLEMENTED | `OrtRuntimeManager` 按模型路径和执行提供器复用 Session；关闭时只释放 Session，保留进程级 ORT 环境以支持重载 |
| 模型 I/O 契约 | IMPLEMENTED | `ModelContract`、`TensorSpec`；强制模型版本、SHA-256、布局、颜色和归一化信息 |
| 模型清单校验 | IMPLEMENTED | `ModelBundleValidator` 校验资产路径限制在 `models/`、固定输入维度、NCHW/NHWC 通道数，以及检测 `[1,N,6]`、车道 `[1,1,H,W]`、OCR `[1,T,字母表+1]` 输出形状 |
| 解码参数配置 | IMPLEMENTED | `DetectionDecodeConfig`、`LaneDecodeConfig`；置信度、NMS IoU、最大候选数、车道阈值来自清单 `decode` 块，可由设置页覆盖 |
| 执行提供器回退 | IMPLEMENTED | 四个 Session 在开始采集前预加载；XNNPACK/NNAPI 失败自动重试 CPU，状态栏显示回退 |
| 输出校验 | IMPLEMENTED | 维度、NaN/Inf、置信度、类别和归一化坐标检查 |
| 坐标映射 | IMPLEMENTED | letterbox 正反向映射，坐标统一为预览归一化坐标 |
| 车辆模型适配 | READY_FOR_ASSET | 支持 `[1,N,6]` 检测输出的阈值与 NMS；具体模型需填模型卡 |
| 车道模型适配 | READY_FOR_ASSET | 支持二值概率掩码解码；具体输出必须与模型卡一致 |
| CPU/XNNPACK/NNAPI | DEVICE_REQUIRED | 三种配置入口已提供；必须逐一记录成功率、回退和真机数据 |
| 性能统计 | IMPLEMENTED | 处理 FPS、E2E P50/P95、推理 P50/P95、进程内存、热状态和错误计数 |

仓库不包含 ONNX 权重。将模型放入 APK 前，必须在
[`MODEL_EVALUATION.md`](MODEL_EVALUATION.md) 中将状态更新为 `APPROVED`，填写 SHA-256，
并使运行时契约与模型真实输入输出完全一致。

模型批准后，由 `PipelineFactory` 统一激活四个资产。工厂会在创建任何 Session 前验证全部
SHA-256；任何一个资产失败都会终止整条流水线，不允许部分模型静默运行。

配置方式：复制 `app/src/main/assets/models/model-bundle.json.example` 为
`model-bundle.json`，逐项替换经过审查的路径、64 位 SHA-256、张量名称/形状、标签、OCR
字符集及 blank 索引。车辆/车牌可增加 `decode` 块配置 `confidence`、`nmsIou`、
`maxDetections`，车道可配置 `decode.threshold`；未填写时使用代码默认值。应用启动后在后台
解析并校验；缺少清单时保持 `NotConfigured`，加载或推理失败时清除旧叠加并显示错误，可由
用户手动重试。清单结构错误（路径越界、维度不匹配、输出通道与字母表不一致）会在创建任何
Session 前直接失败，并显示具体原因。设置页的覆盖值只保存在本机偏好设置中，不修改资产清单。

## 3. P3：车辆跟踪与叠加

`VehicleTracker` 使用类别一致性和 IoU 关联，时间戳必须单调。轨迹允许短时丢帧，超过
`maximumMissedFrames` 或 `maximumIdleNs` 后删除。Track ID 仅为当前运行时临时编号，不能用于身份识别。

`RoadOverlay` 绘制车辆框、类别、置信度、Track ID 和归一化车道点。模型坐标必须先经
`LetterboxTransform.modelToSource` 映射；CameraX 预览采用 `FILL_CENTER` 时，真机测试还需验证预览视口裁剪映射。

## 4. P4：车牌与 OCR

处理顺序：车牌检测候选 → 四角裁剪/矫正 → 尺寸与清晰度检查 → OCR 限频 → CTC 解码 →
同 Track ID 多帧融合 → 车辆关联 → 本地事件。

- `PlateCropper` 对四角区域进行目标尺寸重采样，并以拉普拉斯方差判断模糊。
- `OcrScheduler` 同时限制最小时间间隔和单轨迹最大尝试次数。
- `OcrTemporalFusion` 以文本置信度加权；观察次数或置信度不足时必须标记 `uncertain`。
- `PlateVehicleMatcher` 要求车牌在连续多帧内保持与同一车辆轨迹的几何一致；未达到
  `minimumConsecutiveFrames` 的关联只用于 OCR 候选，不写入本地事件，避免单帧位置误关联。
- 每帧结束前按 `VehicleTracker` 当前活跃 Track 清理 OCR 调度、融合历史、关联历史和已落库
  标记，避免长时间运行或大量短暂目标导致状态无界增长。
- OCR 字符集、空白索引和车牌规范必须来自已审查模型卡，不得猜测。

## 5. P5：本地事件与隐私

- Room 数据库当前 schema 版本为 2，包含显式 `1 → 2` 迁移。
- 支持 Flow 事件列表、单条删除、全部删除和默认 30 天保留接口。
- 原始帧不落盘；Manifest 不申请网络权限。
- `PrivacyLog` 仅输出脱敏车牌。禁止绕过该入口把 OCR 原文写入 Logcat、崩溃平台或分析事件。
- 设置页可显式导出 JSON 诊断报告：设备/系统构建、App 版本、模型 ID/版本/SHA-256、Provider、
  FPS/P50/P95/丢帧/错误/内存/热状态和本地事件。报告只写入应用缓存并经 FileProvider 分享，
  不产生网络请求；报告包含事件中的完整车牌文本，需按隐私要求保管。
- UI 明确显示模型未启用状态，模型或相机错误不能用旧结果伪装实时结果。
- 相机错误可由用户显式重启，权限被永久拒绝时可跳转应用设置；模型失败会清空叠加并提供重新加载入口。
- 设置页可停止/开始采集、选择保留期（7/30/90 天）、覆盖执行提供器和阈值、查看模型信息。

## 6. P6：真机验收与发布

以下项目当前均为 `DEVICE_REQUIRED`，必须使用
[`P6_TEST_REPORT_TEMPLATE.md`](P6_TEST_REPORT_TEMPLATE.md) 记录原始数据：

1. 30 分钟持续运行；
2. 60–120 分钟扩展运行；
3. 白天、夜间、逆光、遮挡和运动模糊；
4. 如纳入范围，验证前台服务、熄屏权限与 MagicOS 电源管理；当前仅实现前台常亮和手动停止采集，
   熄屏后台采集仍需将 CameraX 所有权迁到前台服务，尚未实现；
5. CPU、XNNPACK、NNAPI A/B 测试；
6. 车辆、车道、车牌和 OCR 准确率及失败案例；
7. APK、Git commit、模型 hash、SBOM、许可证证据一一对应。

当前构建只能视为开发基线，不是内部发布候选 APK。

## 7. 发布阻断条件

- 任一模型/权重/训练数据/OCR 字符集为 `UNKNOWN`、`BLOCKED` 或缺少证据；
- 未记录目标荣耀设备和系统构建号；
- 未执行长稳测试，或存在持续内存增长、热崩溃、相机资源泄漏；
- 日志出现完整车牌或应用存在未说明的网络传输；
- 模型输出契约与实际张量不一致；
- 性能/准确率报告没有原始数据、配置和失败案例。
