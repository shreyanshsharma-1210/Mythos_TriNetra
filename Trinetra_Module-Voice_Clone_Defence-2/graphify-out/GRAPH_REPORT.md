# Graph Report - Trinetra  (2026-08-21)

## Corpus Check
- Corpus is ~33,681 words - fits in a single context window. You may not need a graph.

## Summary
- 587 nodes · 1009 edges · 74 communities (22 shown, 52 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 9 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Voice Similarity Fusion
- Contact Management
- Audio Processing
- Model Manifest
- Microphone Capture
- Live Verification
- Session Scoring
- Enrollment UI
- Speaker Encoding
- Model Conversion
- Main Activity
- Audio Window
- TFLite Embedding
- Embedding Models
- Audio I/O
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 50
- Community 51
- Community 52
- Community 53
- Community 54
- Community 55
- Community 56
- Community 57
- Community 58
- Community 59
- Community 60
- Community 61
- Community 62
- Community 63
- Community 64
- Community 65
- Community 66
- Community 67
- Community 68
- Community 69
- Community 70

## God Nodes (most connected - your core abstractions)
1. `MicCapture` - 26 edges
2. `Level` - 22 edges
3. `EnrollViewModel` - 22 edges
4. `VcdApp` - 17 edges
5. `AudioWindow` - 17 edges
6. `SessionScores` - 16 edges
7. `TestModeViewModel` - 16 edges
8. `ModelRuntime` - 15 edges
9. `Reason` - 15 edges
10. `LiveVerificationScreen()` - 15 edges

## Surprising Connections (you probably didn't know these)
- `PermissionScreen()` --calls--> `rememberMicPermissionState()`  [INFERRED]
  VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/permission/PermissionScreen.kt → VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/permission/MicPermission.kt
- `VcdNavHost()` --calls--> `EnrollScreen()`  [EXTRACTED]
  VoiceCloneDefense/app/src/main/java/com/mythos/vcd/MainActivity.kt → VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/enroll/EnrollScreen.kt
- `VcdNavHost()` --calls--> `LiveVerificationScreen()`  [EXTRACTED]
  VoiceCloneDefense/app/src/main/java/com/mythos/vcd/MainActivity.kt → VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/live/LiveVerificationScreen.kt
- `VcdNavHost()` --calls--> `CaptureSpikeScreen()`  [EXTRACTED]
  VoiceCloneDefense/app/src/main/java/com/mythos/vcd/MainActivity.kt → VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/spike/CaptureSpikeScreen.kt
- `VcdNavHost()` --calls--> `TestModeScreen()`  [EXTRACTED]
  VoiceCloneDefense/app/src/main/java/com/mythos/vcd/MainActivity.kt → VoiceCloneDefense/app/src/main/java/com/mythos/vcd/ui/testmode/TestModeScreen.kt

## Import Cycles
- None detected.

## Communities (74 total, 52 thin omitted)

### Community 0 - "Voice Similarity Fusion"
Cohesion: 0.09
Nodes (36): Color, Fusion, FusionThresholds, Level, CRITICAL, INDETERMINATE, SAFE, SUSPICIOUS (+28 more)

### Community 1 - "Contact Management"
Cohesion: 0.07
Nodes (18): Application, Notification, RoomDatabase, SecretKey, ContactRepository, EnrolledContact, FloatArray, Flow (+10 more)

### Community 2 - "Audio Processing"
Cohesion: 0.08
Nodes (13): AudioNormalize, FloatArray, AudioRingBuffer, FloatArray, FloatArray, SincResampler, AudioNormalizeTest, AudioRingBufferTest (+5 more)

### Community 3 - "Model Manifest"
Cohesion: 0.08
Nodes (17): FloatArray, OnDeviceModelTest, AudioWindow, Provenance, FILE, LIVE_MIC, StateFlow, ModelManifest (+9 more)

### Community 4 - "Microphone Capture"
Cohesion: 0.09
Nodes (23): MediaRecorder, AudioRecord, AudioRecordingConfiguration, HandlerThread, ShortArray, Failure, DEVICE_BUSY, PERMISSION_DENIED (+15 more)

### Community 5 - "Live Verification"
Cohesion: 0.09
Nodes (23): Intent, Job, LifecycleService, converted_at_utc, detail, spoof, model_id, notes (+15 more)

### Community 6 - "Session Scoring"
Cohesion: 0.09
Nodes (13): SessionScores, Reason, BORDERLINE_SIMILARITY, CLONE_SIGNATURE, MATCH_AUTHENTIC, NO_SPEECH, NO_VOICEPRINT_SELECTED, NOT_CLAIMED_CONTACT (+5 more)

### Community 7 - "Enrollment UI"
Cohesion: 0.11
Nodes (20): ConsentCheck(), ConsentStep(), DetailsStep(), DoneStep(), EnrollScreen(), FailedStep(), ProcessingStep(), RecordingStep() (+12 more)

### Community 8 - "Speaker Encoding"
Cohesion: 0.10
Nodes (20): embed_utterance(), MelFrontend, normalize_volume(), ndarray, Path, Tensor, PyTorch reference models for Voice Clone Defense. These are the definitions…, Resemblyzer's VoiceEncoder with the mel front end attached. in : [B,… (+12 more)

### Community 9 - "Model Conversion"
Cohesion: 0.18
Nodes (20): Module, build_speaker(), convert_speaker(), convert_spoof(), export_onnx(), load_calibration_clips(), log(), main() (+12 more)

### Community 10 - "Main Activity"
Cohesion: 0.16
Nodes (16): Bundle, ComponentActivity, MainActivity, Routes, VcdNavHost(), DisclosureBanner(), DisclosureBannerPreviewOnly(), Modifier (+8 more)

### Community 11 - "Audio Window"
Cohesion: 0.14
Nodes (10): AudioConstants, FloatArray, WindowSlicer, Context, StateFlow, Uri, ViewModel, Run (+2 more)

### Community 12 - "TFLite Embedding"
Cohesion: 0.20
Nodes (12): ByteBuffer, Interpreter, MappedByteBuffer, SpoofDetector, RuntimeException, ModelUnavailableException, interpreterOptions(), Context (+4 more)

### Community 13 - "Embedding Models"
Cohesion: 0.17
Nodes (8): Closeable, FloatArray, SpeakerEmbedder, SpoofDetector, ByteArray, FloatArray, Vec, Closeable

### Community 14 - "Audio I/O"
Cohesion: 0.22
Nodes (4): ByteArray, FloatArray, Pcm, WavIo

### Community 15 - "Community 15"
Cohesion: 0.25
Nodes (9): Exception, MediaExtractor, MediaFormat, AudioFileDecoder, Decoded, DecodeException, ByteArray, Context (+1 more)

### Community 16 - "Community 16"
Cohesion: 0.13
Nodes (3): DisclosureGate, StateFlow, DisclosureGateTest

### Community 18 - "Community 18"
Cohesion: 0.47
Nodes (3): StateFlow, LiveSession, State

### Community 19 - "Community 19"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **41 isolated node(s):** `model_id`, `speaker_encoder`, `spoof_detector`, `quantization`, `converted_at_utc` (+36 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **52 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VcdApp` connect `Voice Similarity Fusion` to `Contact Management`, `Model Manifest`, `Live Verification`, `Enrollment UI`, `Main Activity`, `Audio Window`?**
  _High betweenness centrality (0.121) - this node is a cross-community bridge._
- **Why does `MicCapture` connect `Microphone Capture` to `Voice Similarity Fusion`, `Community 18`, `Live Verification`, `Enrollment UI`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Why does `AudioConstants` connect `Audio Window` to `Audio Processing`, `Model Manifest`, `Microphone Capture`, `Live Verification`, `Enrollment UI`, `Embedding Models`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **What connects `model_id`, `speaker_encoder`, `spoof_detector` to the rest of the system?**
  _41 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Voice Similarity Fusion` be split into smaller, more focused modules?**
  _Cohesion score 0.09098039215686274 - nodes in this community are weakly interconnected._
- **Should `Contact Management` be split into smaller, more focused modules?**
  _Cohesion score 0.06567992599444958 - nodes in this community are weakly interconnected._
- **Should `Audio Processing` be split into smaller, more focused modules?**
  _Cohesion score 0.07770582793709528 - nodes in this community are weakly interconnected._