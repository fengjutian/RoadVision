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

1. **抽子图**（必做）：只用 `200` 输出，`onnx.utils.extract_model` 会顺带丢掉 aux 分割分支的
   权重。预期降到 60 MB 上下，具体以脚本输出为准。
2. **判断是否需要量化**：裁剪后若 ≤ 60 MB，直接发 fp32——不动精度，风险最低。
3. **仅在仍过大时做 INT8**：QDQ 静态量化 + 校准集。注意两点实测结论：
   - `quantize_dynamic` 对 conv 会生成 `ConvInteger`，ONNX Runtime CPU/XNNPACK **不支持**，模型
     直接加载失败；必须用 `quantize_static`（QLinearConv）。
   - 含 NMS/动态形状的模型（如车辆模型）做 QDQ 会在 `ReduceMax` 崩，车道这种纯卷积结构才行。
   - 校准集不要用随机噪声当最终依据，只用于体积/耗时摸底。

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
| 下载 165 MB 权重 | **进行中**（本机约 2 MB/分钟，脚本断点续传、自动重试） |
| 抽子图 + 体积评估 | 待下载完成 |
| INT8 量化（仅在仍过大时） | 待定 |
| 用 CULane 样例图核对解码结果 | 待下载完成 |
| 接进清单 + 出 APK | 待上一步 |

## 8. 已知限制

- 即便接入成功，UFLD 在夜间、强反光、施工改道等场景会明显退化；这属于模型能力边界，不是接线问题。
- `resize: "stretch"` 会让 4:3 的手机画面在横向被压缩，与 CULane 的 2.78:1 训练比例不同；
  必要时可按 800×288 的比例先裁剪画面再送入，属于后续调优项。
- 该权重仅用于内部评测，未做商业使用审查归档。
