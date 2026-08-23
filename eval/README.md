# TriNetra evaluation harness

Reproducible benchmark for voice-clone defence scores. Uses the same PyTorch reference models,
window geometry, fusion rules, and session stabilisation as the Android app.

## Setup

1. **Python 3.10+** with dependencies from the VCD tools tree:

```bash
cd Trinetra_Module-Voice_Clone_Defence-2/VoiceCloneDefense/tools
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt   # Windows
# source .venv/bin/activate && pip install -r requirements.txt   # Linux/macOS
bash fetch_checkpoints.sh                       # downloads Resemblyzer + AASIST weights
```

Or install eval-only deps at repo root: `pip install -r eval/requirements.txt` (models still live under `tools/_work/`).

2. **Labelled manifest** — `eval/manifest.csv` lists enrol clip, probe clip, and ground-truth label
   (`genuine`, `clone`, or `impostor`). Paths are relative to the repo root.

## Run

From the repo root:

```bash
python eval/benchmark.py
```

Outputs:

| File | Contents |
|------|----------|
| `eval/output/sessions.csv` | Per-session peak and stabilised levels, median scores |
| `eval/output/metrics.json` | Precision, recall, accuracy, FPR at **high_plus** and **critical_only** tiers |

Session verdicts use **stable_peak_level** (median-of-five stabilisation), matching the methodology
documented in the root README.

## Grow the corpus

Add rows to `manifest.csv`:

```csv
session_id,enrol_audio,probe_audio,label,contact_name,source,notes
bob-genuine,eval/datasets/bob/enrol.wav,eval/datasets/bob/genuine_call.wav,genuine,Bob,hackathon,Live demo recording
bob-clone,eval/datasets/bob/enrol.wav,eval/datasets/bob/clone.wav,clone,Bob,hackathon,ElevenLabs clone
```

Place audio under `eval/datasets/` (or reference existing paths under `app/src/androidTest/assets/`).

## Relationship to the app

| Component | App | Benchmark |
|-----------|-----|-----------|
| Models | ONNX in APK | PyTorch reference (parity-checked) |
| Per-window fusion | `Fusion.kt` | `analyze_clips.fuse()` |
| Session aggregation | `SessionScores.kt` | `eval/session_scores.py` |
| On-device regression | `VoiceDefenceModuleTest.kt` | `aditya-*` rows in manifest |
