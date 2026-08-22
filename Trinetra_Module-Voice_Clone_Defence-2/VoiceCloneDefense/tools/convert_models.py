"""PyTorch -> ONNX -> int8 -> on-device, with a parity check that decides what ships.

Run:  tools/.venv/Scripts/python.exe tools/convert_models.py

The point of this script is not to produce model files. It is to produce model files whose
behaviour has been compared against the original PyTorch checkpoints on real speech, and to record
what that comparison actually measured in models/manifest.json. Every number in that manifest comes
from a run of this script. If a stage fails, the manifest records the failure rather than a
plausible-looking figure, and the app reports the models as missing rather than scoring anything.

WHY ONNX RUNTIME AND NOT TFLITE
-------------------------------
The original plan was PyTorch -> ONNX -> TensorFlow (onnx2tf) -> TFLite int8. The PyTorch -> ONNX
half is numerically exact for both models (see the parity figures below). The TensorFlow half is
not usable with either onnx2tf release available here:

  onnx2tf 2.6.8   Writes .tflite files that then refuse to load.
                    speaker: RESHAPE node 11   num_input_elements != num_output_elements (26000 != 1)
                    spoof:   RESHAPE node 1359 num_input_elements != num_output_elements (1280 != 20)
                  Its dynamic-range output loads no better:
                    BATCH_MATMUL lhs float32 / rhs int8 type mismatch, node 0 failed to prepare.
  onnx2tf 1.26.9  Fails earlier, during conversion, on /net/MaxPool layout inference.

Every one of those was found by loading and running the converted file, not by checking that a file
appeared. A .tflite that exists but will not allocate its tensors is not a converted model.

So both models ship as ONNX and run through onnxruntime-android. Everything else about the design
is unchanged: inference is fully on-device, nothing is uploaded, and the app has no INTERNET
permission at all.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import traceback
from pathlib import Path

import librosa
import numpy as np
import onnxruntime as ort
import torch

sys.path.insert(0, str(Path(__file__).parent))
import vcd_models as M  # noqa: E402

TOOLS = Path(__file__).parent
WORK = TOOLS / "_work"
ASSETS = TOOLS.parent / "app" / "src" / "main" / "assets" / "models"

RESEMBLYZER_CKPT = WORK / "resemblyzer_pretrained.pt"
AASIST_REPO = WORK / "aasist"
AASIST_CONF = AASIST_REPO / "config" / "AASIST.conf"
AASIST_WEIGHTS = AASIST_REPO / "models" / "weights" / "AASIST.pth"

# Three different LibriSpeech speakers, used for both quantisation calibration and parity.
CALIBRATION_KEYS = ["libri1", "libri2", "libri3"]

# Parity bars a quantised model has to clear to be preferred over float32. These are conversion
# fidelity thresholds, not accuracy claims — they say "quantisation did not change the answer",
# nothing about whether the answer is right.
SPEAKER_MIN_COSINE = 0.995
SPOOF_MAX_DELTA = 0.05


def log(msg: str) -> None:
    print(f"[convert] {msg}", flush=True)


def load_calibration_clips() -> list[np.ndarray]:
    clips = []
    for key in CALIBRATION_KEYS:
        wav, _ = librosa.load(librosa.example(key), sr=M.SAMPLE_RATE, mono=True)
        clips.append(wav.astype(np.float32))
    return clips


def export_onnx(model: torch.nn.Module, dummy: torch.Tensor, path: Path) -> Path:
    model.eval()
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (dummy,),
        str(path),
        input_names=["waveform"],
        output_names=["output"],
        opset_version=17,
        # The TorchScript exporter is the one the downstream tooling is tested against; the dynamo
        # exporter emits newer constructs that not every consumer recognises.
        dynamo=False,
        do_constant_folding=True,
    )
    log(f"ONNX written: {path.name} ({path.stat().st_size / 1e6:.1f} MB)")
    return path


def quantize_int8(src: Path, dst: Path) -> Path | None:
    """Dynamic int8 weight quantisation. Returns None if it will not produce a loadable model."""
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(
            model_input=str(src),
            model_output=str(dst),
            weight_type=QuantType.QInt8,
        )
        # Loading is the real test. A file that exists proves nothing.
        ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
        log(f"int8 written: {dst.name} ({dst.stat().st_size / 1e6:.2f} MB)")
        return dst
    except Exception as exc:  # noqa: BLE001
        log(f"int8 quantisation unusable ({type(exc).__name__}: {str(exc)[:160]})")
        return None


def run_onnx(path: Path, batch: np.ndarray) -> np.ndarray:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    name = session.get_inputs()[0].name
    return np.vstack([session.run(None, {name: row.reshape(1, -1)})[0] for row in batch])


def io_shapes(path: Path) -> tuple[list, list]:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    return list(session.get_inputs()[0].shape), list(session.get_outputs()[0].shape)


# ------------------------------------------------------------------------------- speaker encoder

def speaker_partials(clips: list[np.ndarray]) -> np.ndarray:
    rows = []
    for clip in clips:
        rows.extend(M.split_partials(M.normalize_volume(clip)))
    return np.stack(rows).astype(np.float32)


def convert_speaker(clips: list[np.ndarray], report: dict) -> None:
    log("=== speaker encoder ===")
    partials = speaker_partials(clips)
    log(f"parity partials: {partials.shape}")

    float_path = WORK / "speaker_encoder_fp32.onnx"
    int8_path = WORK / "speaker_encoder_int8.onnx"
    shipped = ASSETS / "speaker_encoder.onnx"

    try:
        model = M.SpeakerEncoder().eval().load_resemblyzer(RESEMBLYZER_CKPT)
        export_onnx(model, torch.zeros(1, M.PARTIAL_SAMPLES), float_path)

        with torch.no_grad():
            reference = model(torch.from_numpy(partials)).numpy()

        def cosine_against_reference(path: Path) -> np.ndarray:
            got = run_onnx(path, partials)
            return np.sum(reference * got, axis=1) / (
                np.linalg.norm(reference, axis=1) * np.linalg.norm(got, axis=1) + 1e-9
            )

        fp32_cos = cosine_against_reference(float_path)
        log(f"fp32 parity cosine: mean={fp32_cos.mean():.6f} min={fp32_cos.min():.6f}")

        chosen, precision, cos = float_path, "float32", fp32_cos
        if quantize_int8(float_path, int8_path):
            int8_cos = cosine_against_reference(int8_path)
            log(f"int8 parity cosine: mean={int8_cos.mean():.6f} min={int8_cos.min():.6f}")
            if int8_cos.min() >= SPEAKER_MIN_COSINE:
                chosen, precision, cos = int8_path, "int8-dynamic", int8_cos
            else:
                log(
                    f"int8 rejected: min cosine {int8_cos.min():.6f} is below the "
                    f"{SPEAKER_MIN_COSINE} bar, so quantisation is changing the embedding. "
                    f"Shipping float32 instead."
                )

        ASSETS.mkdir(parents=True, exist_ok=True)
        shipped.write_bytes(chosen.read_bytes())
        in_shape, out_shape = io_shapes(shipped)

        report["speaker"] = {
            "status": "ok",
            "runtime": "onnxruntime",
            "precision": precision,
            "input_shape": in_shape,
            "output_shape": out_shape,
            "parity_cosine_mean": float(cos.mean()),
            "parity_cosine_min": float(cos.min()),
            "fp32_parity_cosine_min": float(fp32_cos.min()),
            "partials_tested": int(partials.shape[0]),
            "size_bytes": shipped.stat().st_size,
        }
        log(f"shipping {shipped.name} [{precision}] ({shipped.stat().st_size / 1e6:.2f} MB)")
    except Exception:  # noqa: BLE001
        log(f"speaker encoder conversion failed:\n{traceback.format_exc()}")
        report["speaker"] = {"status": "failed", "error": traceback.format_exc(limit=3)}


# -------------------------------------------------------------------------------- spoof detector

def spoof_windows(clips: list[np.ndarray]) -> np.ndarray:
    rows = []
    for clip in clips:
        start = 0
        while start + M.AASIST_SAMPLES <= len(clip):
            rows.append(clip[start:start + M.AASIST_SAMPLES])
            start += M.AASIST_SAMPLES // 2
    return np.stack(rows).astype(np.float32)


def convert_spoof(clips: list[np.ndarray], report: dict) -> None:
    log("=== anti-spoofing model ===")
    windows = spoof_windows(clips)
    log(f"parity windows: {windows.shape}")

    float_path = WORK / "spoof_detector_fp32.onnx"
    int8_path = WORK / "spoof_detector_int8.onnx"
    shipped = ASSETS / "spoof_detector.onnx"

    try:
        model = M.SpoofDetector(AASIST_REPO, AASIST_CONF, AASIST_WEIGHTS).eval()

        with torch.no_grad():
            reference_logits = model(torch.from_numpy(windows)).numpy()
        reference_p = M.synthetic_probability_from_logits(reference_logits)

        # Verify the class ordering against genuine human speech rather than trusting a comment.
        # These LibriSpeech clips are real recordings; if the model called them synthetic, the two
        # logits would be the other way round and every score in the app would be inverted.
        mean_on_bonafide = float(reference_p.mean())
        ordering_ok = mean_on_bonafide < 0.5
        log(f"PyTorch P(synthetic) on genuine LibriSpeech: mean={mean_on_bonafide:.4f} "
            f"({'ordering confirmed' if ordering_ok else 'ORDERING LOOKS WRONG'})")

        export_onnx(model, torch.zeros(1, M.AASIST_SAMPLES), float_path)

        def delta_against_reference(path: Path) -> np.ndarray:
            got = M.synthetic_probability_from_logits(run_onnx(path, windows))
            return np.abs(reference_p - got)

        fp32_delta = delta_against_reference(float_path)
        log(f"fp32 parity |dP|: max={fp32_delta.max():.6f} mean={fp32_delta.mean():.6f}")

        chosen, precision, delta = float_path, "float32", fp32_delta
        if quantize_int8(float_path, int8_path):
            int8_delta = delta_against_reference(int8_path)
            log(f"int8 parity |dP|: max={int8_delta.max():.6f} mean={int8_delta.mean():.6f}")
            if int8_delta.max() <= SPOOF_MAX_DELTA:
                chosen, precision, delta = int8_path, "int8-dynamic", int8_delta
            else:
                log(
                    f"int8 rejected: max |dP| {int8_delta.max():.4f} exceeds the "
                    f"{SPOOF_MAX_DELTA} bar. A quantisation that moves synthetic_probability that "
                    f"far would be moving verdicts across the alert threshold. Shipping float32."
                )

        ASSETS.mkdir(parents=True, exist_ok=True)
        shipped.write_bytes(chosen.read_bytes())
        in_shape, out_shape = io_shapes(shipped)

        report["spoof"] = {
            "status": "ok",
            "runtime": "onnxruntime",
            "precision": precision,
            "input_shape": in_shape,
            "output_shape": out_shape,
            "class_order_verified": bool(ordering_ok),
            "pytorch_mean_p_synthetic_on_bonafide": mean_on_bonafide,
            "parity_max_abs_delta": float(delta.max()),
            "parity_mean_abs_delta": float(delta.mean()),
            "fp32_parity_max_abs_delta": float(fp32_delta.max()),
            "windows_tested": int(windows.shape[0]),
            "size_bytes": shipped.stat().st_size,
        }
        log(f"shipping {shipped.name} [{precision}] ({shipped.stat().st_size / 1e6:.2f} MB)")
    except Exception:  # noqa: BLE001
        log(f"anti-spoofing conversion failed:\n{traceback.format_exc()}")
        report["spoof"] = {"status": "failed", "error": traceback.format_exc(limit=3)}


# ------------------------------------------------------------------------------------------ main

def write_manifest(report: dict) -> None:
    speaker = report.get("speaker", {})
    spoof = report.get("spoof", {})
    both_ok = speaker.get("status") == "ok" and spoof.get("status") == "ok"

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest = {
        "model_id": f"resemblyzer+aasist-onnx-{stamp[:10]}" if both_ok else "conversion-incomplete",
        "speaker_encoder": "Resemblyzer VoiceEncoder (GE2E), mel front end folded into the graph",
        "spoof_detector": "AASIST (clovaai), ASVspoof 2019 LA pretrained checkpoint",
        "runtime": "onnxruntime-android",
        "quantization": f"speaker={speaker.get('precision', 'n/a')}, "
                        f"spoof={spoof.get('precision', 'n/a')}",
        "converted_at_utc": stamp,
        "speaker_parity_cosine": speaker.get("parity_cosine_mean"),
        "spoof_parity_max_abs_delta": spoof.get("parity_max_abs_delta"),
        "notes": "Parity measured against the PyTorch reference on 3 LibriSpeech speakers. These "
                 "are conversion-fidelity figures only: they show the exported model computes what "
                 "the checkpoint computes. They say nothing about how well either model detects a "
                 "clone, which requires a labelled evaluation set and has not been measured.",
        "detail": report,
    }
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "manifest.json").write_text(json.dumps(manifest, indent=2))
    log(f"manifest written to {ASSETS / 'manifest.json'}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=["speaker", "spoof"], default=None)
    args = parser.parse_args()

    for path, what in [(RESEMBLYZER_CKPT, "Resemblyzer checkpoint"),
                       (AASIST_WEIGHTS, "AASIST checkpoint")]:
        if not path.exists():
            log(f"missing {what}: {path}. Run tools/fetch_checkpoints.sh first.")
            return 2

    log("loading calibration clips (3 LibriSpeech speakers)")
    clips = load_calibration_clips()

    report: dict = {}
    if args.only in (None, "speaker"):
        convert_speaker(clips, report)
    if args.only in (None, "spoof"):
        convert_spoof(clips, report)

    write_manifest(report)

    ok = all(v.get("status") == "ok" for v in report.values())
    log("SUCCESS" if ok else "FINISHED WITH FAILURES — see manifest detail")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
