"""Fetch, trim and install the CULane lane model used for evaluation builds.

The ailia export of Ultra-Fast-Lane-Detection carries the auxiliary segmentation branch that is only
used during training, which is most of its 165 MB. Inference needs the classification head alone, so
the graph is extracted down to that single output before it is copied into the app assets.

Usage (from the repository root):

    python tools/prepare_lane_model.py                 # resume download, trim, verify, install
    python tools/prepare_lane_model.py --skip-fetch     # reuse .model-cache/lane/culane_18.onnx
    python tools/prepare_lane_model.py --quantise qdq   # also try INT8 and compare against float32

The result lands in app/src/main/assets/models/lane/model.onnx, which is git-ignored. The SHA-256
printed at the end goes into the local (also ignored) model bundle; see docs/LANE_MODEL_PLAN.md.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import requests

MODEL_URL = "https://storage.googleapis.com/ailia-models/ultra-fast-lane-detection/culane_18.onnx"
CACHE = Path(".model-cache/lane")
RAW = CACHE / "culane_18.onnx"
TRIMMED = CACHE / "culane_18_cls.onnx"
QUANTISED = CACHE / "culane_18_cls_int8.onnx"
ASSET = Path("app/src/main/assets/models/lane/model.onnx")
CLASS_OUTPUT = "200"
INPUT_NAME = "input.1"
EXPECTED_BYTES = 165_032_014


def fetch(attempts: int = 60) -> Path:
    """Resume the download until the advertised byte count is reached."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for attempt in range(attempts):
        have = RAW.stat().st_size if RAW.exists() else 0
        if have >= EXPECTED_BYTES:
            print(f"download complete: {have} bytes", flush=True)
            return RAW
        headers = {"User-Agent": "Mozilla/5.0"}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            with requests.get(MODEL_URL, headers=headers, timeout=(15, 120), stream=True) as response:
                if response.status_code not in (200, 206):
                    print(f"attempt {attempt}: HTTP {response.status_code}", flush=True)
                    time.sleep(15)
                    continue
                mode = "ab" if have and response.status_code == 206 else "wb"
                with open(RAW, mode) as handle:
                    for chunk in response.iter_content(262144):
                        handle.write(chunk)
        except Exception as error:  # noqa: BLE001 - the mirror drops long transfers routinely
            print(f"attempt {attempt}: {type(error).__name__}", flush=True)
            time.sleep(8)
        else:
            size = RAW.stat().st_size
            print(f"attempt {attempt}: {size}/{EXPECTED_BYTES} bytes", flush=True)
            if size >= EXPECTED_BYTES:
                return RAW
    raise SystemExit("download incomplete; rerun the script to resume")


def trim() -> Path:
    import onnx
    from onnx.utils import extract_model

    declared = [spec.name for spec in onnx.load(str(RAW)).graph.output]
    print(f"declared outputs: {declared}", flush=True)
    if CLASS_OUTPUT not in declared:
        raise SystemExit(f"expected output {CLASS_OUTPUT!r}, found {declared}")
    extract_model(str(RAW), str(TRIMMED), [INPUT_NAME], [CLASS_OUTPUT])
    print(
        f"trimmed: {TRIMMED.stat().st_size / 1e6:.1f} MB (raw {RAW.stat().st_size / 1e6:.1f} MB)",
        flush=True,
    )
    return TRIMMED


def quantise(source: Path) -> Path:
    """INT8 QDQ. Only used when the float32 graph is still too large for the APK."""
    import numpy as np
    from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize_static

    shape = (1, 3, 288, 800)

    class NoiseReader(CalibrationDataReader):
        def __init__(self, samples: int = 12):
            self.values = [np.random.rand(*shape).astype(np.float32) for _ in range(samples)]
            self.index = 0

        def get_next(self):
            if self.index >= len(self.values):
                return None
            item = {INPUT_NAME: self.values[self.index]}
            self.index += 1
            return item

        def rewind(self):
            self.index = 0

    quantize_static(str(source), str(QUANTISED), NoiseReader(), weight_type=QuantType.QInt8)
    print(f"quantised: {QUANTISED.stat().st_size / 1e6:.1f} MB", flush=True)
    return QUANTISED


def verify(path: Path) -> None:
    import numpy as np
    import onnxruntime as ort

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    spec = session.get_inputs()[0]
    shape = [dim if isinstance(dim, int) else 1 for dim in spec.shape]
    started = time.perf_counter()
    for _ in range(3):
        output = session.run(None, {spec.name: np.zeros(shape, dtype=np.float32)})[0]
    elapsed = (time.perf_counter() - started) / 3 * 1000
    print(f"verified: {shape} -> {output.shape}, {elapsed:.0f} ms per inference on desktop CPU", flush=True)
    assert tuple(output.shape) == (1, 201, 18, 4), f"unexpected output shape {output.shape}"


def install(path: Path) -> None:
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_bytes(path.read_bytes())
    print(
        "bundle entry:\n"
        + json.dumps(
            {
                "asset": str(ASSET),
                "sizeMb": round(ASSET.stat().st_size / 1e6, 1),
                "sha256": hashlib.sha256(ASSET.read_bytes()).hexdigest(),
                "input": INPUT_NAME,
                "output": CLASS_OUTPUT,
            },
            indent=2,
        ),
        flush=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-fetch", action="store_true")
    parser.add_argument("--quantise", choices=["none", "qdq"], default="none")
    args = parser.parse_args()

    fetch() if not args.skip_fetch else print(f"reusing {RAW}", flush=True)
    trimmed = trim()
    final = quantise(trimmed) if args.quantise == "qdq" else trimmed
    verify(final)
    install(final)


if __name__ == "__main__":
    main()
