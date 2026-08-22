# Voice Clone Defense

A standalone Android app that checks a caller's voice against a voiceprint someone enrolled in
advance, and — separately and independently — checks whether that voice shows signs of being
AI-generated.

Proof-of-concept for TRINETRA (Team MYTHOS, IKIGAI 2026, problem ID IHNG6), but self-contained:
it has its own UI, its own storage, its own models, and no dependency on any other codebase.

---

## The scope boundary

This app captures audio **only** from `MediaRecorder.AudioSource.MIC`, while a call is on
speakerphone. It hears the far party the same way a person standing next to you would.

It does not touch `VOICE_CALL`, `VOICE_UPLINK`, or `VOICE_DOWNLINK`. Those are signature-only on
Android and unavailable to third-party apps. There is no code here that tries, no fallback that
tries, and no flag that turns it on. This is a design boundary, not a limitation waiting to be
lifted.

There is also **no `INTERNET` permission in the manifest**. Not "we don't upload" as a policy —
the app has no network capability at all, so audio, embeddings and scores physically cannot leave
the device.

---

## Current status

| Phase | State |
|---|---|
| 0 · Capture feasibility spike | **Run. The answer is no** on the tested handset — see [Phase 0 has been run](#phase-0-has-been-run-and-the-answer-is-no). |
| 1 · Permissions, foreground service, disclosure banner | Done |
| 2 · Enrolment + encrypted voiceprint storage | Done |
| 3 · On-device models | Done, but **not TFLite** — see [Models](#models). Shipped as ONNX with exact measured parity. |
| 4 · Shared inference pipeline | Done |
| 4b · Test Mode (file-based) | Done |
| 5 · Fusion + SAFE/SUSPICIOUS/CRITICAL alerting | Done |
| 6 · Graceful degradation + tests | Done — 59 JVM unit tests passing |
| 6b · Per-contact anti-spoofing baseline | Done — added after the detector was measured calling known-genuine audio synthetic. See [The anti-spoofing model was measured failing](#the-anti-spoofing-model-was-measured-failing-on-real-audio). |

### Phase 0 has been run, and the answer is no

Phase 0 asked whether `AudioRecord` on `MIC` still returns usable audio while `AudioManager` is in
`MODE_IN_CALL`. It was built as a real screen (**Capture diagnostics** on the home screen) rather
than a throwaway branch, so it could be run against a real call and produce a verdict.

**Result, on a LAVA LXX504 (Android 15, API 35), real cellular call on speakerphone: the app gets
nothing.** The microphone is held by the call. The moment the call ends, the same screen picks up
audio normally — so this is not a permission problem, a routing problem, or a bug in the capture
code. The platform is deliberately withholding call audio from a third-party recorder.

This is the expected behaviour, not a surprise. Android 10 onwards restricts concurrent capture,
and the APIs that *can* read call audio — `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK` — are
gated behind signature permissions available only to the system dialler. No permission, manifest
entry, or `targetSdk` change unlocks them for an app like this one, and this project does not try:
that boundary was a hard constraint from the start, not a limitation discovered late.

One handset is not a survey. Some OEM builds are reported to pass speakerphone audio through, and
the diagnostics screen exists so anyone can check theirs in about a minute. But the honest default
assumption is now that **live in-call capture does not work**, rather than that it might.

The app reports this rather than sitting on a dead level meter: when capture returns only silence
it checks `AudioManager.getMode()`, and if a call is in progress it says the handset does not pass
call audio to third-party apps and points at Test Mode, instead of blaming the privacy toggle.

**What still works:** everything else. **Test Mode is the demoable path and never depended on Phase
0.** Feed it a recording of the call — one made by the system dialler's own recorder, or by any
other device — and it runs the identical pipeline, models, thresholds and fusion rules that live
capture would have used.

---

## Build and install

Requirements: Android Studio (for its bundled JDK 21), Android SDK 35, a device on API 26+.

```bash
# Windows, from the project root
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat :app:assembleDebug
gradlew.bat :app:installDebug          # with a device attached

# unit tests (no device needed)
gradlew.bat :app:testDebugUnitTest

# on-device model checks (device needed)
gradlew.bat :app:connectedDebugAndroidTest
```

ONNX Runtime ships a native library per ABI, so the build is split by ABI. Install the one that
matches your device rather than the universal APK:

| APK | Size |
|---|---|
| `app-arm64-v8a-debug.apk` | 42 MB — almost certainly the one you want |
| `app-armeabi-v7a-debug.apk` | 37 MB — older 32-bit devices |
| `app-x86_64-debug.apk` | 45 MB — emulators |
| `app-universal-debug.apk` | 95 MB — all four, for when you do not want to think about it |

`local.properties` points at the SDK using **forward slashes** on purpose — `java.util.Properties`
treats a backslash as an escape character, and a Windows path written with backslashes silently
loses its separators.

## Models

Both models run fully on-device. Neither is trained here; both are published checkpoints converted
by `tools/convert_models.py`.

| | Speaker embedding | Anti-spoofing |
|---|---|---|
| Origin | Resemblyzer `VoiceEncoder` (GE2E) | AASIST (clovaai), ASVspoof 2019 LA |
| Input | 25 600 samples (1.6 s @ 16 kHz) | 64 600 samples (4.0375 s @ 16 kHz) |
| Output | 256-d L2-normalised embedding | 2 logits, `[spoof, bonafide]` |
| Produces | `voice_similarity` (cosine vs. voiceprint) | `synthetic_probability` = softmax[0] |
| Shipped as | ONNX float32, 6.4 MB | ONNX float32, 1.5 MB |

### These are ONNX, not TFLite — and not int8

Two deviations from the original plan. Both were forced by measurement, not preference.

**TFLite could not be produced.** The PyTorch → ONNX half is numerically exact for both models.
The ONNX → TensorFlow → TFLite half is not usable with either onnx2tf release available:

| Version | Failure |
|---|---|
| onnx2tf 2.6.8 | Writes `.tflite` files that then refuse to load. Speaker: `RESHAPE node 11, num_input_elements != num_output_elements (26000 != 1)`. Spoof: `RESHAPE node 1359, (1280 != 20)`. |
| onnx2tf 2.6.8, dynamic-range | Loads no better: `BATCH_MATMUL lhs float32 / rhs int8` type mismatch, node 0 failed to prepare. |
| onnx2tf 1.26.9 | Fails earlier, during conversion, on `/net/MaxPool` layout inference. |

Every one of those was found by **loading and running** the converted file. A `.tflite` that exists
but will not allocate its tensors is not a converted model — and for a while this project had files
on disk that looked like success. So both models ship as ONNX through `onnxruntime-android`.
Nothing else about the design changes: inference is entirely on-device and the app still holds no
`INTERNET` permission.

**int8 was rejected on measured grounds.** Dynamic int8 quantisation produced loadable models that
gave the wrong answers:

| Model | int8 parity vs PyTorch | Verdict |
|---|---|---|
| Speaker encoder | min cosine **0.336**, mean 0.561 (bar: ≥ 0.995) | Rejected — quantisation destroys the embedding |
| Anti-spoofing | max \|ΔP\| **0.189**, mean 0.029 (bar: ≤ 0.05) | Rejected — moves scores across the 0.50 alert threshold |

A 0.19 shift in `synthetic_probability` is not a rounding error; it is enough to flip a verdict.
The float32 models are 7.9 MB combined, which is a cheap price for scores that mean what they say.
`convert_models.py` still attempts int8 on every run and will ship it automatically if a future
checkpoint or runtime clears those bars.

### Measured conversion parity (what actually shipped)

| Model | Metric | Result |
|---|---|---|
| Speaker encoder | mean cosine, ONNX vs PyTorch, 55 partials | **1.000000** (min 1.000000) |
| Anti-spoofing | max \|ΔP\|, ONNX vs PyTorch, 18 windows | **4.8 × 10⁻⁷** |
| Mel front end | max relative error vs librosa | **9.2 × 10⁻⁷** |

### Building the models

```bash
py -3.12 -m venv tools/.venv
tools/.venv/Scripts/python.exe -m pip install torch --index-url https://download.pytorch.org/whl/cpu
tools/.venv/Scripts/python.exe -m pip install onnx onnxruntime librosa soundfile numpy
bash tools/fetch_checkpoints.sh
tools/.venv/Scripts/python.exe tools/convert_models.py
```

The script writes `app/src/main/assets/models/manifest.json` with the measured parity. **Every
number in that file comes from a run of the script.** If a stage fails, the manifest records the
failure and the app reports the models as missing rather than scoring anything.

### Two implementation notes worth knowing

**The mel front end is inside the exported graph.** `torch.stft` exports to an ONNX STFT op that
downstream tooling handles inconsistently, so `MelFrontend` computes the DFT as a fixed `conv1d`
instead — mathematically identical, lowers to ops every backend supports. Folding it into the graph
also means the mel arithmetic exists in exactly one place, validated against librosa, rather than
being reimplemented by hand in Kotlin where it could drift and quietly degrade every similarity
score. The `center=True` padding is a `torch.cat` rather than `F.pad` for the same reason: `F.pad`
exported to a Pad whose shape handling onnx2tf mis-lowered.

**The LSTM is exported fused, not unrolled.** An unrolled export duplicates the weights at every
one of the 160 timesteps and produced an 875 MB graph. It is not used.

## What has and has not been measured

Measured:

- **Conversion parity** — exported model vs. the original PyTorch checkpoint, on three LibriSpeech
  speakers. Exact for both. Numbers in `manifest.json`.
- **int8 quantisation quality** — measured, and rejected on the evidence. See above.
- **Mel front end vs. librosa** — max relative error ~1e-6.
- **Class ordering of the anti-spoofing head** — verified numerically against genuine speech, not
  assumed from a comment.
- **59 unit tests** covering fusion rules, ring-buffer wraparound, resampler anti-aliasing, WAV
  decoding, loudness normalisation, score latching, and the disclosure interlock.
- **Desk-vs-device agreement on real audio.** The same two clips scored on the desk in PyTorch and
  on the handset through the shipped ONNX models agree to four decimal places per window. See
  below.
- **On-device inference latency**, measured on a LAVA LXX504 (Android 15, API 35, arm64-v8a):
  **690 ms** for the full live path — speaker embedder plus anti-spoofing — against the 3 000 ms
  live hop, median of three runs after warm-up. The anti-spoofing model alone is 557 ms, so the
  embedder adds ~133 ms. That is 23 % of the budget; a handset roughly four times slower would
  still keep up. One device is not a range, and a cold first window costs more than a warm one.
- **The six on-device model checks pass on real hardware** (`gradlew :app:connectedDebugAndroidTest`):
  models load with the expected shapes, embeddings are unit-length and deterministic, different
  audio yields different embeddings, synthetic probability stays in [0, 1], and silence is reported
  as unmeasured rather than scored.

Not measured, and therefore not claimed anywhere in the app or these docs:

- Precision, recall, or false-positive rate for either model.
- How well the anti-spoofing model generalises to current cloning tools (ElevenLabs, RVC). AASIST
  was trained on ASVspoof 2019 LA; cloning has moved on since.
- Whether real speech under stress false-triggers as synthetic.
- End-to-end latency on anything but the one handset above, and nothing at all about latency while
  a call is actually in progress and the CPU is busier.

The thresholds in `FusionThresholds.PROVISIONAL` are **starting points for calibration, not
measured operating points**, and the UI says so wherever a score appears. Test Mode exposes raw
per-window scores as CSV precisely so they can be calibrated against real clips.

One data point that is worth knowing before you read a score: on genuine LibriSpeech recordings,
the PyTorch AASIST checkpoint returns a mean `synthetic_probability` of roughly 0.33 — not the
near-zero you might expect. LibriSpeech is out-of-domain for ASVspoof, so some elevation is
expected, but it means the default 0.50 alert threshold has less headroom than it looks. Calibrate
before drawing conclusions.

---

## The anti-spoofing model was measured failing on real audio

This is the most important measured result in the repo, and it is a negative one.

Two clips of the same real person, both confirmed genuine recordings, were run through the
pipeline. The speaker encoder handled them correctly — whole-clip cosine **0.9370**, comfortably a
match. The anti-spoofing model called them synthetic on essentially every window:

| Clip | median `synthetic_probability` (AASIST) | median (AASIST-L) |
|---|---|---|
| `voice1.mp3` | 0.9991 | 0.9988 |
| `voice2.mp3` | 0.9997 | 1.0000 |

Before the fix, the app reported **CRITICAL — possible cloned voice** on 26 of 27 windows of a real
person speaking. That is the worst failure this app can have: it tells a family their relative is a
computer-generated impostor.

Controls were run before concluding anything. Each was ruled out:

| Hypothesis | Control | Result |
|---|---|---|
| MP3 compression artifacts | genuine speech pushed through the identical 48k → 112 kbps → 16k chain | 0.0009 → 0.0010. Not it. |
| Clipping | census of near-full-scale samples and flat runs | 0.000% clipped, no flat runs. Not it. |
| Denoiser artifacts | additive noise at 40 / 30 / 20 dB SNR | 0.9991 → 0.9983. Not it. |
| A bad checkpoint | re-ran with AASIST-L | 0.9988 / 1.0000. Not it. |
| Recording level | scaled 10× down | 0.999 → 0.006, but a louder LibriSpeech clip scores 0.0009. Does not explain it. |

The clips are also *cleaner* than the LibriSpeech baseline (noise floors −57 / −60 dBFS vs
−47 / −52), so this is not a noise problem either. The conclusion the evidence supports is that the
ASVspoof-2019-trained checkpoint does not generalise to this recording domain, and that its output
on a given voice cannot be trusted without evidence that it works on that voice.

### What the app does about it

Enrolment now measures the detector against the one recording whose provenance is not in doubt —
the 30–60 s the contact just recorded themselves after consenting. The median across windows is
stored as that contact's `baselineSynthetic`, and it changes how their live scores are read:

| Baseline | State | Effect |
|---|---|---|
| ≥ 0.85 (no headroom left above it) | `UNRELIABLE` | The clone check is **dropped** for this contact and the UI says so. Identity matching still runs and is what the verdict rests on. |
| measured, with headroom | `USABLE` | The alert threshold becomes `max(0.50, baseline + 0.15)`. A voice whose real recordings read 0.40 needs 0.55; one that reads 0.05 is held to the ordinary 0.50. |
| never measured | `NO_BASELINE` | Ordinary absolute thresholds still apply — **CRITICAL still fires** — but the verdict carries a caveat saying the check is uncalibrated for this voice. |

`NO_BASELINE` deliberately does not suppress the alert. "Never measured" is a different claim from
"measured and useless", and suppressing on it would silently switch clone detection off for every
contact enrolled before baselines existed — the users least likely to notice.

Verified end-to-end twice — once on the desk against the PyTorch reference
(`tools/analyze_clips.py`), once on an actual handset through the shipped ONNX models and the
same code path Test Mode uses (`RealClipPipelineTest`, LAVA LXX504 / Android 15):

```
anti-spoofing baseline on known-genuine audio = 0.9991 -> UNRELIABLE
   0.0s  sim 0.8261  syn 0.9892  SAFE  MATCH_SPOOF_CHECK_UNRELIABLE
   ...
median similarity 0.8875, median synthetic 0.9995,
worst window SUSPICIOUS/BORDERLINE_SIMILARITY, median inference 762 ms
```

The two runs agree to four decimal places on every window — desk 0.8262/0.9892 against device
0.8261/0.9892 — so the ONNX exports on the phone and the PyTorch checkpoints on the desk are
computing the same thing on real audio, not just on the three LibriSpeech clips used for the
conversion gate.

`RealClipPipelineTest` also asserts the negative: run the same window with `baselineSynthetic =
null` and it still returns CRITICAL/CLONE_SIGNATURE. Without that, the suite would keep passing if
the clone alert quietly stopped working altogether.

This is mitigation, not a fix. The right fix is a checkpoint that generalises, and until one is in
place the honest description of this app is **a working speaker-verification system with an
anti-spoofing stage that is only trustworthy on voices where it has been shown to work.**

---

## How to test it

Steps 1–4 need no live call, no second device, and no microphone permission beyond enrolment.

1. **Enrol a voice.** Home → *Enrol new*. Consent screen first, then ~30–60 s of prompted speech
   with a live level meter. The audio is embedded and then zero-filled in memory; nothing is
   written to disk.
2. **Run a genuine clip through Test Mode** with that contact selected. Expect high similarity.
   Read the synthetic probability against what the score panel says about *this* contact's
   baseline — if the panel reports the clone check as off or uncalibrated for them, the synthetic
   number is not evidence of anything and the verdict rests on voice matching alone.
3. **Run a cloned clip of the same voice** (ElevenLabs, RVC). The previous result stays on screen
   for side-by-side comparison. Expect similarity to stay high while synthetic probability moves —
   **that divergence is the whole demo**, not two different similarity scores.
4. **Copy the per-window CSV** and calibrate the thresholds against what you actually measured.

Without a device, the same pipeline runs on the desk against the PyTorch reference, which matches
the shipped ONNX exports exactly:

```bash
tools/.venv/Scripts/python.exe tools/analyze_clips.py clip.mp3 --enrol enrolment.mp3
```

It prints per-window similarity, synthetic probability, level and reason, the measured baseline for
the enrolment clip, and the effective alert threshold that baseline implies — the same numbers Test
Mode shows, using the same fusion rules.

To run the same clips through the real app on a real phone without touching the file picker, push
them into the app's external files directory and run `RealClipPipelineTest`:

```bash
adb install -r -g app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push voice1.mp3 /sdcard/Android/data/com.mythos.vcd.debug/files/voice1.mp3
adb push voice2.mp3 /sdcard/Android/data/com.mythos.vcd.debug/files/voice2.mp3
adb shell am instrument -w -e class com.mythos.vcd.RealClipPipelineTest   com.mythos.vcd.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s RealClipPipelineTest:I
```

The test skips itself if the clips are absent, and the clips are deliberately not committed — they
are recordings of a real person.

Stretch test — the one that needs hardware:

5. Make a real call, put it on speaker, open **Capture diagnostics**, and check whether the level
   meter moves when the other party speaks and you stay silent. Then try **Start Live
   Verification**. If the handset mutes third-party mic capture during calls, the diagnostics
   screen will say so plainly instead of the app producing scores from silence.

---

## Privacy mechanics

Not policy statements — the mechanisms that enforce them.

| Promise | Mechanism |
|---|---|
| Nothing is uploaded | No `INTERNET` permission in the manifest |
| No silent capture | `DisclosureGate` — the banner composable opens the gate on draw; `LiveVerificationService` refuses to open the mic while it is shut, so removing the banner breaks capture rather than hiding it |
| Raw audio is not persisted | `AudioRingBuffer` is memory-only and `zeroize()`d on stop; enrolment audio is `Arrays.fill(0f)`-ed immediately after embedding; Test Mode scrubs the decoded clip after scoring |
| Voiceprints are encrypted at rest | AES-256-GCM under a non-exportable Android Keystore key; GCM so tampering fails loudly instead of yielding a garbage embedding |
| Backups cannot leak voiceprints | Cloud backup and device transfer both excluded — the Keystore key is device-bound, so a restored ciphertext would be undecryptable anyway |
| Deletion is real | Per-contact delete, plus `destroyKey()` which makes every stored voiceprint permanently unrecoverable |
| No score from audio we are not sure about | Every failure path returns `Level.INDETERMINATE`; a mid-call capture failure stops the session rather than continuing to score |

---

## Architecture

```
audio/          AudioConstants, AudioWindow, AudioRingBuffer, MicCapture, AudioFileDecoder,
                WindowSlicer, SincResampler, WavIo, AudioNormalize, Pcm
ml/             SpeakerEmbedder / SpoofDetector interfaces, ONNX Runtime implementations,
                ModelRuntime (lazy load, failure as a first-class state), Vec
pipeline/       VerificationPipeline (the one shared inference path), Fusion, Verdict,
                SessionScores
data/           Room contacts DB, ContactRepository, VoiceprintCrypto
service/        LiveVerificationService (foregroundServiceType=microphone), DisclosureGate,
                LiveSession, Notifications
ui/             home · enroll · live · testmode · spike · permission · components · theme
tools/          vcd_models.py (PyTorch reference), convert_models.py (conversion + parity)
```

The single most important structural rule: **live capture and Test Mode call the same
`VerificationPipeline.analyze`, on windows of the same width.** The only difference between them is
where the samples came from, and that travels with the window as `AudioWindow.Provenance`. A Test
Mode result therefore tells you something about the live path, rather than about a parallel
implementation that happens to look similar.

---

## Known limitations

- **Live in-call capture does not work** on the one handset tested, and probably will not on most.
  See Phase 0 above. The app degrades to Test Mode, which is a real path, not a consolation prize —
  but it means the "screen a call as it happens" story is unproven on real hardware.
- **No speaker separation.** On speakerphone the mic hears both parties. Nothing in the pipeline
  separates them, so windows containing the user's own speech are scored against the contact's
  voiceprint and come back as a mismatch. An own-voice energy gate or diarisation would fix it;
  neither is built.
- **The app does not detect calls.** By design — no `READ_PHONE_STATE`, no `InCallService`. The
  user opens the app and presses start.
- **Resemblyzer's VAD is not reproduced.** The reference pipeline trims silence with `webrtcvad`
  before embedding; this app uses an RMS gate per window instead. Loudness normalisation *is*
  reproduced exactly (`AudioNormalize` ↔ `vcd_models.normalize_volume`).
- **Anti-spoofing models age — and this one was measured failing.** AASIST is ASVspoof 2019-era and
  returned ~0.999 on known-genuine recordings of a real person. The per-contact baseline stops that
  becoming a false CRITICAL, but it does so by switching the clone check off for those voices. See
  [The anti-spoofing model was measured failing](#the-anti-spoofing-model-was-measured-failing-on-real-audio).
- **A contact with an `UNRELIABLE` baseline gets identity checking only.** Their verdict says so
  explicitly, but it means the app is doing half its job for that person.
- **Models are float32, not int8.** Measured and rejected — see Models above. Latency has now been
  measured on hardware (690 ms of a 3 000 ms hop) but on exactly one handset, and not during a
  live call.
- **The APK is large** (42 MB for arm64) because ONNX Runtime's native library is ~17 MB. Nothing
  here is tuned for size.
- **Voiceprints are tied to a model build.** `ContactEntity.modelId` records which encoder produced
  each print; prints from a different build are marked stale and refused rather than compared,
  because a cosine between embeddings from two different encoders is a meaningless number that
  would still render as a confident percentage.
