#!/usr/bin/env python3
"""Reproducible benchmark for TriNetra voice-clone defence scores.

Reads eval/manifest.csv, runs each labelled session through the same pipeline geometry as the
Android app (PyTorch reference models matching shipped ONNX), and reports precision / recall /
accuracy at session level using median-of-five stabilisation (SessionScores).

Usage (from repo root, after model checkpoints are fetched — see eval/README.md):

    python eval/benchmark.py
    python eval/benchmark.py --manifest eval/manifest.csv --out eval/output

Add rows to manifest.csv to grow the labelled corpus. Each row needs enrol_audio, probe_audio,
and label: genuine | clone | impostor.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
TOOLS_DIR = REPO_ROOT / "Trinetra_Module-Voice_Clone_Defence-2" / "VoiceCloneDefense" / "tools"
sys.path.insert(0, str(TOOLS_DIR))

import analyze_clips as ac  # noqa: E402
import vcd_models as M  # noqa: E402
from channel_experiment import (  # noqa: E402
    channel_mic,
    channel_voip_narrowband,
    channel_voip_wideband,
)
from session_scores import SessionScores, WindowAnalysis, WindowVerdict  # noqa: E402

CHANNELS = [
    ("mic", channel_mic),
    ("voip-wb", channel_voip_wideband),
    ("voip-nb", channel_voip_narrowband),
]

POSITIVE_LABELS = {"clone", "impostor", "attack", "scam"}
NEGATIVE_LABELS = {"genuine", "benign", "safe"}


@dataclass
class Voiceprint:
    label: str
    embedding: np.ndarray
    baseline: float | None


@dataclass
class SessionResult:
    session_id: str
    label: str
    contact_name: str
    source: str
    peak_level: str
    stable_peak_level: str
    stable_level: str
    windows: int
    median_similarity: float | None
    median_synthetic: float | None
    baseline: float | None
    notes: str


def repo_path(rel: str) -> Path:
    return (REPO_ROOT / rel).resolve()


def enrol_variants(encoder, detector, wav: np.ndarray) -> list[Voiceprint]:
    out: list[Voiceprint] = []
    for label, fn in CHANNELS:
        degraded = fn(wav.copy(), M.SAMPLE_RATE)
        embedding = M.embed_utterance(encoder, degraded)
        baseline = ac.measure_synthetic_baseline(detector, degraded)
        out.append(Voiceprint(label, embedding, baseline))
    return out


def best_match(voiceprints: list[Voiceprint], embedding: np.ndarray) -> tuple[float, Voiceprint]:
    best_sim = -1.0
    best_vp = voiceprints[0]
    for vp in voiceprints:
        sim = float(np.dot(embedding, vp.embedding))
        if sim > best_sim:
            best_sim = sim
            best_vp = vp
    return best_sim, best_vp


def score_session(
    encoder,
    detector,
    voiceprints: list[Voiceprint],
    probe_wav: np.ndarray,
    contact_name: str,
) -> SessionResult:
    scores = SessionScores()

    def fuse_for_session(sim: float | None, syn: float | None) -> WindowVerdict:
        # Stabiliser re-fuses median scores without a per-window best print; use first baseline.
        baseline = voiceprints[0].baseline
        level, reason = ac.fuse(sim, syn, baseline)
        return WindowVerdict(level, reason, sim, syn, contact_name)

    sims: list[float] = []
    syns: list[float] = []

    for start, window in ac.slice_windows(probe_wav):
        rms = float(np.sqrt((window ** 2).mean()))
        if rms < ac.MIN_RMS:
            verdict = WindowVerdict("INDETERMINATE", "NO_SPEECH", None, None, contact_name)
        else:
            import torch

            with torch.no_grad():
                logits = detector(torch.from_numpy(window).unsqueeze(0)).numpy()
            synthetic = float(M.synthetic_probability_from_logits(logits)[0])
            embedding = M.embed_utterance(encoder, window)
            similarity, matched = best_match(voiceprints, embedding)
            level, reason = ac.fuse(similarity, synthetic, matched.baseline)
            verdict = WindowVerdict(level, reason, similarity, synthetic, contact_name)
            sims.append(similarity)
            syns.append(synthetic)

        analysis = WindowAnalysis(start / M.SAMPLE_RATE, verdict)
        scores = scores.accept(analysis, fuse_for_session)

    return SessionResult(
        session_id="",
        label="",
        contact_name=contact_name,
        source="",
        peak_level=scores.peak_level,
        stable_peak_level=scores.stable_peak_level,
        stable_level=scores.stable_level,
        windows=scores.measured_windows,
        median_similarity=float(np.median(sims)) if sims else None,
        median_synthetic=float(np.median(syns)) if syns else None,
        baseline=voiceprints[0].baseline,
        notes="",
    )


def is_positive_prediction(level: str, tier: str) -> bool:
    if tier == "critical":
        return level == "CRITICAL"
    if tier == "high_plus":
        return level in {"SUSPICIOUS", "CRITICAL"}
    return level in {"SUSPICIOUS", "CRITICAL"}


def is_positive_label(label: str) -> bool:
    return label.strip().lower() in POSITIVE_LABELS


def compute_metrics(results: list[SessionResult], tier: str) -> dict:
    tp = fp = tn = fn = 0
    indeterminate = 0
    for r in results:
        level = r.stable_peak_level
        if level == "INDETERMINATE":
            indeterminate += 1
            continue
        predicted = is_positive_prediction(level, tier)
        actual = is_positive_label(r.label)
        if predicted and actual:
            tp += 1
        elif predicted and not actual:
            fp += 1
        elif not predicted and actual:
            fn += 1
        else:
            tn += 1
    total = tp + fp + tn + fn
    precision = tp / (tp + fp) if (tp + fp) else None
    recall = tp / (tp + fn) if (tp + fn) else None
    accuracy = (tp + tn) / total if total else None
    fpr = fp / (fp + tn) if (fp + tn) else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and (precision + recall) > 0
        else None
    )
    return {
        "tier": tier,
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
        "indeterminate_sessions": indeterminate,
        "precision": precision,
        "recall": recall,
        "accuracy": accuracy,
        "fpr": fpr,
        "f1": f1,
    }


def load_manifest(path: Path) -> list[dict]:
    rows = []
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("session_id", "").startswith("#"):
                continue
            rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="TriNetra VCD labelled benchmark")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=REPO_ROOT / "eval" / "manifest.csv",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=REPO_ROOT / "eval" / "output",
    )
    args = parser.parse_args()

    manifest_path = args.manifest.resolve()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = load_manifest(manifest_path)
    if not rows:
        print(f"No sessions in {manifest_path}")
        return 2

    print("Loading PyTorch reference models (parity-checked against shipped ONNX)...")
    encoder = M.SpeakerEncoder().eval().load_resemblyzer(ac.RESEMBLYZER_CKPT)
    detector = M.SpoofDetector(ac.AASIST_REPO, ac.AASIST_CONF, ac.AASIST_WEIGHTS).eval()

    results: list[SessionResult] = []
    for row in rows:
        enrol = repo_path(row["enrol_audio"])
        probe = repo_path(row["probe_audio"])
        if not enrol.exists() or not probe.exists():
            print(f"SKIP {row['session_id']}: missing audio ({enrol.name} / {probe.name})")
            continue

        enrol_wav = ac.load_16k(enrol)
        probe_wav = ac.load_16k(probe)
        voiceprints = enrol_variants(encoder, detector, enrol_wav)
        session = score_session(
            encoder,
            detector,
            voiceprints,
            probe_wav,
            row.get("contact_name", ""),
        )
        session.session_id = row["session_id"]
        session.label = row["label"].strip().lower()
        session.source = row.get("source", "")
        session.notes = row.get("notes", "")
        results.append(session)
        print(
            f"{session.session_id:20} label={session.label:8} "
            f"peak={session.peak_level:13} stable_peak={session.stable_peak_level:13} "
            f"sim={session.median_similarity:.3f} syn={session.median_synthetic:.3f}"
            if session.median_similarity is not None and session.median_synthetic is not None
            else f"{session.session_id:20} label={session.label:8} peak={session.peak_level}"
        )

    if not results:
        print("No sessions scored — check manifest paths and model checkpoints.")
        return 2

    metrics_high = compute_metrics(results, "high_plus")
    metrics_critical = compute_metrics(results, "critical")

    sessions_csv = out_dir / "sessions.csv"
    with sessions_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(asdict(results[0]).keys()))
        writer.writeheader()
        for r in results:
            writer.writerow(asdict(r))

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "manifest": str(manifest_path.relative_to(REPO_ROOT)),
        "sessions_scored": len(results),
        "aggregation": "session-level stable_peak_level (median-of-five stabilisation)",
        "thresholds": {
            "similarity_high": ac.SIMILARITY_HIGH,
            "similarity_low": ac.SIMILARITY_LOW,
            "synthetic_high": ac.SYNTHETIC_HIGH,
            "synthetic_elevated": ac.SYNTHETIC_ELEVATED,
            "synthetic_baseline_margin": ac.SYNTHETIC_BASELINE_MARGIN,
        },
        "metrics": {
            "high_plus": metrics_high,
            "critical_only": metrics_critical,
        },
        "sessions": [asdict(r) for r in results],
    }

    metrics_json = out_dir / "metrics.json"
    metrics_json.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(f"\nWrote {sessions_csv.relative_to(REPO_ROOT)}")
    print(f"Wrote {metrics_json.relative_to(REPO_ROOT)}")
    print("\n--- high_plus (SUSPICIOUS or CRITICAL) ---")
    _print_metrics(metrics_high)
    print("\n--- critical_only (CRITICAL) ---")
    _print_metrics(metrics_critical)
    return 0


def _print_metrics(m: dict) -> None:
    def pct(x: float | None) -> str:
        return f"{x * 100:.1f}%" if x is not None else "n/a"

    print(
        f"  TP={m['tp']} FP={m['fp']} TN={m['tn']} FN={m['fn']} "
        f"indeterminate={m['indeterminate_sessions']}"
    )
    print(
        f"  precision={pct(m['precision'])}  recall={pct(m['recall'])}  "
        f"accuracy={pct(m['accuracy'])}  fpr={pct(m['fpr'])}  f1={pct(m['f1'])}"
    )


if __name__ == "__main__":
    raise SystemExit(main())
