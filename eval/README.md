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

2. **Labelled manifest** — `eval/manifest.csv` lists **19 IKIGAI 206 sessions** across three handsets
   (LAVA, Redmi Note 12 Pro 5G, Samsung S24): genuine calls, AI clones, channel variants
   (`probe_channel`: `mic`, `voip-wb`, `voip-nb`), and a cross-speaker benign check. Paths are
   relative to the repo root.

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

## Field devices (IKIGAI 206)

| Handset | Notes |
|---------|--------|
| LAVA LXX504 | Latency reference; capture diagnostics |
| Xiaomi Redmi Note 12 Pro 5G | Primary hackathon demo; iPhone→Android cellular validated |
| Samsung Galaxy S24 | Second validation device |

Accuracy/precision/recall in the root README were measured across labelled sessions on these devices.

## Manifest columns

| Column | Description |
|--------|-------------|
| `session_id` | Unique session name |
| `enrol_audio` | Enrolment clip (known-genuine) |
| `probe_audio` | Clip scored against the enrolment |
| `label` | `genuine` (benign) or `clone` (attack) |
| `contact_name` | Enrolled contact |
| `source` | `regression` or `hackathon` |
| `device` | Handset used during IKIGAI 206 field validation |
| `probe_channel` | `mic`, `voip-wb`, or `voip-nb` (simulated call channel) |
| `notes` | Session context |

Audio lives under `app/src/androidTest/assets/` (aditya pair) and `eval/datasets/voice/` (second speaker pair).

## Relationship to the app

| Component | App | Benchmark |
|-----------|-----|-----------|
| Models | ONNX in APK | PyTorch reference (parity-checked) |
| Per-window fusion | `Fusion.kt` | `analyze_clips.fuse()` |
| Session aggregation | `SessionScores.kt` | `eval/session_scores.py` |
| On-device regression | `VoiceDefenceModuleTest.kt` | `aditya-regression-*` rows in manifest |
