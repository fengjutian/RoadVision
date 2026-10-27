"""Compare RoadVision vehicle ONNX variants on speed and a small fixed-image set.

The model contract is [1, N, 6] with normalised xyxy, score and class id. This script keeps the
same letterbox mapping as the Android client and reports IoU against a manually labelled bus box.
It is an internal regression check, not a replacement for a labelled road dataset.

Usage:
    python tools/evaluate_vehicle_models.py \
        --model 640=app/src/main/assets/models/vehicle/model.onnx \
        --model 480=.model-cache/yolo11n-480.onnx \
        --model 416=.model-cache/yolo11n-416.onnx \
        --model 320=.model-cache/yolo11n-320.onnx
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageEnhance

VEHICLE_CLASSES = {2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}
# Manually inspected bus.jpg: the bus occupies this normalised box in the source image.
BUS_GT = (0.010, 0.210, 0.980, 0.680)
CANVAS = (810, 1080)


def letterbox(image: Image.Image, size: int) -> tuple[np.ndarray, float, float, float]:
    width, height = image.size
    scale = min(size / width, size / height)
    new_w, new_h = round(width * scale), round(height * scale)
    pad_x = (size - new_w) / 2.0
    pad_y = (size - new_h) / 2.0
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(image.resize((new_w, new_h), Image.BILINEAR), (round(pad_x), round(pad_y)))
    tensor = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    return tensor, scale, pad_x, pad_y


def map_box(box: np.ndarray, size: int, source: tuple[int, int], pad_x: float, pad_y: float) -> np.ndarray:
    width, height = source
    scale = min(size / width, size / height)
    x1, y1, x2, y2 = (float(v) for v in box[:4])
    return np.array(
        [
            (x1 * size - pad_x) / (width * scale),
            (y1 * size - pad_y) / (height * scale),
            (x2 * size - pad_x) / (width * scale),
            (y2 * size - pad_y) / (height * scale),
        ],
        dtype=np.float32,
    ).clip(0.0, 1.0)


def iou(a: np.ndarray, b: tuple[float, float, float, float]) -> float:
    left, top = max(float(a[0]), b[0]), max(float(a[1]), b[1])
    right, bottom = min(float(a[2]), b[2]), min(float(a[3]), b[3])
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    area_a = max(0.0, float(a[2] - a[0])) * max(0.0, float(a[3] - a[1]))
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def scaled_bus_images() -> list[tuple[str, Image.Image, tuple[float, float, float, float]]]:
    base = Image.open(".model-cache/bus.jpg").convert("RGB")
    cases = [("bus", base, BUS_GT)]
    for scale in (0.8, 0.6, 0.4, 0.25):
        width, height = CANVAS
        resized = base.resize((round(width * scale), round(height * scale)), Image.BILINEAR)
        canvas = Image.new("RGB", (width, height), (90, 90, 90))
        offset = ((width - resized.width) // 2, (height - resized.height) // 2)
        canvas.paste(resized, offset)
        gt = (
            (BUS_GT[0] * resized.width + offset[0]) / width,
            (BUS_GT[1] * resized.height + offset[1]) / height,
            (BUS_GT[2] * resized.width + offset[0]) / width,
            (BUS_GT[3] * resized.height + offset[1]) / height,
        )
        cases.append((f"bus@{scale:.2f}", canvas, gt))
    dark = ImageEnhance.Brightness(base).enhance(0.55)
    cases.append(("bus-dark", dark, BUS_GT))
    return cases


def evaluate(path: Path, size: int) -> dict:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    cases = scaled_bus_images()
    zidane = Image.open(".model-cache/zidane.jpg").convert("RGB")
    cases.append(("zidane/no-vehicle", zidane, None))

    rows = []
    latencies = []
    for name, image, gt in cases:
        tensor, _, pad_x, pad_y = letterbox(image, size)
        started = time.perf_counter()
        output = session.run(None, {input_name: tensor})[0]
        latencies.append((time.perf_counter() - started) * 1000)
        detections = []
        for row in output[0]:
            if float(row[4]) < 0.35 or int(row[5]) not in VEHICLE_CLASSES:
                continue
            detections.append((VEHICLE_CLASSES[int(row[5])], float(row[4]), map_box(row, size, image.size, pad_x, pad_y)))
        best = max(detections, key=lambda item: item[1], default=None)
        rows.append(
            {
                "case": name,
                "vehicles": len(detections),
                "best_label": best[0] if best else None,
                "best_score": round(best[1], 4) if best else None,
                "iou": round(iou(best[2], gt), 4) if best and gt else None,
                "gt": gt is not None,
            }
        )

    # Warm up and measure median latency over repeated runs on a normal bus frame.
    tensor, _, _, _ = letterbox(cases[0][1], size)
    for _ in range(5):
        session.run(None, {input_name: tensor})
    samples = []
    for _ in range(20):
        started = time.perf_counter()
        session.run(None, {input_name: tensor})
        samples.append((time.perf_counter() - started) * 1000)
    return {
        "input": size,
        "size_mb": round(path.stat().st_size / 1e6, 1),
        "latency_ms": {"first": round(latencies[0], 2), "p50": round(statistics.median(samples), 2)},
        "rows": rows,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", action="append", required=True, help="size=path")
    parser.add_argument("--json", default="")
    args = parser.parse_args()
    results = []
    for item in args.model:
        size, raw = item.split("=", 1)
        result = evaluate(Path(raw), int(size))
        results.append(result)
        print(json.dumps(result, ensure_ascii=False))

    print("\n| input | size MB | p50 ms | bus score | bus IoU | scaled TP | zidane FP |")
    print("|---|---|---|---|---|---|---|")
    for result in results:
        rows = result["rows"]
        bus = next(row for row in rows if row["case"] == "bus")
        scaled = [row for row in rows if row["case"].startswith("bus@") or row["case"] == "bus-dark"]
        tp = sum(1 for row in scaled if row["gt"] and (row["iou"] or 0) >= 0.5)
        zidane = next(row for row in rows if row["case"] == "zidane/no-vehicle")
        print(
            f"| {result['input']} | {result['size_mb']} | {result['latency_ms']['p50']} | "
            f"{bus['best_score']} | {bus['iou']} | {tp}/{len(scaled)} | {zidane['vehicles']} |"
        )
    if args.json:
        Path(args.json).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nwritten {args.json}")


if __name__ == "__main__":
    main()
