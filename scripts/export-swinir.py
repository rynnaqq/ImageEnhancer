#!/usr/bin/env python3
"""Export the official SwinIR real-world x4 checkpoint to fixed-shape ONNX.

This converter intentionally exports a fixed 96x96 RGB tile. Install its isolated
host dependencies with:

    python -m pip install --index-url https://download.pytorch.org/whl/cpu \
        torch==2.14.1+cpu torchvision==0.29.1+cpu
    python -m pip install timm==1.0.25 onnx==1.20.1 \
        onnxruntime==1.30.0 psutil==7.2.2 pillow==12.3.0 \
        numpy==2.5.2 protobuf==7.36.2

Then run ``python scripts/export-swinir.py --fetch`` from the repository root.

The script verifies the pinned upstream source and checkpoint before importing any
model code. It then checks the exported graph with ONNX and compares PyTorch with
ONNX Runtime on constant, ramp, and photo inputs.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import shutil
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import psutil
import torch
from PIL import Image, ImageOps


SOURCE_REPOSITORY = "https://github.com/JingyunLiang/SwinIR"
SOURCE_COMMIT = "6545850fbf8df298df73d81f3e8cba638787c8bd"
CHECKPOINT_NAME = "003_realSR_BSRGAN_DFO_s64w8_SwinIR-M_x4_GAN.pth"
CHECKPOINT_URL = (
    "https://github.com/JingyunLiang/SwinIR/releases/download/v0.0/"
    + CHECKPOINT_NAME
)
CHECKPOINT_BYTES = 67_129_861
CHECKPOINT_SHA256 = "b9afb61e65e04eb7f8aba5095d070bbe9af28df76acd0c9405aeb33b814bcfc6"
INPUT_SIZE = 96
UPSCALE = 4
OPSET = 17

PINNED_SOURCE_FILES = {
    "LICENSE": "d66ccc11682c7458b19b376e1335e8665cbe412ff71f554bfec25f1791cddfa0",
    "README.md": "bb18003aa632ed06fa453556979ad46ca6eb57fc636af674d1a8b03fe2959816",
    "main_test_swinir.py": "812671208d948a7a36241aeb2cd7371dfb19cac9cd976c7bc296fd786ebfca2e",
    "models/network_swinir.py": "9e143898679ebeebc5d2fc94ad1b89c38aa4a4d43da4e0fcba0f93e476994913",
}


def pinned_source_url(relative: str) -> str:
    return (
        "https://raw.githubusercontent.com/JingyunLiang/SwinIR/"
        f"{SOURCE_COMMIT}/{relative}"
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path: Path, expected_sha256: str, expected_bytes: int | None = None) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    if expected_bytes is not None and path.stat().st_size != expected_bytes:
        raise ValueError(
            f"{path} has {path.stat().st_size} bytes; expected {expected_bytes}"
        )
    actual = sha256_file(path)
    if actual.lower() != expected_sha256.lower():
        raise ValueError(f"{path} SHA-256 is {actual}; expected {expected_sha256}")


def download_pinned(
    url: str,
    destination: Path,
    expected_sha256: str,
    expected_bytes: int | None = None,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".download")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "ImageEnhancer-SwinIR-export/1"})
    print(f"Downloading {url}", flush=True)
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as stream:
            shutil.copyfileobj(response, stream, length=1024 * 1024)
        verify_file(partial, expected_sha256, expected_bytes)
        os.replace(partial, destination)
    finally:
        partial.unlink(missing_ok=True)


def prepare_pinned_inputs(source_dir: Path, checkpoint: Path, fetch: bool) -> None:
    for relative, expected_hash in PINNED_SOURCE_FILES.items():
        path = source_dir / relative
        try:
            verify_file(path, expected_hash)
        except (FileNotFoundError, ValueError):
            if not fetch:
                raise
            download_pinned(pinned_source_url(relative), path, expected_hash)
    try:
        verify_file(checkpoint, CHECKPOINT_SHA256, CHECKPOINT_BYTES)
    except (FileNotFoundError, ValueError):
        if not fetch:
            raise
        download_pinned(
            CHECKPOINT_URL, checkpoint, CHECKPOINT_SHA256, CHECKPOINT_BYTES
        )


@dataclass
class PeakRss:
    interval_seconds: float = 0.05

    def __post_init__(self) -> None:
        self._process = psutil.Process()
        self._stop = threading.Event()
        self.peak_bytes = self._process.memory_info().rss
        self._thread = threading.Thread(target=self._sample, daemon=True)

    def _sample(self) -> None:
        while not self._stop.wait(self.interval_seconds):
            self.peak_bytes = max(self.peak_bytes, self._process.memory_info().rss)

    def __enter__(self) -> "PeakRss":
        self._thread.start()
        return self

    def __exit__(self, *_: object) -> None:
        self.peak_bytes = max(self.peak_bytes, self._process.memory_info().rss)
        self._stop.set()
        self._thread.join()


def build_model(source_dir: Path, checkpoint: Path) -> torch.nn.Module:
    sys.path.insert(0, str(source_dir))
    try:
        from models.network_swinir import SwinIR
    finally:
        sys.path.pop(0)

    model = SwinIR(
        upscale=UPSCALE,
        in_chans=3,
        img_size=64,
        window_size=8,
        img_range=1.0,
        depths=[6, 6, 6, 6, 6, 6],
        embed_dim=180,
        num_heads=[6, 6, 6, 6, 6, 6],
        mlp_ratio=2,
        upsampler="nearest+conv",
        resi_connection="1conv",
    )
    checkpoint_blob = torch.load(checkpoint, map_location="cpu", weights_only=True)
    if sorted(checkpoint_blob) != ["params_ema"]:
        raise ValueError(f"unexpected checkpoint keys: {sorted(checkpoint_blob)}")
    state = checkpoint_blob["params_ema"]
    if len(state) != 552:
        raise ValueError(f"unexpected checkpoint tensor count: {len(state)}")
    model.load_state_dict(state, strict=True)
    del state, checkpoint_blob
    return model.eval()


def deterministic_inputs(photo_path: Path) -> dict[str, np.ndarray]:
    constant = np.full((1, 3, INPUT_SIZE, INPUT_SIZE), 0.5, dtype=np.float32)

    axis = np.linspace(0.0, 1.0, INPUT_SIZE, dtype=np.float32)
    x = np.broadcast_to(axis[None, :], (INPUT_SIZE, INPUT_SIZE))
    y = np.broadcast_to(axis[:, None], (INPUT_SIZE, INPUT_SIZE))
    ramp = np.stack((x, y, (x + y) * 0.5), axis=0)[None, ...].copy()

    with Image.open(photo_path) as image:
        rgb = ImageOps.fit(
            image.convert("RGB"),
            (INPUT_SIZE, INPUT_SIZE),
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.5),
        )
        photo = np.asarray(rgb, dtype=np.float32) / np.float32(255.0)
    photo = np.transpose(photo, (2, 0, 1))[None, ...].copy()
    return {"constant": constant, "ramp": ramp, "photo": photo}


def array_sha256(array: np.ndarray) -> str:
    return hashlib.sha256(array.tobytes(order="C")).hexdigest()


def add_metadata(model_proto: onnx.ModelProto) -> None:
    values = {
        "model_id": "swinir-real-sr-x4-rgb-96",
        "model_family": "SwinIR",
        "source_repository": SOURCE_REPOSITORY,
        "source_commit": SOURCE_COMMIT,
        "checkpoint_url": CHECKPOINT_URL,
        "checkpoint_sha256": CHECKPOINT_SHA256,
        "license": "Apache-2.0",
        "input_contract": "float32 NCHW RGB [1,3,96,96], values 0..1",
        "output_contract": (
            "float32 NCHW RGB [1,3,384,384]; source model output, clamp to 0..1 "
            "before image encoding"
        ),
    }
    del model_proto.metadata_props[:]
    for key, value in values.items():
        entry = model_proto.metadata_props.add()
        entry.key = key
        entry.value = value


def export_onnx(model: torch.nn.Module, output_path: Path) -> dict[str, Any]:
    partial = output_path.with_suffix(output_path.suffix + ".partial")
    if partial.exists():
        partial.unlink()
    dummy = torch.full((1, 3, INPUT_SIZE, INPUT_SIZE), 0.5, dtype=torch.float32)
    started = time.perf_counter()
    with PeakRss() as memory:
        torch.onnx.export(
            model,
            (dummy,),
            partial,
            input_names=["input"],
            output_names=["output"],
            opset_version=OPSET,
            do_constant_folding=True,
            dynamo=False,
            external_data=False,
        )
    elapsed = time.perf_counter() - started

    model_proto = onnx.load(partial, load_external_data=True)
    onnx.checker.check_model(model_proto, full_check=True)
    add_metadata(model_proto)
    onnx.save_model(model_proto, partial, save_as_external_data=False)
    model_proto = onnx.load(partial, load_external_data=True)
    onnx.checker.check_model(model_proto, full_check=True)
    if any(initializer.external_data for initializer in model_proto.graph.initializer):
        raise ValueError("export unexpectedly contains external tensor data")
    os.replace(partial, output_path)
    return {
        "seconds": elapsed,
        "peak_rss_bytes": memory.peak_bytes,
        "ir_version": model_proto.ir_version,
        "opset": next(item.version for item in model_proto.opset_import if not item.domain),
        "nodes": len(model_proto.graph.node),
        "initializers": len(model_proto.graph.initializer),
    }


def validate_parity(
    model: torch.nn.Module,
    output_path: Path,
    inputs: dict[str, np.ndarray],
    threads: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    references: dict[str, np.ndarray] = {}
    pytorch_timings: dict[str, float] = {}
    with torch.inference_mode():
        for name, sample in inputs.items():
            started = time.perf_counter()
            result = model(torch.from_numpy(sample)).cpu().numpy()
            pytorch_timings[name] = time.perf_counter() - started
            references[name] = result

    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    session_started = time.perf_counter()
    with PeakRss() as session_memory:
        session = ort.InferenceSession(
            str(output_path), sess_options=options, providers=["CPUExecutionProvider"]
        )
    session_seconds = time.perf_counter() - session_started
    if session.get_inputs()[0].name != "input":
        raise ValueError("unexpected ONNX input name")
    if session.get_outputs()[0].name != "output":
        raise ValueError("unexpected ONNX output name")
    if session.get_inputs()[0].shape != [1, 3, INPUT_SIZE, INPUT_SIZE]:
        raise ValueError(f"unexpected ONNX input shape: {session.get_inputs()[0].shape}")
    expected_output_shape = [1, 3, INPUT_SIZE * UPSCALE, INPUT_SIZE * UPSCALE]
    if session.get_outputs()[0].shape != expected_output_shape:
        raise ValueError(f"unexpected ONNX output shape: {session.get_outputs()[0].shape}")

    results: list[dict[str, Any]] = []
    inference_peak = psutil.Process().memory_info().rss
    for name, sample in inputs.items():
        started = time.perf_counter()
        with PeakRss() as run_memory:
            actual = session.run(["output"], {"input": sample})[0]
        ort_seconds = time.perf_counter() - started
        inference_peak = max(inference_peak, run_memory.peak_bytes)
        reference = references[name]
        if tuple(actual.shape) != tuple(expected_output_shape):
            raise ValueError(f"{name} produced shape {actual.shape}")
        if not np.isfinite(reference).all() or not np.isfinite(actual).all():
            raise ValueError(f"{name} produced non-finite output")
        difference = np.abs(reference - actual)
        denominator = np.maximum(np.abs(reference), np.float32(1e-3))
        relative = difference / denominator
        np.testing.assert_allclose(actual, reference, rtol=1e-3, atol=2e-4)
        results.append(
            {
                "name": name,
                "input_sha256": array_sha256(sample),
                "pytorch_seconds": pytorch_timings[name],
                "onnxruntime_seconds": ort_seconds,
                "max_abs_error": float(difference.max()),
                "mean_abs_error": float(difference.mean()),
                "p99_relative_error": float(np.percentile(relative, 99)),
                "reference_min": float(reference.min()),
                "reference_max": float(reference.max()),
                "onnx_min": float(actual.min()),
                "onnx_max": float(actual.max()),
            }
        )
    runtime = {
        "providers": session.get_providers(),
        "session_creation_seconds": session_seconds,
        "session_peak_rss_bytes": session_memory.peak_bytes,
        "inference_peak_rss_bytes": inference_peak,
    }
    return results, runtime


def package_version(name: str) -> str:
    return importlib.metadata.version(name)


def parse_args(repo_root: Path) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=repo_root / ".tools" / "swinir-export" / "source-pinned",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=repo_root / ".tools" / "swinir-export" / "downloads" / CHECKPOINT_NAME,
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=repo_root / ".tools" / "model-candidates" / "face-swinir.onnx",
    )
    parser.add_argument(
        "--photo",
        type=Path,
        default=repo_root / "docs" / "evidence" / "qa-fixture.png",
    )
    parser.add_argument("--threads", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument(
        "--fetch",
        action="store_true",
        help="fetch missing or invalid source/checkpoint files from pinned official URLs",
    )
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def main() -> int:
    repo_root = Path(__file__).resolve().parents[1]
    args = parse_args(repo_root)
    source_dir = args.source_dir.resolve()
    checkpoint = args.checkpoint.resolve()
    output = args.output.resolve()
    photo = args.photo.resolve()
    if not 1 <= args.threads <= 8:
        raise ValueError("--threads must be between 1 and 8")
    if output.exists() and not args.force:
        raise FileExistsError(f"{output} already exists; pass --force to replace it")
    if not photo.is_file():
        raise FileNotFoundError(photo)

    prepare_pinned_inputs(source_dir, checkpoint, args.fetch)

    torch.manual_seed(0)
    np.random.seed(0)
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    output.parent.mkdir(parents=True, exist_ok=True)

    print(f"Building SwinIR from official source commit {SOURCE_COMMIT}", flush=True)
    model = build_model(source_dir, checkpoint)
    print(f"Exporting fixed RGB tensor [1,3,{INPUT_SIZE},{INPUT_SIZE}]", flush=True)
    export_metrics = export_onnx(model, output)
    print("Checking PyTorch / ONNX Runtime parity", flush=True)
    inputs = deterministic_inputs(photo)
    parity, runtime = validate_parity(model, output, inputs, args.threads)

    license_output = output.with_name(output.stem + ".LICENSE")
    readme_output = output.with_name(output.stem + ".upstream-README.md")
    shutil.copyfile(source_dir / "LICENSE", license_output)
    shutil.copyfile(source_dir / "README.md", readme_output)

    report = {
        "model": {
            "id": "swinir-real-sr-x4-rgb-96",
            "architecture": "SwinIR-M real-world SR x4 GAN",
            "purpose": "neural detail enhancement of detected face-region tiles",
            "input": {
                "name": "input",
                "dtype": "float32",
                "layout": "NCHW",
                "color": "RGB",
                "range": [0.0, 1.0],
                "shape": [1, 3, INPUT_SIZE, INPUT_SIZE],
            },
            "output": {
                "name": "output",
                "dtype": "float32",
                "layout": "NCHW",
                "color": "RGB",
                "shape": [1, 3, INPUT_SIZE * UPSCALE, INPUT_SIZE * UPSCALE],
                "postprocess": "clamp source output to 0..1 before image encoding",
            },
        },
        "provenance": {
            "source_repository": SOURCE_REPOSITORY,
            "source_commit": SOURCE_COMMIT,
            "checkpoint_url": CHECKPOINT_URL,
            "checkpoint_bytes": CHECKPOINT_BYTES,
            "checkpoint_sha256": CHECKPOINT_SHA256,
            "license": "Apache-2.0",
            "source_files": PINNED_SOURCE_FILES,
            "photo_fixture": {
                "path": str(photo.relative_to(repo_root)),
                "bytes": photo.stat().st_size,
                "sha256": sha256_file(photo),
            },
        },
        "artifact": {
            "path": str(output),
            "bytes": output.stat().st_size,
            "sha256": sha256_file(output),
            "license_path": str(license_output),
            "upstream_readme_path": str(readme_output),
        },
        "export": export_metrics,
        "runtime": runtime,
        "parity": parity,
        "environment": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "logical_cpu_count": os.cpu_count(),
            "threads": args.threads,
            "physical_memory_bytes": psutil.virtual_memory().total,
            "packages": {
                name: package_version(name)
                for name in (
                    "torch",
                    "torchvision",
                    "timm",
                    "numpy",
                    "onnx",
                    "onnxruntime",
                    "Pillow",
                    "psutil",
                    "protobuf",
                )
            },
        },
    }
    report_path = output.with_suffix(".provenance.json")
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["artifact"], indent=2), flush=True)
    print(f"Validation report: {report_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
