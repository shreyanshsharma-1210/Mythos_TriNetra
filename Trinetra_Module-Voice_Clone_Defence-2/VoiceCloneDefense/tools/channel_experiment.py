"""
Does a voiceprint taken over the microphone still work on a voice arriving over a call?

Enrolment records directly from the microphone: clean, full-band, no codec. A VoIP call arrives
after the sender's noise suppression and automatic gain control, through a low-bitrate codec, and
out of a jitter buffer. That is a different signal carrying the same voice, and both models were
handed it without anyone checking what the difference costs.

This measures the cost, and whether enrolling *through the same channel* recovers it.

Honest about its own limits: there is no Opus encoder available here, so the VoIP conditions are
simulated — band-limiting, gain normalisation, additive noise, and for the narrowband case a real
G.711 mu-law round trip. That is enough to show the direction and rough size of a channel effect.
It is not a substitute for measuring against audio that has actually been through WebRTC, which is
what tools/analyze_clips.py plus a real call will give.

    tools/.venv/Scripts/python.exe tools/channel_experiment.py ../voice1.mp3 ../voice2.mp3
"""

from __future__ import annotations

import argparse
from pathlib import Path

import librosa
import numpy as np
import torch
from scipy.signal import butter, sosfilt

import vcd_models as M

WORK = Path(__file__).resolve().parent / "_work"
RESEMBLYZER_CKPT = WORK / "resemblyzer_pretrained.pt"
AASIST_REPO = WORK / "aasist"
AASIST_CONF = AASIST_REPO / "config" / "AASIST.conf"
AASIST_WEIGHTS = AASIST_REPO / "models" / "weights" / "AASIST.pth"

WINDOW_SAMPLES = 64_600
MIN_RMS = 0.005


# ---------------------------------------------------------------- channel simulation


def _bandpass(wav: np.ndarray, low: float, high: float, rate: int) -> np.ndarray:
    nyq = rate / 2
    high = min(high, nyq * 0.99)
    sos = butter(6, [low / nyq, high / nyq], btype="band", output="sos")
    return sosfilt(sos, wav).astype(np.float32)


def _mulaw_roundtrip(wav: np.ndarray) -> np.ndarray:
    """A real G.711 mu-law encode/decode, the codec behind ordinary telephony."""
    mu = 255.0
    x = np.clip(wav, -1.0, 1.0)
    encoded = np.sign(x) * np.log1p(mu * np.abs(x)) / np.log1p(mu)
    # 8-bit quantisation is the whole point of the codec; without it this is a no-op.
    quantised = np.round((encoded + 1.0) * 127.5) / 127.5 - 1.0
    decoded = np.sign(quantised) * (1.0 / mu) * ((1.0 + mu) ** np.abs(quantised) - 1.0)
    return decoded.astype(np.float32)


def _agc(wav: np.ndarray, target_dbfs: float = -23.0) -> np.ndarray:
    """Crude automatic gain control, as a sending device would apply."""
    rms = float(np.sqrt((wav ** 2).mean()))
    if rms <= 0:
        return wav
    gain = 10 ** ((target_dbfs - 20 * np.log10(rms)) / 20)
    return np.clip(wav * gain, -1.0, 1.0).astype(np.float32)


def _noise(wav: np.ndarray, snr_db: float) -> np.ndarray:
    rms = float(np.sqrt((wav ** 2).mean()))
    if rms <= 0:
        return wav
    noise_rms = rms / (10 ** (snr_db / 20))
    rng = np.random.default_rng(1234)
    return (wav + rng.normal(0, noise_rms, wav.shape)).astype(np.float32)


def channel_mic(wav: np.ndarray, rate: int) -> np.ndarray:
    """What enrolment sees today: the recording, untouched."""
    return wav


def channel_voip_wideband(wav: np.ndarray, rate: int) -> np.ndarray:
    """Roughly what a wideband VoIP call delivers: AGC, band-limiting, a little line noise."""
    out = _agc(wav)
    out = _bandpass(out, 100.0, 7000.0, rate)
    return _noise(out, snr_db=32.0)


def channel_voip_narrowband(wav: np.ndarray, rate: int) -> np.ndarray:
    """Classic telephony: 300-3400 Hz at 8 kHz through G.711, then back up to 16 kHz."""
    out = _agc(wav)
    out = _bandpass(out, 300.0, 3400.0, rate)
    down = librosa.resample(out, orig_sr=rate, target_sr=8000)
    down = _mulaw_roundtrip(down)
    up = librosa.resample(down, orig_sr=8000, target_sr=rate)
    return _noise(up, snr_db=28.0).astype(np.float32)


CHANNELS = {
    "mic": channel_mic,
    "voip-wb": channel_voip_wideband,
    "voip-nb": channel_voip_narrowband,
}


# ---------------------------------------------------------------- measurement


def synthetic_median(detector, wav: np.ndarray) -> float | None:
    scores = []
    for start in range(0, len(wav) - WINDOW_SAMPLES + 1, WINDOW_SAMPLES):
        window = wav[start:start + WINDOW_SAMPLES]
        if float(np.sqrt((window ** 2).mean())) < MIN_RMS:
            continue
        with torch.no_grad():
            logits = detector(torch.from_numpy(window).unsqueeze(0)).numpy()
        scores.append(float(M.synthetic_probability_from_logits(logits)[0]))
    return float(np.median(scores)) if scores else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("enrol", type=Path)
    parser.add_argument("probe", type=Path)
    args = parser.parse_args()

    for p in (args.enrol, args.probe):
        if not p.exists():
            print(f"missing file: {p}")
            return 2

    print("loading models (PyTorch reference — matches the shipped ONNX exports exactly)")
    encoder = M.SpeakerEncoder().eval().load_resemblyzer(RESEMBLYZER_CKPT)
    detector = M.SpoofDetector(AASIST_REPO, AASIST_CONF, AASIST_WEIGHTS).eval()

    enrol_raw = librosa.load(str(args.enrol), sr=M.SAMPLE_RATE, mono=True)[0].astype(np.float32)
    probe_raw = librosa.load(str(args.probe), sr=M.SAMPLE_RATE, mono=True)[0].astype(np.float32)
    print(f"enrol {args.enrol.name}: {len(enrol_raw)/M.SAMPLE_RATE:.1f}s")
    print(f"probe {args.probe.name}: {len(probe_raw)/M.SAMPLE_RATE:.1f}s")

    voiceprints = {}
    for name, fn in CHANNELS.items():
        degraded = fn(enrol_raw, M.SAMPLE_RATE)
        voiceprints[name] = M.embed_utterance(encoder, degraded)
        base = synthetic_median(detector, degraded)
        print(f"  enrolment through {name:8s}: anti-spoofing baseline {base:.4f}")

    print()
    print("=" * 78)
    print("SPEAKER SIMILARITY — same person, enrolment channel (rows) vs call channel (columns)")
    print("=" * 78)
    header = "  " + "".join(f"{c:>12}" for c in CHANNELS)
    print(f"{'':10}{header}")

    probes = {name: fn(probe_raw, M.SAMPLE_RATE) for name, fn in CHANNELS.items()}
    probe_embeds = {name: M.embed_utterance(encoder, wav) for name, wav in probes.items()}

    for enrol_ch in CHANNELS:
        row = f"{enrol_ch:10}"
        for probe_ch in CHANNELS:
            cos = float(np.dot(voiceprints[enrol_ch], probe_embeds[probe_ch]))
            row += f"{cos:>12.4f}"
        print(row + "  ")

    print()
    print("=" * 78)
    print("ANTI-SPOOFING on the probe, per channel")
    print("=" * 78)
    for probe_ch, wav in probes.items():
        print(f"  {probe_ch:10} synthetic_probability median {synthetic_median(detector, wav):.4f}")

    print()
    print("Read the similarity table down a column: that is the same call audio scored against")
    print("voiceprints enrolled through different channels. If the diagonal beats the top row, a")
    print("channel-matched voiceprint is worth storing.")
    print()
    print("Channel conditions here are simulated, not real Opus. Treat the direction as the")
    print("finding and the exact numbers as indicative.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
