# 车道模型接入计划（CULane / UFLD）

本文件记录"用真实中国道路车道模型替换无权重兜底"这条线的全部事实、契约、命令与当前进度，
避免后续只能从提交历史里反推。

## 1. 结论先说

- 已找到可用且**源可连通**的模型：Ultra-Fast-Lane-Detection，CULane 训练，ResNet18。
- 上游代码 **MIT**，比车辆检测用的 Ultralytics（AGPL-3.0）干净。
- 解码器、拉伸预处理、清单字段均已实现并通过单测；**唯一未完成的是拿到权重并把它缩到能进
  APK 的体积**。
- 在模型就位前，车道走 `ClassicalLaneDetector`（无权重兜底），只用于跑通链路，不代表车道精度。

## 2. 模型事实

| 字段 | 值 |
|---|---|
| 名称 | Ultra-Fast-Lane-Detection（UFLD），CULane，backbone ResNet18 |
| 上游仓库 | https://github.com/cfzd/Ultra-Fast-Lane-Detection |
| 代码许可证 | MIT |
| 训练数据 | CULane（中国城区道路，4 条车道） |
| ONNX 直链 | `https://storage.googleapis.com/ailia-models/ultra-fast-lane-detection/culane_18.onnx` |
| 该直链可用性 | 实测可连通（HTTP 206 分段下载），但本机速率约 2 MB/分钟 |
| 原始体积 | 165,032,014 字节（约 165 MB，含训练用 aux 分割头） |
| 输入 | `input.1`，`[1, 3, 288, 800]`，NCHW，RGB，`scale = 1/255`，**stretch 缩放** |
| 输出 | `200`，`[1, 201, 18, 4]` = 200 个位置桶 + 1 个"无车道"类 × 18 行锚点 × 4 车道 |
| 行锚点 | `[121,131,141,150,160,170,180,189,199,209,219,228,238,248,258,267,277,287]`，除以 288 后写进清单 |

行锚点来自上游 `data/constant.py` 的 `culane_row_anchor`，不是推测值。

## 3. 代码侧已就绪

| 能力 | 位置 |
|---|---|
| UFLD 解码（softmax over 位置桶 → 期望位置；`argmax == 无车道类` 丢弃该行） | `UfldLaneDecoder.kt` |
| 拉伸缩放（UFLD 训练用 stretch，不是 letterbox） | `ModelContract.resizeMode` + `StretchTransform` + `TensorPreprocessor` |
| 解码器选择 | `LaneDecodeConfig.type = MASK / UFLD`，`OnnxLaneDetector` 按清单选择 |
| 单测 | `UfldLaneDecoderTest`（3 项）、`TensorPreprocessorTest`（stretch 2 项） |

## 4. 体积问题与处理顺序

原始 165 MB 无法进 APK（当前 APK 约 105 MB）。处理顺序：

1. **抽子图**：只用 `200` 输出。**实测结果：裁剪无效**——该导出本来就只声明 `200` 一个输出，
   178.1 MB 原样保留（`cls.0.weight` 14.8 MB、`cls.2.weight` 118.6 MB 是分类头本体，不是 aux 分支）。
   这也说明这个导出不是 cfzd 原版 `parsingNet` 的 1×1 卷积头，而是一个大 FC 头的变体。
2. **量化（本模型必须做）**：178 MB 无法进 APK，实际采用 QDQ INT8，**44.9 MB**。
3. 量化实测结论（都踩过）：
   - 原模型 opset 11，直接 QDQ 会生成带 `axis` 属性的 `DequantizeLinear`，加载报
     `Unrecognized attribute: axis`；**必须先 `onnx.version_converter.convert_version(model, 13)`**；
   - `quantize_dynamic` 对 conv 生成 `ConvInteger`，ONNX Runtime CPU/XNNPACK 不支持；
   - 含 NMS/动态形状的模型做 QDQ 会在 `ReduceMax` 崩，只有纯卷积结构可行。

### 4.1 量化精度实测（单张 CULane 样例图，16 个增广校准样本）

| 指标 | 数值 |
|---|---|
| fp32 体积 / 桌面推理 | 178.1 MB / 51 ms |
| INT8 体积 | 44.9 MB |
| 输出最大绝对差 / 平均绝对差 | 0.41 / 0.046 |
| argmax 类别一致率 | **93.06 %** |
| 每行峰值概率 min / median / max | 0.093 / 0.298 / 0.561 |

峰值概率中位数只有 0.298，所以清单里 `decode.threshold` 取 **0.12**；取 0.3 会丢掉一半有效行。
93 % 的一致率意味着 INT8 版本**有可测量的精度损失**，且校准集只有一张真实图，结论只能当
"链路可用"，不能当精度验收。fp32 版本留在 `.model-cache/lane/culane_18_cls.onnx`，删掉
`--quantise` 重新安装即可切换。

## 5. 命令

```bash
# 下载（断点续传）+ 抽子图 + 校验形状 + 安装到 assets，全自动
python tools/prepare_lane_model.py

# 模型已在 .model-cache/lane/culane_18.onnx 时跳过下载
python tools/prepare_lane_model.py --skip-fetch

# 需要量化时（默认不量化）
python tools/prepare_lane_model.py --skip-fetch --quantise qdq
```

脚本最后会打印 `sha256`、体积、输入输出名，直接填进清单。

## 6. 启用方式（本地 `model-bundle.json`，该文件不入库）

```json
"lane": {
  "assetPath": "models/lane/model.onnx",
  "decode": { "type": "ufld", "threshold": 0.3, "rowAnchors": [0.4201, 0.4549, "…18 项…"] },
  "contract": {
    "id": "ufld-culane-res18-288x800",
    "version": "1.0-evaluation",
    "sha256": "<脚本输出的哈希>",
    "input": { "name": "input.1", "shape": [1, 3, 288, 800] },
    "outputs": [{ "name": "200", "shape": [1, 201, 18, 4] }],
    "layout": "NCHW",
    "colorOrder": "RGB",
    "scale": 0.0039215686,
    "resize": "stretch"
  }
}
```

删掉 `"mode": "classical"` 即切到 ONNX 车道；`assetPath` 出现后 `PipelineFactory` 自动选
`OnnxLaneDetector`，调度、坐标映射、叠加层都不变。

## 7. 当前进度

| 步骤 | 状态 |
|---|---|
| 找到可连通的中国道路车道模型 | 完成 |
| 解码器 / stretch / 清单字段 | 完成（单测通过） |
| 下载 178.1 MB 权重 | 完成（8 路并行分段下载，约 14 分钟；单连接实测被限速到 ~25 kB/s） |
| 抽子图 + 体积评估 | 完成（裁剪无收益，仍 178.1 MB） |
| INT8 量化 | 完成（44.9 MB，argmax 一致率 93.06 %） |
| CULane 样例图核对解码 | 完成（4 条车道从 `y≈0.49` 收敛点向右下展开，结构与标注一致） |
| 接进清单 + 出 APK | 完成（本机 APK 141 MB，`assets/models/lane/model.onnx` 44.9 MB 已打入） |
| 真机看效果 | **待测**（需要装 APK 后导出诊断） |

模型哈希：`ce392a1948477a1cff9e8847c948eae217a32c585948390bd81c846a7a36e0c5`（INT8，288×800）。

## 8. 已知限制

- 即便接入成功，UFLD 在夜间、强反光、施工改道等场景会明显退化；这属于模型能力边界，不是接线问题。
- `resize: "stretch"` 会让 4:3 的手机画面在横向被压缩，与 CULane 的 2.78:1 训练比例不同；
  必要时可按 800×288 的比例先裁剪画面再送入，属于后续调优项。
- INT8 只用一个真实场景图做校准，精度结论不可外推；正式评测前应换成 CULane 测试集的一批
  真实图片重新校准，并与 fp32 版本做逐帧对照。
