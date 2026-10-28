"""Fetch, trim and install the CULane lane model used for evaluation builds.

The ailia export of Ultra-Fast-Lane-Detection carries the auxiliary segmentation branch that is only
used during training, which is most of its size. Inference needs the classification head alone, so
the graph is extracted down to that single output before it is copied into the app assets.

The mirror throttles a single connection to roughly 25 kB/s, so the download is split into parallel
ranged requests that are merged afterwards. Each part resumes on its own, which keeps a two hour
transfer closer to ten minutes.

Usage (from the repository root):

    python tools/prepare_lane_model.py                 # download, trim, verify, install
    python tools/prepare_lane_model.py --skip-fetch     # reuse .model-cache/lane/culane_18.onnx
    python tools/prepare_lane_model.py --quantise qdq   # also try INT8 and compare against float32

The result lands in app/src/main/assets/models/lane/model.onnx, which is git-ignored. The SHA-256
printed at the end goes into the local (also ignored) model bundle; see docs/LANE_MODEL_PLAN.md.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import shutil
import time
from pathlib import Path

import requests

MODEL_URL = "https://storage.googleapis.com/ailia-models/ultra-fast-lane-detection/culane_18.onnx"
CACHE = Path(".model-cache/lane")
RAW = CACHE / "culane_18.onnx"
PARTS = CACHE / "parts"
TRIMMED = CACHE / "culane_18_cls.onnx"
QUANTISED = CACHE / "culane_18_cls_int8.onnx"
ASSET = Path("app/src/main/assets/models/lane/model.onnx")
CLASS_OUTPUT = "200"
INPUT_NAME = "input.1"
USER_AGENT = {"User-Agent": "Mozilla/5.0"}
WORKERS = 8


def remote_size() -> int:
    response = requests.get(MODEL_URL, headers={**USER_AGENT, "Range": "bytes=0-0"}, timeout=(15, 60))
    content_range = response.headers.get("Content-Range", "")
    if "/" not in content_range:
        raise SystemExit(f"cannot determine remote size: {response.status_code} {content_range!r}")
    return int(content_range.rsplit("/", 1)[1])


def download_part(index: int, start: int, end: int, attempts: int = 80) -> Path:
    path = PARTS / f"part{index}"
    expected = end - start + 1
    for _ in range(attempts):
        have = path.stat().st_size if path.exists() else 0
        if have >= expected:
            return path
        headers = {**USER_AGENT, "Range": f"bytes={start + have}-{end}"}
        try:
            with requests.get(MODEL_URL, headers=headers, timeout=(15, 90), stream=True) as response:
                if response.status_code not in (200, 206):
                    time.sleep(5)
                    continue
                with open(path, "ab") as handle:
                    for chunk in response.iter_content(131072):
                        handle.write(chunk)
        except Exception:  # noqa: BLE001 - the mirror drops connections routinely
            time.sleep(3)
    raise SystemExit(f"part {index} could not be completed")


def fetch() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    total = remote_size()
    if RAW.exists() and RAW.stat().st_size == total:
        print(f"already downloaded: {total} bytes", flush=True)
        return RAW

    PARTS.mkdir(parents=True, exist_ok=True)
    size = total // WORKERS
    ranges = []
    for index in range(WORKERS):
        start = index * size
        end = total - 1 if index == WORKERS - 1 else (start + size - 1)
        ranges.append((index, start, end))

    print(f"downloading {total / 1e6:.1f} MB in {WORKERS} parts", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(download_part, *item) for item in ranges]
        while not all(f.done() for f in futures):
            time.sleep(20)
            done = sum((PARTS / f"part{i}").stat().st_size for i in range(WORKERS) if (PARTS / f"part{i}").exists())
            print(f"  {done / 1e6:.1f}/{total / 1e6:.1f} MB", flush=True)
        for future in futures:
            future.result()

    with open(RAW, "wb") as merged:
        for index, _, _ in ranges:
            with open(PARTS / f"part{index}", "rb") as part:
                shutil.copyfileobj(part, merged)
    merged_size = RAW.stat().st_size
    print(f"merged: {merged_size} bytes (expected {total})", flush=True)
    if merged_size != total:
        raise SystemExit("merged file is the wrong size; delete .model-cache/lane/parts and retry")

    import onnx

    onnx.checker.check_model(str(RAW))
    print("onnx check passed", flush=True)
    shutil.rmtree(PARTS, ignore_errors=True)
    return RAW


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
