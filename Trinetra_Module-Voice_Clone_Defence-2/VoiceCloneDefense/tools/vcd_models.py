"""PyTorch reference models for Voice Clone Defense.

These are the definitions that get exported to TFLite and, just as importantly, the definitions the
converted models are checked against. Everything here runs on raw 16 kHz mono waveform so that the
Android side never has to reimplement a feature front end — see the note on MelFrontend below.
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import librosa
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

SAMPLE_RATE = 16_000

# Resemblyzer's partial-utterance geometry.
MEL_N_FFT = 400          # 25 ms
MEL_HOP = 160            # 10 ms
MEL_N_CHANNELS = 40
PARTIAL_FRAMES = 160     # 1.6 s
PARTIAL_SAMPLES = PARTIAL_FRAMES * MEL_HOP  # 25 600

# AASIST's fixed input width.
AASIST_SAMPLES = 64_600

# Resemblyzer normalises loudness before embedding. Skipping it would shift every similarity
# score, so the Android side does the identical thing in AudioNormalize.kt.
AUDIO_NORM_TARGET_DBFS = -30.0


def normalize_volume(wav: np.ndarray, target_dbfs: float = AUDIO_NORM_TARGET_DBFS,
                     increase_only: bool = True) -> np.ndarray:
    """Loudness normalisation matching Resemblyzer's audio.normalize_volume.

    Deliberately kept out of the exported graph: it is a single scalar gain, it is trivial to
    reimplement identically in Kotlin, and it has to be computed over the whole utterance rather
    than per 1.6 s partial to match the reference behaviour.
    """
    rms = np.sqrt(np.mean((wav.astype(np.float64)) ** 2))
    if rms < 1e-10:
        return wav
    dbfs = 20 * np.log10(rms)
    delta = target_dbfs - dbfs
    if increase_only and delta < 0:
        return wav
    return (wav * (10 ** (delta / 20))).astype(np.float32)


class MelFrontend(nn.Module):
    """librosa.feature.melspectrogram, expressed as conv1d so it survives the export chain.

    torch.stft exports to an ONNX STFT op that the ONNX->TF->TFLite path handles inconsistently.
    A DFT written as a fixed convolution is mathematically the same thing and lowers to ops every
    backend supports, which turns "does the front end convert" from a risk into a non-question.

    Folding the front end into the graph also means there is exactly one implementation of this
    arithmetic, validated here against librosa, instead of a second hand-written copy in Kotlin
    that could drift and quietly degrade every similarity score the app reports.
    """

    def __init__(self, n_fft=MEL_N_FFT, hop=MEL_HOP, n_mels=MEL_N_CHANNELS,
                 n_frames=PARTIAL_FRAMES, sr=SAMPLE_RATE):
        super().__init__()
        self.n_fft, self.hop, self.n_mels, self.n_frames = n_fft, hop, n_mels, n_frames
        n_freq = n_fft // 2 + 1

        window = torch.hann_window(n_fft, periodic=True, dtype=torch.float64)
        n = torch.arange(n_fft, dtype=torch.float64)
        k = torch.arange(n_freq, dtype=torch.float64).unsqueeze(1)
        angle = 2.0 * math.pi * k * n / n_fft
        real = torch.cos(angle) * window
        imag = -torch.sin(angle) * window
        dft = torch.cat([real, imag], dim=0).unsqueeze(1).float()
        self.register_buffer("dft", dft)  # [2 * n_freq, 1, n_fft]

        mel_fb = librosa.filters.mel(
            sr=sr, n_fft=n_fft, n_mels=n_mels, htk=False, norm="slaney"
        )
        self.register_buffer("mel_fb", torch.from_numpy(mel_fb).float())  # [n_mels, n_freq]

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        """[B, L] waveform -> [B, n_frames, n_mels] power-mel, matching librosa's defaults."""
        x = wav.unsqueeze(1)
        # librosa center=True with pad_mode='constant' (its default since 0.10), expressed as a
        # concat rather than F.pad. Numerically identical, but torch exports F.pad as an ONNX Pad
        # whose shape handling onnx2tf's flatbuffer fast path mis-lowers — it emits a RESHAPE that
        # tries to collapse 26 000 elements into 1 and the interpreter refuses to load the model.
        # Concat exports as a Concat and converts cleanly.
        pad = self.n_fft // 2
        zeros = torch.zeros(x.shape[0], 1, pad, dtype=x.dtype, device=x.device)
        x = torch.cat([zeros, x, zeros], dim=-1)
        spec = F.conv1d(x, self.dft, stride=self.hop)  # [B, 2 * n_freq, T]
        n_freq = self.dft.shape[0] // 2
        real, imag = spec[:, :n_freq, :], spec[:, n_freq:, :]
        power = real * real + imag * imag  # power=2.0
        mel = torch.matmul(self.mel_fb, power)  # [B, n_mels, T]
        # Resemblyzer feeds raw power mel — no log, no dB. Unusual, but it is what the encoder
        # was trained on, and "fixing" it here would silently invalidate the checkpoint.
        mel = mel[:, :, : self.n_frames]
        return mel.transpose(1, 2)  # [B, n_frames, n_mels]


class SpeakerEncoder(nn.Module):
    """Resemblyzer's VoiceEncoder with the mel front end attached.

    in : [B, PARTIAL_SAMPLES] waveform in [-1, 1], already loudness-normalised
    out: [B, 256] L2-normalised embedding
    """

    def __init__(self, hidden=256, layers=3, embed=256, unroll: bool = False):
        super().__init__()
        self.frontend = MelFrontend()
        self.lstm = nn.LSTM(MEL_N_CHANNELS, hidden, layers, batch_first=True)
        self.linear = nn.Linear(hidden, embed)
        self.relu = nn.ReLU()
        self.unroll = unroll
        self.hidden, self.layers = hidden, layers

    def load_resemblyzer(self, checkpoint: Path) -> "SpeakerEncoder":
        blob = torch.load(checkpoint, map_location="cpu", weights_only=False)
        state = blob["model_state"] if "model_state" in blob else blob
        missing, unexpected = self.load_state_dict(state, strict=False)
        # The front-end buffers are ours, not the checkpoint's; anything else missing is a real
        # problem and should not be shrugged off.
        real_missing = [k for k in missing if not k.startswith("frontend.")]
        # similarity_weight/bias are the GE2E training-loss scalars; they play no part in
        # inference and are expected to be left over in the published checkpoint.
        unexpected = [k for k in unexpected if k not in ("similarity_weight", "similarity_bias")]
        if real_missing or unexpected:
            raise RuntimeError(
                f"Resemblyzer checkpoint did not match the model definition.\n"
                f"  missing:    {real_missing}\n  unexpected: {list(unexpected)}"
            )
        return self

    def _lstm_unrolled(self, x: torch.Tensor) -> torch.Tensor:
        """Explicit LSTM loop, used when the fused LSTM op will not convert.

        Produces the same numbers as nn.LSTM but lowers to plain matmul/sigmoid/tanh, which every
        backend handles. Kept as a fallback rather than the default because the fused op is
        considerably faster on-device when it does convert.
        """
        b, t, _ = x.shape
        layer_in = x
        for layer in range(self.layers):
            w_ih = getattr(self.lstm, f"weight_ih_l{layer}")
            w_hh = getattr(self.lstm, f"weight_hh_l{layer}")
            b_ih = getattr(self.lstm, f"bias_ih_l{layer}")
            b_hh = getattr(self.lstm, f"bias_hh_l{layer}")
            h = torch.zeros(b, self.hidden, dtype=x.dtype)
            c = torch.zeros(b, self.hidden, dtype=x.dtype)
            outs = []
            for step in range(t):
                gates = F.linear(layer_in[:, step, :], w_ih, b_ih) + F.linear(h, w_hh, b_hh)
                i, f, g, o = gates.chunk(4, dim=1)
                i, f, o = torch.sigmoid(i), torch.sigmoid(f), torch.sigmoid(o)
                c = f * c + i * torch.tanh(g)
                h = o * torch.tanh(c)
                outs.append(h.unsqueeze(1))
            layer_in = torch.cat(outs, dim=1)
        return layer_in[:, -1, :]

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        mels = self.frontend(wav)
        if self.unroll:
            last = self._lstm_unrolled(mels)
        else:
            _, (hidden, _) = self.lstm(mels)
            last = hidden[-1]
        raw = self.relu(self.linear(last))
        return raw / (torch.norm(raw, dim=1, keepdim=True) + 1e-9)


class SpoofDetector(nn.Module):
    """AASIST wrapped to return just the two logits.

    Upstream returns (last_hidden, output) and scores with output[:, 1] as the bonafide score, so
    index 0 is spoof. convert_models.py asserts that ordering numerically against real audio
    rather than trusting this comment.

    in : [B, AASIST_SAMPLES] waveform in [-1, 1]
    out: [B, 2] logits ordered [spoof, bonafide]
    """

    def __init__(self, aasist_repo: Path, config_path: Path, weights: Path):
        super().__init__()
        repo = str(aasist_repo.resolve())
        if repo not in sys.path:
            sys.path.insert(0, repo)
        from models.AASIST import Model as AasistModel  # noqa: E402

        config = json.loads(Path(config_path).read_text())
        self.net = AasistModel(config["model_config"])
        self.net.load_state_dict(torch.load(weights, map_location="cpu", weights_only=False))
        self.net.eval()

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        _, logits = self.net(wav, Freq_aug=False)
        return logits


def split_partials(wav: np.ndarray, width: int = PARTIAL_SAMPLES,
                   hop: int = PARTIAL_SAMPLES // 2) -> list[np.ndarray]:
    """Resemblyzer-style 50 %-overlapping partials, matching VerificationPipeline.embedUtterance."""
    if len(wav) < width:
        raise ValueError(f"need at least {width} samples, got {len(wav)}")
    out, start = [], 0
    while start + width <= len(wav):
        out.append(wav[start:start + width])
        start += hop
    if start - hop + width < len(wav):
        out.append(wav[-width:])
    return out


def embed_utterance(model: SpeakerEncoder, wav: np.ndarray) -> np.ndarray:
    """Average partial embeddings and re-normalise — the reference for the Kotlin implementation."""
    partials = split_partials(normalize_volume(wav))
    with torch.no_grad():
        batch = torch.from_numpy(np.stack(partials)).float()
        embeds = model(batch).numpy()
    mean = embeds.mean(axis=0)
    return mean / (np.linalg.norm(mean) + 1e-9)


def synthetic_probability_from_logits(logits: np.ndarray) -> np.ndarray:
    """softmax over [spoof, bonafide], returning P(spoof)."""
    shifted = logits - logits.max(axis=-1, keepdims=True)
    exp = np.exp(shifted)
    return (exp[..., 0] / exp.sum(axis=-1)).astype(np.float32)
