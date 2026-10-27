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
- 车辆/车道叠加、事件列表，以及 E2E/推理 P50/P95、内存、热状态诊断字段。

验证命令：`gradlew.bat testDebugUnitTest assembleDebug`。开发机结果为
`BUILD SUCCESSFUL`，当前共有 21 项单元测试，生成
`app/build/outputs/apk/debug/app-debug.apk`。APK 已包含 P2–P5 运行时代码，
但不包含车辆、车道、车牌或 OCR 权重；在合法模型资产与模型卡就绪前，UI 明确保持模型禁用状态。

**已确定的项目决定：** 项目名
RoadVision；默认本地处理，未来允许用户主动上传；首版车辆检测、车道识别、跟踪、车牌
OCR；交付内部测试 APK；持续稳定 15 FPS 为目标；技术路线 Kotlin +
CameraX + YOLO ONNX + ONNX Runtime；闭源商业用途。

**尚未完成的前置任务：** 1. 确认荣耀真机型号、SoC、Android/MagicOS
版本。 2. 完成模型代码、权重、训练数据、OCR 字库及所有依赖许可证审查。
3. 为已批准模型填写精确输入输出契约、标签/字符集并加入资产。 4.
在真机验证连续采集、旋转、权限撤销和资源释放。 5.
建立真机性能基准；当前无 RoadVision 真机 FPS 实测结果。 6.
冻结测试视频/数据集与准确率验收阈值。

文档中所有模型、版本、性能和准确率未被明确标记为实测的内容，均不可对外宣称为已验证事实。
