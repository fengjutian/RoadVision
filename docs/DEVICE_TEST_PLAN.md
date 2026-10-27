# RoadVision 荣耀真机测试计划

## 1. 当前状态

-   荣耀具体机型：HONOR MLM-AN00（`Build.DEVICE` = HNMLM，SoC = SM8850）。
-   RAM：待实机记录。2026-10-27 导出中的 7,656 MB 是**整机已用内存**，不是可用容量。
-   Android / MagicOS：Android 17（SDK 37），构建
    `MLM-AN00 11.0.0.111(SP7C00E110R101P2)`。
-   本项目真机数据：**只有一次仅采集基线**。2026-10-27 导出 `cameraFps` 7.87、
    时长约 7 分钟、无推理。该导出为 `schemaVersion` 1，其 `processedFps`、延迟与内存字段
    口径错误（见第 5 节），**不得作为 15 FPS 或延迟验收数据**。
-   15 FPS：验收目标，不是已验证结论。

## 2. 测试前必须记录

-   手机品牌、完整型号、SoC、RAM、存储剩余空间
-   Android 版本、MagicOS 版本、构建号、安全补丁级别
-   APK versionCode/versionName、Git commit
-   每个模型的来源、版本、SHA-256、输入尺寸
-   ONNX Runtime 版本与执行提供器
-   摄像头分辨率、帧率、镜头、旋转、曝光条件
-   环境温度、初始电量、是否充电、屏幕亮度
-   视频场景与数据集版本、样本数、合法来源

## 3. 分阶段测试

1.  **静态图片基准：** PC 参考输出与 Android 输出一致性。
2.  **单模型真机：** 分别测车辆、车道、车牌/OCR。
3.  **流水线增量：** 逐个启用跟踪、融合、OCR，定位瓶颈。
4.  **执行提供器对比：** CPU 与目标设备支持的其他提供器分别测量。
5.  **持续 30 分钟：** 记录 FPS、延迟、内存、热状态和异常。
6.  **扩展 60--120 分钟：** 观察温控降频、系统终止、电量变化。
7.  **熄屏测试：**
    仅在确定需要熄屏运行时执行；核验前台服务、权限与系统行为。
8.  **场景评估：**
    白天、夜间、逆光、雨天（若安全可控）、遮挡、远距离、运动模糊。

## 4. 必报指标

-   CameraX 采集 FPS
-   有效端到端感知 FPS
-   延迟 P50/P95
-   丢帧比例与队列积压
-   车辆检测准确率指标
-   车道识别有效率/误差
-   Track ID 切换与跟踪失败
-   车牌检测召回率
-   OCR 字符级与整牌准确率
-   车牌---车辆关联错配率
-   内存、CPU、热状态、耗电变化
-   崩溃、ANR、模型加载失败、相机断开次数

## 5. FPS 验收口径

建议将有效端到端感知 FPS
定义为在固定窗口内，输出有效且完成融合的感知结果数除以窗口秒数。不要用相机预览帧率代替模型输出
FPS。

目标：持续稳定 15 FPS。首轮建议连续 30 分钟；扩展稳定性测试 60--120
分钟。团队需在正式验收前冻结允许的 P95 延迟、丢帧率、热状态和耗电阈值。

### 5.1 导出字段口径（`schemaVersion` ≥ 3）

从 schemaVersion 3 起，导出中的性能字段含义如下；旧导出的 `processedFps`
与延迟字段不可信（无流水线时也计为"已处理"，延迟被钳位到 0）。

-   `cameraFps`：CameraX 每秒送入分析器的帧数，**不等于**感知 FPS。
-   `processedFrames` / `processedFps`：仅在存在活跃流水线并完成一次处理后计数。
-   `skippedFrames`：帧到达调度器但没有活跃流水线，未被分析（模型未启用时的正常值）。
-   `droppedFrames`：只统计 `FrameScheduler` 内部丢弃的帧。`STRATEGY_KEEP_ONLY_LATEST`
    在分析器之前合并的帧不可见，**不能**用 0 推断"未丢帧"。
-   `latencyP50Ms` / `latencyP95Ms` / `lastLatencyMs`：`latencySource` 为
    `analyzer_ingress`，即"分析器入口 → 结果"，含帧转换与流水线耗时，**不含**相机传感器与
    传输延迟。该时钟与进程内其他测量同源，不受相机时间戳基准影响。
-   `sensorClockOffsetMs` / `sensorClockMismatch`：相机传感器时间戳与进程时钟的偏移；
    `true` 表示二者基准不同，不能用传感器时间戳计算延迟。
-   `clockAnomalies`：出现负延迟的样本数；非 0 说明时钟回跳，需要重测。
-   `memory.appPssMb`：本进程 PSS；`memory.deviceUsedMb`：整机已用内存（含其他应用）。
-   `analysis.width/height/rotationDegrees/format`：实际分析流尺寸、旋转与像素格式，用于
    解释 `cameraFps` 上限；分辨率由 CameraX 默认值决定，未显式指定。
-   `session.startedAtEpochMs` / `session.elapsedSeconds`：从第一帧采集开始计时。

## 6. 结果记录模板

  字段                 值
  -------------------- ------------------------
  测试日期/人员        待填
  设备型号 / SoC       待填
  Android / MagicOS    待填
  APK / Git commit     待填
  模型版本与 hash      待填
  执行提供器           待填
  输入尺寸 / 场景      待填
  测试时长             待填
  Camera FPS           待填
  有效端到端 FPS       待填
  延迟 P50 / P95       待填
  丢帧率               待填
  内存 / 温控 / 耗电   待填
  结论                 未测试 / 通过 / 未通过
  原始日志/视频引用    待填

## 7. 解释限制

不同手机、不同输入尺寸、不同模型和不同执行提供器之间不可直接类比。桌面
CPU/GPU 的公开 FPS 不能换算成荣耀手机
FPS。所有性能结论都必须来自实际目标设备。
