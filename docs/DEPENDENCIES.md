# RoadVision 许可证与第三方依赖审查

## 1. 目的与准入规则

RoadVision 的目标是闭源商业用途。任何模型、权重、数据集、OCR
字库、预处理/后处理代码或第三方依赖，在进入候选发布构建前必须完成许可证审查。

本文件是审查流程，不构成法律意见。对许可条款有疑义时，应由具备相应资质的法务人员核验。

## 2. 必须分开审查的对象

  对象                 必查内容
  -------------------- ----------------------------------------------
  代码仓库             SPDX/许可证全文、NOTICE、版权声明、修改义务
  预训练权重           权重自身许可、模型卡、商用与再分发条件
  训练/验证数据        数据来源、商业用途、再分发、隐私和标注许可
  OCR 字库/语言包      字体与数据许可、嵌入与分发条件
  ONNX 导出文件        上游模型许可是否继续适用、导出工具和代码依赖
  Android SDK/运行时   版本、许可、专有组件和再分发条件
  传递依赖             完整依赖树、许可证兼容性、安全公告
  测试数据/视频        拍摄授权、车牌等个人信息的处理依据和访问控制

## 3. 高风险提示

-   **事实性提醒：** ONNX 是模型交换格式，不等于模型许可证。
-   **事实性提醒：** 将模型导出为 ONNX
    不会自动消除原始代码、权重或数据许可义务。
-   **风险提示：** Ultralytics 相关项目/模型可能涉及 AGPL-3.0
    或商业授权安排。若选择该路线，必须在闭源分发前核实当前具体版本、权重条款及适用商业授权；未解决前不得作为发布候选。
-   **候选方向：** 可研究许可证条款更明确的轻量检测/车道/OCR
    实现，但仍须核验具体仓库版本、模型文件和数据集。不得只看代码仓库首页的许可证就推断权重可商用。

## 4. 审查清单

每项资产必须填写：

-   名称与用途
-   官方来源 URL
-   版本、tag 或 commit
-   代码许可证与许可证文件位置
-   权重许可证/模型卡
-   训练数据来源与许可
-   是否允许商业使用
-   是否允许修改与再分发
-   是否有署名、NOTICE、源码提供或网络交互等义务
-   SHA-256
-   审查人、日期、结论与证据归档位置

结论状态统一为： - `APPROVED`：可在限定条件下使用，证据齐全。 -
`CONDITIONAL`：需满足列明条件后才可使用。 -
`BLOCKED`：不得进入候选发布构建。 - `UNKNOWN`：信息不足，按阻断处理。

## 5. 模型清单模板

  ---------------------------------------------------------------------------------------
  模型角色   候选名称/版本   来源     权重     代码许可   权重许可   数据许可   结论
                                      hash                                      
  ---------- --------------- -------- -------- ---------- ---------- ---------- ---------
  车辆检测   待选            待填     待填     待核验     待核验     待核验     UNKNOWN

  车道识别   待选            待填     待填     待核验     待核验     待核验     UNKNOWN

  车牌检测   待选            待填     待填     待核验     待核验     待核验     UNKNOWN

  OCR        待选            待填     待填     待核验     待核验     待核验     UNKNOWN
  ---------------------------------------------------------------------------------------

## 5.1 当前本地评测模型（未通过审查，仅限内部测试）

| 字段 | 值 |
|---|---|
| 名称与用途 | Ultralytics YOLO11n（COCO 预训练），车辆检测 |
| 官方来源 | https://github.com/ultralytics/ultralytics ，权重取自 https://huggingface.co/Ultralytics/YOLO11 |
| 版本 | ultralytics 8.4.190；权重文件 `yolo11n.pt` |
| 代码许可证 | AGPL-3.0 |
| 权重许可证 | 沿用 Ultralytics 许可条款（商用需商业授权）；**未核验，按阻断处理** |
| 训练数据 | COCO 2017（80 类，含 car/bus/truck/motorcycle） |
| 导出方式 | `tools/export_vehicle_model.py --imgsz <尺寸>`，opset 17，内置 NMS，输出 `[1, 300, 6]` 并归一化坐标 |
| ONNX SHA-256 | `c2cc17325a6ab9870f7b11834a5dc52088306bf841f26c5bbbffdf3e0f97c657`（416×416 输入；此前 640×640 版本 `8bddec96…` 已弃用） |
| 结论 | `BLOCKED`（闭源分发前必须取得商业授权或替换模型） |

车道目前不依赖任何权重：`model-bundle.json` 中的 `"lane": { "mode": "classical" }` 选用
`ClassicalLaneDetector`（边缘 + 霍夫投票），只用于让调度、坐标映射与叠加层在真机跑通，
**不能**作为车道识别准确率依据；换成 ONNX 车道模型时删掉 `mode`、填 `assetPath` 与契约即可。

使用约束（已落实）：

-   `.onnx` 二进制、本地评测清单 `app/src/main/assets/models/model-bundle.json` 与
    `.model-cache/` 均在 `.gitignore` 中，**不入库**；干净 clone 只保留示例清单，需本地放置
    清单和权重；
-   本地评测清单的 contract id 标注 `-agpl-evaluation-only`，导出报告的
    `models[].id/version` 会带上该标记；
-   该构建仅用于真机链路与性能评测，不得作为候选发布 APK。

## 5.2 车道模型候选（评测中，未定稿）

| 字段 | 值 |
|---|---|
| 名称 | Ultra-Fast-Lane-Detection, CULane, ResNet18（`culane_18.onnx`） |
| 上游代码 | https://github.com/cfzd/Ultra-Fast-Lane-Detection ，**MIT** |
| 训练数据 | CULane（中国城区道路车道线，4 条车道） |
| ONNX 来源 | ailia-models 导出，`https://storage.googleapis.com/ailia-models/ultra-fast-lane-detection/culane_18.onnx`（可连通的 GCS 直链） |
| 输入 | `input.1`，`[1, 3, 288, 800]`，NCHW，RGB，`scale = 1/255`，**stretch 缩放**（非 letterbox） |
| 输出 | `200`，`[1, 201, 18, 4]`：200 个位置桶 + 1 个“无车道”类，18 个行锚点，4 条车道 |
| 行锚点 | `[121,131,141,150,160,170,180,189,199,209,219,228,238,248,258,267,277,287] / 288` |
| 体积 | **165 MB**（含 `use_aux` 分割头），不能直接塞进 APK |
| 结论 | `CONDITIONAL`：需先裁剪 aux 分支并量化（INT8）后再评估体积/精度 |

启用方式（模型文件就位后，本地清单 `model-bundle.json`）：

```json
"lane": {
  "assetPath": "models/lane/model.onnx",
  "decode": { "type": "ufld", "threshold": 0.3, "rowAnchors": [0.4201, 0.4549, "…"] },
  "contract": {
    "id": "ufld-culane-res18-288x800", "version": "1.0-evaluation",
    "sha256": "<文件哈希>",
    "input": { "name": "input.1", "shape": [1, 3, 288, 800] },
    "outputs": [{ "name": "200", "shape": [1, 201, 18, 4] }],
    "layout": "NCHW", "colorOrder": "RGB", "scale": 0.0039215686,
    "resize": "stretch"
  }
}
```

解码器与 stretch 预处理已实现并通过单测（`UfldLaneDecoderTest`）；`mode: "classical"` 的
无权重兜底保留，作为模型未就位时的默认。

## 6. 第三方依赖清单模板

  ---------------------------------------------------------------------------------------
  组件               版本               许可证      用途         来源         审查状态
  ------------------ ------------------ ----------- ------------ ------------ -----------
  Kotlin / Android   工程初始化时锁定   待核验      工程与语言   官方发行页   UNKNOWN
  Gradle Plugin                                                               

  CameraX            工程初始化时锁定   待核验      相机采集     Android 官方 UNKNOWN

  ONNX Runtime       工程初始化时锁定   待核验      ONNX 推理    官方发行页   UNKNOWN
  Android                                                                     

  Room               工程初始化时锁定   待核验      本地数据库   Android 官方 UNKNOWN

  OpenCV（如采用）   待选               待核验      图像处理     官方仓库     UNKNOWN

  跟踪器实现（如     待选               待核验      车辆跟踪     官方仓库     UNKNOWN
  ByteTrack）                                                                 
  ---------------------------------------------------------------------------------------

上表是待审查模板，不代表已经批准使用。版本锁定后应生成依赖树/SBOM，并记录传递依赖。

## 7. 参考来源（需按实际选用版本再次核验）

-   Ultralytics 仓库：https://github.com/ultralytics/ultralytics
-   Ultralytics 许可说明：https://www.ultralytics.com/license
-   ONNX Runtime
    Android/mobile：https://onnxruntime.ai/docs/tutorials/mobile/
-   Android
    CameraX：https://developer.android.com/media/camera/camerax/analyze
-   PaddleDetection：https://github.com/PaddlePaddle/PaddleDetection
-   PaddleOCR：https://github.com/PaddlePaddle/PaddleOCR
-   UFLD V2：https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2
-   ByteTrack：https://github.com/ifzhang/ByteTrack
-   OpenCV：https://github.com/opencv/opencv

## 8. 发布门禁

-   `UNKNOWN` 或 `BLOCKED` 的模型/依赖不得进入候选发布 APK。
-   `CONDITIONAL` 必须把条件落实为可验证的检查项。
-   发布包必须关联 Git commit、构建号、模型 hash、依赖锁定文件、SBOM
    和审查记录。
