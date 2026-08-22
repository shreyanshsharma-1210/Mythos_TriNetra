"""Run audio clips through the reference pipeline and print the scores the app would produce.

    tools/.venv/Scripts/python.exe tools/analyze_clips.py --enrol voice1.mp3 voice1.mp3 voice2.mp3

This is the desk-side twin of Test Mode. It uses the same checkpoints, the same window geometry,
the same loudness normalisation and the same fusion rules as the Android app, so the numbers it
prints are the numbers the app prints — the ONNX exports the app ships match these PyTorch
references to a cosine of 1.000000 and a max |dP| of 4.8e-07 (see convert_models.py).

What it is for: calibrating thresholds against real clips before trusting a borderline verdict,
and checking a demo pair without needing a device in your hand.

What it is NOT: an accuracy measurement. It reports what the models output. Whether those outputs
are correct for your clips is a separate question that needs labelled data.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import librosa
import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
import vcd_models as M  # noqa: E402

WORK = Path(__file__).parent / "_work"
RESEMBLYZER_CKPT = WORK / "resemblyzer_pretrained.pt"
AASIST_REPO = WORK / "aasist"
AASIST_CONF = AASIST_REPO / "config" / "AASIST.conf"
AASIST_WEIGHTS = AASIST_REPO / "models" / "weights" / "AASIST.pth"

# Mirrors AudioConstants.kt.
WINDOW_SAMPLES = 64_600
FILE_HOP_SAMPLES = 32_300

# Mirrors FusionThresholds.PROVISIONAL. Uncalibrated starting points, not measured operating
# points — which is the whole reason this script exists.
SIMILARITY_HIGH = 0.75
SIMILARITY_LOW = 0.60
SYNTHETIC_HIGH = 0.50
SYNTHETIC_ELEVATED = 0.30
MIN_RMS = 0.005
SYNTHETIC_BASELINE_MARGIN = 0.15


@dataclass
class WindowScore:
    start_s: float
    rms: float
    similarity: float | None
    synthetic: float | None
    level: str
    reason: str


def spoof_check_status(baseline: float | None) -> str:
    """Mirrors Fusion.spoofCheckStatus."""
    if baseline is None:
        return "NO_BASELINE"
    if baseline + SYNTHETIC_BASELINE_MARGIN >= 1.0:
        return "UNRELIABLE"
    return "USABLE"


def effective_synthetic_threshold(baseline: float | None) -> float:
    """Mirrors Fusion.effectiveSyntheticThreshold."""
    if baseline is None:
        return SYNTHETIC_HIGH
    return max(SYNTHETIC_HIGH, baseline + SYNTHETIC_BASELINE_MARGIN)


def fuse(
    similarity: float | None,
    synthetic: float | None,
    baseline: float | None = None,
) -> tuple[str, str]:
    """Byte-for-byte the rules in Fusion.kt."""
    if synthetic is None:
        return "INDETERMINATE", "PIPELINE_UNAVAILABLE"
    if similarity is None:
        if synthetic >= SYNTHETIC_HIGH:
            return "SUSPICIOUS", "SYNTHETIC_UNKNOWN_SPEAKER"
        return "INDETERMINATE", "NO_VOICEPRINT_SELECTED"

    high = similarity >= SIMILARITY_HIGH
    low = similarity < SIMILARITY_LOW
    spoof_check = spoof_check_status(baseline)
    threshold = effective_synthetic_threshold(baseline)

    # A baseline measured at or near the ceiling means the detector already called this person's
    # own genuine recording synthetic, so it cannot distinguish them from a clone of them and its
    # output is dropped. NO_BASELINE deliberately does not get this treatment: "never measured" is
    # a different claim from "measured and useless", and suppressing on it would silently disable
    # detection for every uncalibrated contact.
    if high and spoof_check == "UNRELIABLE":
        return "SAFE", "MATCH_SPOOF_CHECK_UNRELIABLE"
    if high and synthetic >= threshold:
        return "CRITICAL", "CLONE_SIGNATURE"
    if high and synthetic >= SYNTHETIC_ELEVATED:
        return "SUSPICIOUS", "POSSIBLE_SYNTHESIS"
    if high:
        return "SAFE", "MATCH_AUTHENTIC"
    if low:
        return "SUSPICIOUS", "NOT_CLAIMED_CONTACT"
    return "SUSPICIOUS", "BORDERLINE_SIMILARITY"


def load_16k(path: Path) -> np.ndarray:
    wav, _ = librosa.load(str(path), sr=M.SAMPLE_RATE, mono=True)
    return wav.astype(np.float32)


def slice_windows(wav: np.ndarray) -> list[tuple[int, np.ndarray]]:
    """Mirrors WindowSlicer.slice — no padding, and the tail window is not dropped."""
    if len(wav) < WINDOW_SAMPLES:
        return []
    out, start = [], 0
    while start + WINDOW_SAMPLES <= len(wav):
        out.append((start, wav[start:start + WINDOW_SAMPLES]))
        start += FILE_HOP_SAMPLES
    last = len(wav) - WINDOW_SAMPLES
    if out and out[-1][0] < last:
        out.append((last, wav[last:]))
    return out


def measure_synthetic_baseline(
    detector: M.SpoofDetector,
    wav: np.ndarray,
) -> float | None:
    """Mirrors VerificationPipeline.measureSyntheticBaseline — non-overlapping windows, median."""
    if len(wav) < WINDOW_SAMPLES:
        return None
    scores = []
    for start in range(0, len(wav) - WINDOW_SAMPLES + 1, WINDOW_SAMPLES):
        window = wav[start:start + WINDOW_SAMPLES]
        if float(np.sqrt((window ** 2).mean())) < MIN_RMS:
            continue
        with torch.no_grad():
            logits = detector(torch.from_numpy(window).unsqueeze(0)).numpy()
        scores.append(float(M.synthetic_probability_from_logits(logits)[0]))
    return float(np.median(scores)) if scores else None


def analyse(
    encoder: M.SpeakerEncoder,
    detector: M.SpoofDetector,
    wav: np.ndarray,
    voiceprint: np.ndarray | None,
    baseline: float | None = None,
) -> list[WindowScore]:
    scores = []
    for start, window in slice_windows(wav):
        rms = float(np.sqrt((window ** 2).mean()))
        if rms < MIN_RMS:
            scores.append(WindowScore(start / M.SAMPLE_RATE, rms, None, None,
                                      "INDETERMINATE", "NO_SPEECH"))
            continue

        with torch.no_grad():
            logits = detector(torch.from_numpy(window).unsqueeze(0)).numpy()
        synthetic = float(M.synthetic_probability_from_logits(logits)[0])

        similarity = None
        if voiceprint is not None:
            embedding = M.embed_utterance(encoder, window)
            similarity = float(np.dot(embedding, voiceprint))

        level, reason = fuse(similarity, synthetic, baseline)
        scores.append(WindowScore(start / M.SAMPLE_RATE, rms, similarity, synthetic, level, reason))
    return scores


def median(values: list[float]) -> float | None:
    return float(np.median(values)) if values else None


SEVERITY = {"INDETERMINATE": 0, "SAFE": 1, "SUSPICIOUS": 2, "CRITICAL": 3}


def report(
    name: str,
    scores: list[WindowScore],
    enrolled_from: str | None,
    baseline: float | None = None,
) -> None:
    print(f"\n{'=' * 78}\n{name}")
    if enrolled_from:
        print(f"  compared against the voiceprint from {enrolled_from}")
        status = spoof_check_status(baseline)
        if baseline is None:
            print("  no anti-spoofing baseline (enrolment clip too short to measure one)")
        else:
            print(f"  anti-spoofing baseline on the enrolment clip: {baseline:.4f}  ->  {status}")
            if status == "UNRELIABLE":
                print("  the detector called known-genuine audio synthetic, so its output is dropped")
            else:
                print(f"  effective alert threshold for this contact: "
                      f"{effective_synthetic_threshold(baseline):.4f}")
    print("=" * 78)

    if not scores:
        print("  clip is shorter than one 4.04 s window — nothing to score")
        return

    print(f"  {'start':>7}  {'rms':>7}  {'similarity':>10}  {'synthetic':>9}  {'level':<13} reason")
    for s in scores:
        sim = f"{s.similarity:.4f}" if s.similarity is not None else "     —"
        syn = f"{s.synthetic:.4f}" if s.synthetic is not None else "    —"
        print(f"  {s.start_s:6.1f}s  {s.rms:7.4f}  {sim:>10}  {syn:>9}  {s.level:<13} {s.reason}")

    sims = [s.similarity for s in scores if s.similarity is not None]
    syns = [s.synthetic for s in scores if s.synthetic is not None]
    peak = max(scores, key=lambda s: SEVERITY[s.level])

    print(f"\n  windows            : {len(scores)} ({len(syns)} measured, {len(scores) - len(syns)} too quiet)")
    if sims:
        print(f"  similarity  median : {median(sims):.4f}   range {min(sims):.4f} .. {max(sims):.4f}")
    if syns:
        print(f"  synthetic   median : {median(syns):.4f}   range {min(syns):.4f} .. {max(syns):.4f}")
    print(f"  worst window       : {peak.level} ({peak.reason}) at {peak.start_s:.1f}s")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("clips", nargs="+", type=Path)
    parser.add_argument("--enrol", type=Path, default=None,
                        help="clip to build the voiceprint from; omit for a spoof-only run")
    args = parser.parse_args()

    for path in [*args.clips, *( [args.enrol] if args.enrol else [] )]:
        if not path.exists():
            print(f"missing file: {path}")
            return 2

    print("loading models (PyTorch reference — matches the shipped ONNX exports exactly)")
    encoder = M.SpeakerEncoder().eval().load_resemblyzer(RESEMBLYZER_CKPT)
    detector = M.SpoofDetector(AASIST_REPO, AASIST_CONF, AASIST_WEIGHTS).eval()

    voiceprint = None
    baseline = None
    if args.enrol:
        enrol_wav = load_16k(args.enrol)
        print(f"enrolling from {args.enrol.name}: {len(enrol_wav) / M.SAMPLE_RATE:.1f}s of audio")
        voiceprint = M.embed_utterance(encoder, enrol_wav)
        # The enrolment clip is the one recording whose provenance is not in doubt, so it is where
        # the app learns what this detector does on genuine audio from this voice. Without it, an
        # ASVspoof-domain mismatch reads identically to a successful clone.
        baseline = measure_synthetic_baseline(detector, enrol_wav)

    for clip in args.clips:
        wav = load_16k(clip)
        scores = analyse(encoder, detector, wav, voiceprint, baseline)
        report(f"{clip.name}  ({len(wav) / M.SAMPLE_RATE:.1f}s)", scores,
               args.enrol.name if args.enrol else None, baseline)

    # Whole-clip embeddings are the cleanest identity comparison available: they use every second
    # of each clip rather than one 4 s window, which is exactly what enrolment does.
    if len(args.clips) > 1:
        print(f"\n{'=' * 78}\nwhole-clip speaker similarity (cosine between full-utterance embeddings)\n{'=' * 78}")
        embeds = {c.name: M.embed_utterance(encoder, load_16k(c)) for c in args.clips}
        names = list(embeds)
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                cos = float(np.dot(embeds[names[i]], embeds[names[j]]))
                verdict = ("same speaker (above the 0.75 match threshold)" if cos >= SIMILARITY_HIGH
                           else "different speakers (below the 0.60 floor)" if cos < SIMILARITY_LOW
                           else "inconclusive (between the thresholds)")
                print(f"  {names[i]} vs {names[j]}: {cos:.4f}  ->  {verdict}")

    print("\nThresholds above are provisional defaults, not measured operating points.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
