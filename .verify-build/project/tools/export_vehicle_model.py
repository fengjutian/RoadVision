"""Export a YOLO vehicle detector to ONNX in the contract RoadVision expects.

RoadVision's vehicle decoder wants a single output shaped [1, N, 6]:
x1, y1, x2, y2, score, classId with normalised coordinates in [0, 1] and NMS already
applied (see VehicleOutputDecoder). Ultralytics can bake NMS into the graph, but it emits
pixel coordinates; this script therefore appends a tiny graph tail that divides the four
box columns by the input size, so the exported file satisfies the contract unchanged.

Usage:
    python tools/export_vehicle_model.py --model yolo11n.pt --imgsz 640 --out app/src/main/assets/models/vehicle/model.onnx

Note on licensing: Ultralytics models are AGPL-3.0. The generated weights are for local
evaluation builds only and must not be committed or shipped; see docs/DEPENDENCIES.md.
"""

from __future__ import annotations

import argparse
import hashlib
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper


def export(model_name: str, imgsz: int, opset: int) -> Path:
    from ultralytics import YOLO

    model = YOLO(model_name)
    path = model.export(
        format="onnx",
        imgsz=imgsz,
        opset=opset,
        nms=True,
        simplify=False,
        dynamic=False,
        batch=1,
    )
    return Path(path)


def normalise_boxes(path: Path, imgsz: int) -> Path:
    """Divide the xyxy columns by the input size so the output is in [0, 1]."""
    graph = onnx.load(str(path))
    output = graph.graph.output[0]
    name = output.name
    source = name

    graph.graph.node.append(
        helper.make_node(
            "Slice",
            [source, "slice_starts_box", "slice_ends_box", "slice_axes"],
            ["box_xyxy"],
            name="slice_box_xyxy",
        )
    )
    graph.graph.node.append(
        helper.make_node(
            "Slice",
            [source, "slice_starts_rest", "slice_ends_rest", "slice_axes"],
            ["box_rest"],
            name="slice_box_rest",
        )
    )
    graph.graph.node.append(
        helper.make_node("Cast", ["box_xyxy"], ["box_xyxy_f32"], to=TensorProto.FLOAT)
    )
    graph.graph.node.append(helper.make_node("Div", ["box_xyxy_f32", "input_scale"], ["box_norm"]))
    graph.graph.node.append(
        helper.make_node("Concat", ["box_norm", "box_rest"], ["normalised"], axis=2)
    )

    for name_, value in (
        ("slice_starts_box", np.array([0], dtype=np.int64)),
        ("slice_ends_box", np.array([4], dtype=np.int64)),
        ("slice_starts_rest", np.array([4], dtype=np.int64)),
        ("slice_ends_rest", np.array([6], dtype=np.int64)),
        ("slice_axes", np.array([2], dtype=np.int64)),
    ):
        graph.graph.initializer.append(numpy_helper.from_array(value, name_))
    graph.graph.initializer.append(
        numpy_helper.from_array(np.array([float(imgsz)], dtype=np.float32), "input_scale")
    )

    # Re-point the declared output at the normalised tensor and give it a stable name.
    for out in graph.graph.output:
        out.CopyFrom(
            helper.make_tensor_value_info("normalised", TensorProto.FLOAT, [1, "num_dets", 6])
        )
    graph = onnx.shape_inference.infer_shapes(graph)
    onnx.checker.check_model(graph)
    target = path.with_name(path.stem + "-norm.onnx")
    onnx.save(graph, str(target))
    return target


def check(model_path: Path, image_path: Path, imgsz: int) -> None:
    """Run the export on one real image and print what it detected."""
    from PIL import Image

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    scale = min(imgsz / width, imgsz / height)
    new_w, new_h = round(width * scale), round(height * scale)
    canvas = Image.new("RGB", (imgsz, imgsz), (114, 114, 114))
    canvas.paste(image.resize((new_w, new_h)), ((imgsz - new_w) // 2, (imgsz - new_h) // 2))
    tensor = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0

    started = time.perf_counter()
    for _ in range(10):
        outputs = session.run(None, {input_name: tensor})
    elapsed = (time.perf_counter() - started) / 10

    detections = outputs[0]
    print(f"  output shape {detections.shape}, dtype {detections.dtype}")
    print(f"  desktop CPU latency {elapsed * 1000:.1f} ms/inference (fp32, {ort.__version__})")
    counts: dict[int, int] = {}
    for row in detections[0]:
        x1, y1, x2, y2, score, class_id = row
        if score < 0.4:
            continue
        counts[int(class_id)] = counts.get(int(class_id), 0) + 1
        if int(class_id) in (2, 3, 5, 7):
            print(
                f"    vehicle class={int(class_id)} score={score:.3f} "
                f"box=({x1:.3f},{y1:.3f},{x2:.3f},{y2:.3f})"
            )
    print(f"  all classes above 0.4: {counts}")
    assert detections.shape[0] == 1 and detections.shape[2] == 6, "contract mismatch"
    assert np.all((detections[..., :4] >= 0.0) & (detections[..., :4] <= 1.001)), "boxes not normalised"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="yolo11n.pt")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--out", required=True)
    parser.add_argument("--image", default="", help="optional verification image")
    args = parser.parse_args()

    raw = export(args.model, args.imgsz, args.opset)
    print(f"exported {raw} ({raw.stat().st_size / 1e6:.1f} MB)")
    normalised = normalise_boxes(raw, args.imgsz)
    size_mb = normalised.stat().st_size / 1e6
    digest = hashlib.sha256(normalised.read_bytes()).hexdigest()
    print(f"normalised {normalised} ({size_mb:.1f} MB)")
    print(f"sha256 {digest}")

    target = Path(args.out)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(normalised.read_bytes())
    print(f"written {target}")

    if args.image:
        check(target, Path(args.image), args.imgsz)


if __name__ == "__main__":
    main()
