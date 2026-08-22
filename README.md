# TriNetra

**On-device scam defence for Android.** TriNetra watches the signals a phone already receives — who is
calling, what arrives by SMS, what other apps put in the notification shade, and (on its own VoIP
calls) the voice on the line — and fuses them into one risk score with an explanation attached.

The name is the project's thesis: three eyes on the same conversation. Signal (who is calling),
semantics (what they are saying), and voice (whether the speaker is who they claim to be). No single
one of those is reliable alone; a scam that defeats all three at once is much harder to build.

> **Status: research prototype / demo build.** Detection thresholds ship uncalibrated on purpose, the
> Digital Arrest walkthrough is a deterministic simulation, and the app makes no accuracy claims. See
> [Honesty about what is real](#honesty-about-what-is-real) — it is not an appendix, it is the part
> that matters most if you are evaluating this.

---

## Table of contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Modules](#modules)
- [Threat coverage](#threat-coverage)
- [Build and run](#build-and-run)
- [Permissions](#permissions)
- [Demo triggers](#demo-triggers)
- [Testing](#testing)
- [Honesty about what is real](#honesty-about-what-is-real)
- [Known gaps](#known-gaps)
- [Project layout](#project-layout)

---

## What it does

| Surface | What TriNetra does |
|---|---|
| **Incoming cellular call** | Screens via `CallScreeningService`, resolves caller identity and reputation, scores risk, and raises a floating overlay that grows with severity — pill, then risk card, then full-screen intervention. Can block on policy. |
| **SMS** | Parses every message for ~70 scam signal types (OTP requests, KYC/SIM threats, parcel and challan scams, payment links, urgency and authority language), and extracts callback numbers and URLs. |
| **Notifications** | Reads the notification shade through `NotificationListenerService`, so messages from WhatsApp, banking apps and the rest are scored on the same footing as SMS. |
| **Correlation** | Notices that a "your SIM will be blocked" SMS and a call from an unknown number ninety seconds later are one attack, not two events. |
| **VoIP call (TriNetra-to-TriNetra)** | Runs speaker verification and anti-spoofing on the remote audio, on-device, plus live Hindi/English transcription and LLM scam-intent analysis. |
| **Escalation** | Vibrates, raises a full-screen alert, and can SMS a trusted contact when risk crosses threshold. |

Everything except the LLM semantic layer runs on the device. Call audio never leaves the phone.

---

## Architecture

Events flow one way. Each stage adds evidence; none of them discards it.

```
+-----------------------------------------------------------------+
|  SENSORS                                                        |
|  CallScreeningService . PhoneStateReceiver . OutgoingCall       |
|  SmsReceiver . NotificationListener . PackageEventReceiver      |
+------------------------------+----------------------------------+
                               |  SecurityEvent
+------------------------------v----------------------------------+
|  NORMALISE & CORRELATE                                          |
|  EventNormalizer -> InteractionManager -> Interaction           |
|  CallerIdentityResolver (contacts -> external -> reputation)    |
+------------------------------+----------------------------------+
                               |
+------------------------------v----------------------------------+
|  INTELLIGENCE                                                   |
|  L1  SmsIntentClassifier   - keyword & pattern signals          |
|  L2  UrlAnalyzer / PhoneNumberExtractor                         |
|  L3  GroqSemanticAnalyzer  - LLM intent, tactics, category      |
|  L4  AttackContextEngine   - cross-event correlation            |
|  L5  EvidenceFusionEngine  - weighted factors                   |
|  L6  RiskEngine            - score 0-100 + RiskLevel            |
+------------------------------+----------------------------------+
                               |  RiskAssessment
+------------------------------v----------------------------------+
|  DECIDE & PRESENT                                               |
|  SecurityIncidentEngine . ProtectionPolicyEngine                |
|  ProtectionController -> overlay (pill -> card -> full screen)  |
|  EmergencyAlarmManager . FamilyAlertService                     |
+-----------------------------------------------------------------+

     +--------------- parallel, VoIP calls only -------------------+
     |  VCD: MicCapture/RemoteAudioAdapter -> WindowSlicer         |
     |       -> SpeakerEmbedder + SpoofDetector (ONNX)             |
     |       -> Fusion -> SessionScores (stabilised verdict)       |
     |  WebRtcIntelligenceCoordinator: + Vosk STT + Groq           |
     +-------------------------------------------------------------+
```

**Persistence** is Room (`TrustMeshDatabase`): security events, interactions, risk assessments and
factors, incidents, timeline entries, trusted callers, protection policies. Voiceprints live in a
separate encrypted store (`vcd/data/crypto/VoiceprintCrypto`).

**UI** is Jetpack Compose throughout, plus a `ComposeView` hosted in a `TYPE_APPLICATION_OVERLAY`
window for the in-call overlay — with its own `LifecycleOwner` and `SavedStateRegistryOwner`, since
there is no Activity behind it.

---

## Modules

### 1. Sensor layer — `sensors/`

| Component | Role |
|---|---|
| `TrustMeshCallScreeningService` | Main entry point for incoming cellular calls. Always calls `respondToCall()` exactly once and fails open — a slow database must never block the dialler. Policy evaluation is bounded to 2 s. |
| `TrustMeshPhoneStateReceiver` | RINGING → OFFHOOK → IDLE; drives overlay transitions (incoming, active pill, call summary). |
| `OutgoingCallReceiver` | Raises the same protection surface for calls the user places. |
| `TrustMeshSmsReceiver` | Direct `SMS_RECEIVED` broadcast at priority 999. Handles demo control codes first, then routes the message into the event pipeline. |
| `TrustMeshNotificationListenerService` | Second path into the pipeline for anything in the notification shade. |
| `PackageEventReceiver` | Flags app installs mid-call — the remote-access scam pattern (`APK_INSTALL_REQUEST`). |

Phone numbers are never logged in full; only a masked suffix reaches logcat.

### 2. Interaction and event core — `interaction/`, `core/events/`, `data/`

`SecurityEvent` is the raw normalised input. `Interaction` is the durable unit a user actually sees:
one caller or one conversation, carrying evidence, timeline, resolved identity, reputation, risk
assessment and Groq response. `InteractionManager` is the single mutable store, exposed as a
`StateFlow` the whole UI observes, backed by Room through `RoomEventRepository`.

`CallerIdentityResolver` is composite: local contacts first, then an external provider, then a
reputation cache. Known-business identity *reduces* risk (weight `-10`), which matters — a risk engine
that can only add is a risk engine that eventually flags everything.

### 3. Intelligence and risk — `core/intelligence/`

**`context/SmsIntentClassifier`** — the deterministic layer. ~70 `ScamSignalType` values across 11
categories (`IDENTITY`, `FINANCIAL`, `AUTHENTICATION`, `TELECOM`, `UTILITY`, `DELIVERY`, `GOVERNMENT`,
`REMOTE_ACCESS`, `URGENCY`, `SOCIAL_ENGINEERING`, `LINK`), each with a confidence and a weight.
`UrlAnalyzer` scores shorteners, raw-IP hosts and unexpected domains; `PhoneNumberExtractor` pulls
callback numbers out of message bodies and flags mismatches against the sender.

**`groq/GroqSemanticAnalyzer`** — the semantic layer, calling Groq (`llama-3.3-70b-versatile`) for
scam category, psychological triggers, key suspicious phrases and a plain-language rationale. This is
the **only** part of the system that sends anything off-device. It runs asynchronously, and every
failure path degrades to the deterministic layers rather than blocking.

**`context/AttackContextEngine`** — correlation across a 5-minute window. Produces a `ContextType`
(`OTP_THEFT`, `KYC_SCAM`, `TELECOM_IMPERSONATION`, `REMOTE_ACCESS_SCAM`, `CHALLAN_SCAM`,
`ACCOUNT_TAKEOVER`, and others) plus an `InferredIntent`, worth +20 to the score on its own.

**`risk/RiskEngine`** — fuses `RiskFactor`s into 0-100 and a `RiskLevel`
(LOW <25, ELEVATED 25-49, HIGH 50-74, CRITICAL 75+). Memoized per interaction by input hash; the cache
is cleared at the start of every call so no caller inherits the previous one's score.

### 4. Protection and overlay — `core/protection/`, `ui/screens/protection/`

`ProtectionPolicyEngine` maps a risk assessment (plus any active incident and the trusted-caller list)
to a `ProtectionAction`: `MONITOR_ONLY`, `SHOW_COMPACT_WARNING`, `SHOW_RISK_CARD`, `SHOW_BOTTOM_SHEET`,
`SHOW_SECURITY_INTERVENTION`, `ASK_USER`, `BLOCK_CALL`. Modes: `STANDARD`, `STRICT`, `CUSTOM`.

`ProtectionController` owns the overlay window — creation, drag, resize per severity, teardown, and the
call-summary card shown when the call ends. The window is `WRAP_CONTENT` and non-modal at low risk (a
draggable pill), becoming `MATCH_PARENT` and touch-modal at HIGH or CRITICAL.

The card shows caller identity and reputation, risk score and level, the live multi-layer risk graph,
the semantic analysis block, the security signals that fired, and recommended actions.

### 5. Voice Clone Defence (VCD) — `vcd/`

A self-contained module — a working voice-verified VoIP phone, not a demo screen. This is where the
real ML lives.

```
vcd/
  audio/       MicCapture . AudioFileDecoder . SincResampler . WindowSlicer
               AudioRingBuffer . AudioNormalize . ChannelSim . WavIo . Pcm
  ml/          SpeakerEmbedder + SpoofDetector interfaces . OrtModels (ONNX Runtime)
  pipeline/    VerificationPipeline . Voiceprint . Fusion . Verdict . SessionScores
  voip/        WebRtcEngine . CallSession . CallManager . SignalingServer/Client
               PeerDiscovery (mDNS) . RemoteAudioAdapter . Ringer
  service/     VoipCallService . LiveVerificationService . AvailabilityService
  data/        Room contacts + call history . VoiceprintCrypto (encrypted at rest)
  ui/          enroll . call . live . testmode . spike . shell
```

**Audio contract.** Every path — microphone, file, WebRTC remote track — normalises to 16 kHz mono
float. The analysis window is **64,600 samples (4.0375 s)**, which is not a round number on purpose: it
is exactly the input width the AASIST anti-spoofing checkpoint was trained on, so the model never sees
a padded or truncated frame. Live capture hops every 3 s; speaker embedding uses 1.6 s partials at 50 %
overlap (Resemblyzer convention), 256-dim output.

**Two models, deliberately narrow.** Both take raw waveform rather than features — the mel front end is
folded into the exported graph, so that arithmetic exists once, in Python, validated against the
reference model, rather than in a second hand-written Kotlin copy that could silently drift and quietly
degrade every score in the app.

- `speaker_encoder.onnx` — "is this the person I enrolled?" (cosine similarity)
- `spoof_detector.onnx` — "is this waveform synthetic?" (independent of who is speaking)

**The clone signature.** Neither score alone is the finding. `Reason.CLONE_SIGNATURE` fires on the
*combination*: high similarity **and** high synthetic probability — it sounds like them, and it looks
generated. That specific pair is the whole reason the module exists.

**Two failure modes it explicitly handles, both found by measurement rather than hypothesised:**

1. **Per-contact spoof baseline.** The ASVspoof-2019-trained AASIST checkpoint returns ~0.999 on
   genuine recordings from some sources. Without calibration those contacts would be flagged as clones
   on every call. Enrolment measures a baseline, and a live score must clear it by
   `syntheticBaselineMargin` (0.15). If the baseline already sits at the ceiling, `SpoofCheck` becomes
   `UNRELIABLE`, the UI says the clone check is off for this voice, **and identity is still reported** —
   how confident the app is in a signal is a different fact from what the signal said.
2. **Verdict stabilisation.** Scores near a threshold cross it constantly through ordinary variation, so
   a per-window verdict flips SAFE/SUSPICIOUS/CRITICAL while the same person talks. `SessionScores`
   decides on the **median of a 5-window buffer**, requires a new level to hold before showing it, and
   makes de-escalation slower than escalation (4 windows down, 2 up). A status that changes every three
   seconds trains the user to ignore it.

`Level.INDETERMINATE` is a first-class state, not a fallback: no speech, no voiceprint, or a model that
failed to run shows as *unmeasured*, never as SAFE.

**The codeword challenge.** The one check a perfect clone cannot pass — a shared secret agreed in
advance, revealed on tap so a shoulder-surfer or a screen recording does not capture it. It is the
module's most reliable signal, and the only one that does not depend on a model.

**Test Mode** runs the pipeline over audio files and exposes raw per-window scores, so thresholds can be
tuned against real clips instead of guessed.

### 6. Live call intelligence — `callaudio/webrtc/`

Runs for the duration of a connected WebRTC call, merging three streams into one state:

- **VCD clone scores** from `CallManager`, suppressed entirely when no voice is enrolled — "cloned" and
  "same person" are meaningless with nothing to compare against.
- **`WebRtcSttBridge`** — Vosk on the remote PCM, Hindi and English, fully offline. Partial results
  update the live line only; finals commit to the rolling transcript.
- **`GroqLiveAnalyzer`** — batches ~10 s of transcript and runs scam-intent analysis on it.

Fused 40 % VCD / 60 % semantic into an `AlertLevel` (CLEAR, MONITORING, ELEVATED, CRITICAL). The overlay
auto-expands at ELEVATED.

### 7. Digital Arrest — `core/digitalarrest/`

A guided walkthrough of the digital-arrest scam: a caller impersonating law enforcement who keeps the
victim on an unbroken video call, claims an arrest warrant, and extracts money under threat of immediate
detention.

`DigitalArrestEngine` evaluates a fixed rule set — authority impersonation, arrest threat, coercive
communication, unverified identity, financial pressure — and produces a `DigitalArrestIncident` with a
case ID (`TRN-DA-YYYYMMDD-XXXXXX`), a timeline, SHA-256-hashed evidence entries including a screenshot,
and a threat assessment. `DigitalArrestPdfGenerator` renders an incident report; `FamilyAlertService`
notifies a trusted contact.

> **Deterministic simulation.** Scoring is fixed, not LLM-derived, for demo reliability. The caller is
> fictional ("Inspector Rahul Sharma", badge CCIU-47291) and every generated document is labelled
> SIMULATION / TRINETRA ASSESSMENT. It is not a law-enforcement record.

### 8. Emergency and family alert — `core/alert/`

`EmergencyAlarmManager` plays a looping alarm at 85 % of max alarm volume with a continuous vibration
pattern, using `prepareAsync()` so it is safe to start from a `BroadcastReceiver`, and falls back through
four ringtone URIs for OEM quirks (MIUI in particular).

`FamilyAlertService` sends an SMS to configured trusted contacts through the TextBee gateway when risk
crosses `HIGH_RISK_THRESHOLD` (50). `EmergencyAlertActivity` and `EmergencyAlertOverlayManager` wake the
screen and display over the lock screen.

---

## Threat coverage

### OTP scams

The most direct attack: get the victim to read out a one-time password. Detection is layered.

- **Signal** — `OTP`, `VERIFICATION_CODE`, `LOGIN_CODE`, `SECURITY_CODE`, `PASSWORD_RESET`,
  `ACCOUNT_VERIFICATION`. The dangerous one is `SHARE_OTP` (weight 25): a message that *asks for* an OTP
  rather than delivering one.
- **Correlation** — an OTP arriving within minutes of a call from an unknown number is
  `ContextType.OTP_THEFT` / `InferredIntent.POSSIBLE_OTP_THEFT`, worth +20 on its own. This is the
  pattern that matters: the OTP itself is legitimate; the call asking for it is the attack.
- **Semantic** — Groq classifies `OTP_THEFT` from conversational text with no keyword present.
- **Response** — `IncidentType.OTP_THEFT`. The overlay's advice is explicit about never sharing the code,
  and it is repeated on the in-call surface, where the pressure is actually being applied.

### SIM swap, SIM block and KYC scams

The pretext family: your SIM will be deactivated, your KYC has expired, TRAI has flagged your number. The
goal is either a port-out — a SIM swap, which hands the attacker every OTP you will ever receive — or a
fee paid in panic.

- **Signal** — `SIM_BLOCK` (weight 20), `KYC_EXPIRY` (15), `TRAI_IMPERSONATION` (15), `DOT_IMPERSONATION`,
  `MOBILE_DISCONNECTION`, `NUMBER_SUSPENSION`, all under `ScamSignalCategory.TELECOM`.
- **Correlation** — `ContextType.TELECOM_IMPERSONATION` and `ContextType.KYC_SCAM`, with
  `InferredIntent.POSSIBLE_TELECOM_IMPERSONATION` / `POSSIBLE_KYC_SCAM`.
- **Escalation** — these pair with `DISCONNECTION_THREAT` and `IMMEDIATE_ACTION` urgency signals and a
  `CALLBACK_NUMBER` in the message body. A callback number that does not match the sender is its own risk
  factor (`CALLBACK_NUMBER_MISMATCH`).

> **Scope note.** TriNetra detects SIM-swap *social engineering* — the messages and calls that set it up.
> It does **not** detect an actual SIM clone or a completed swap at the network level. That needs
> carrier-side signals (IMSI change, `SIM_STATE_CHANGED` correlation, port-out notifications) which this
> build does not read. See [Known gaps](#known-gaps).

### Digital arrest

See [module 7](#7-digital-arrest--coredigitalarrest). Signal coverage in the general pipeline:
`POLICE_IMPERSONATION`, `GOVERNMENT_IMPERSONATION`, `COURT_NOTICE`, `LEGAL_THREAT`, `TAX_NOTICE`,
`ECHALLAN`, feeding `ContextType.GOVERNMENT_IMPERSONATION` and `CHALLAN_SCAM`. The dedicated module
handles the full-workflow scenario with evidence capture and reporting.

### Voice cloning

A few seconds of someone's voice is enough to clone it convincingly. The attack is a call from a number
you do not recognise, in a voice you do.

TriNetra's answer is the [VCD module](#5-voice-clone-defence-vcd--vcd): enrol a voiceprint from consented
audio, then on every VoIP call from that contact run speaker verification and anti-spoofing together and
alert on the *combination* — sounds like them, looks generated. Plus the codeword challenge, which no
model can be wrong about.

On cellular calls TriNetra cannot access call audio — Android grants no such API to third-party apps — so
voice verification is available only on the VoIP path. On the cellular path the defence is everything
else: caller identity, scam-signal classification, cross-event correlation and the codeword.

---

## Build and run

**Requirements:** JDK 17, Android SDK 35, and a physical device on **Android 10 (API 29)** or newer. The
emulator will not do — this app is telephony, microphone and overlay permissions end to end.

```bash
git clone <repo> && cd Mythos_TriNetra
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

### Configuration

Create `local.properties` in the project root (it is gitignored):

```properties
sdk.dir=/path/to/Android/sdk

# Groq - semantic scam analysis. Without it, the deterministic layers still work.
groq.api.key=gsk_xxxxxxxxxxxxxxxxxxxx

# TextBee - outbound SMS gateway for family alerts.
textbee.api.key=txb_xxxxxxxxxxxxxxxxxxxx
textbee.device.id=xxxxxxxxxxxxxxxxxxxxxxxx
textbee.sim.slot=0

# Trusted contacts notified on a high-risk event. Comma-separated.
family.alert.numbers=+91XXXXXXXXXX,+91XXXXXXXXXX
```

Each value can also come from the environment (`GROQ_API_KEY`, `TEXTBEE_API_KEY`, `TEXTBEE_DEVICE_ID`,
`TEXTBEE_SIM_SLOT`, `FAMILY_ALERT_NUMBERS`), which is what CI should use. They are surfaced through
`BuildConfig`.

> **Read this before you build.** `app/build.gradle.kts` contains **hardcoded fallback values** for the
> TextBee API key, the TextBee device ID, and two real-looking phone numbers, used when nothing else is
> configured. Those are committed credentials. Rotate the key, replace the numbers, and change the
> fallbacks to empty strings before this repository goes anywhere public.

### First run

1. Grant the runtime permissions the app asks for (contacts, phone, SMS, microphone, notifications).
2. Grant **Display over other apps** — without it the call overlay silently never appears.
3. Grant **Notification access** in Settings.
4. Optionally set TriNetra as the call-screening app so `CallScreeningService` binds.
5. For VCD: open the Voice tab, enrol a contact (~60 s of consented speech), and set a codeword.
6. VoIP calls need both devices on the same local network — peer discovery is mDNS.

---

## Permissions

| Permission | Why |
|---|---|
| `READ_CONTACTS` | Resolve caller identity locally before anything external is consulted. |
| `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `READ_CALL_LOG` | Call lifecycle and history. |
| `RECEIVE_SMS`, `READ_SMS` | The SMS sensor path. |
| `SYSTEM_ALERT_WINDOW` | The in-call overlay. |
| `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` | Incoming-call and emergency alerts that reach the user with the app closed. |
| `RECORD_AUDIO` | VCD enrolment and live verification. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | WebRTC signalling and mDNS discovery. |
| `FOREGROUND_SERVICE_MICROPHONE` | From Android 11 a backgrounded app loses the microphone within seconds — a call would go silent the moment the user checked a message. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | `AvailabilityService` keeps the device reachable for calls; it holds no microphone. |
| `VIBRATE` | Risk alerts. |

---

## Demo triggers

Inbound SMS bodies that drive scripted scenarios. Checked in order, each returning early:

| Body | Effect |
|---|---|
| `2000` | Starts the Digital Arrest workflow: overlay, evidence capture, PDF report, trusted-contact alert. |
| contains `TriNetra` | Emergency alarm, vibration, and a full-screen alert over the lock screen. |

---

## Testing

```bash
./gradlew :app:testDebugUnitTest          # 84 JVM tests (JUnit4 + Robolectric + Mockito)
./gradlew :app:connectedDebugAndroidTest  # instrumentation
```

Covered: risk escalation end to end, attack-context correlation, phone-number extraction, protection
policy decisions, identity resolution, overlay controller lifecycle, family alerts, and the Phase-13
hardening invariants (`respondToCall()` called
exactly once, fail-open on timeout, no full numbers in logs).

Not covered: the ONNX inference paths, and anything requiring real telephony.

---

## Honesty about what is real

This section exists because a security app that overstates itself is worse than no security app.

**Real, measured, on-device:**

- Speaker verification and anti-spoofing (ONNX), including the per-contact baseline calibration and the
  verdict stabiliser — both of which exist because of failures observed on real audio.
- Offline Hindi and English transcription (Vosk).
- The deterministic signal, correlation and risk layers.
- WebRTC VoIP with mDNS discovery.

**Real, but cloud:** Groq semantic analysis. Transcript text leaves the device for this; **audio never
does**.

**Simulated, and labelled as such in the code:**

- The Digital Arrest workflow — fixed scoring, fictional caller, documents stamped SIMULATION.

**Uncalibrated on purpose.** `FusionThresholds` ships with `calibrated = false`, and the UI says so
wherever a score appears. These are starting points for calibration against a real evaluation set, not
measured operating points. **The app states no accuracy, precision or recall figures, because none have
been measured.** Test Mode exists so that they can be.

---

## Known gaps

- **No cellular call audio.** Android grants no third-party API for it, so genuine voice verification
  works only on TriNetra-to-TriNetra VoIP calls.
- **No network-level SIM-swap detection** — only the social engineering around it. IMSI-change monitoring
  and port-out correlation are unimplemented.
- **Thresholds unmeasured.** See above.
- **Committed fallback credentials** in `app/build.gradle.kts`. Rotate before publishing.
- **VoIP is LAN-only** — mDNS discovery with no TURN relay, so no calls across networks.
- **Groq is a single point of failure** for the semantic layer. Degradation is graceful (the deterministic
  layers carry on) but the L3 signal simply disappears when it is unreachable.
- **English-first scam keywords.** The signal classifier's keyword sets are English. Hindi transcription
  feeds the LLM layer, which handles it, but the deterministic layer does not.

---

## Project layout

```
app/src/main/java/com/trustmesh/app/
├── MainActivity.kt . TrustMeshApp.kt . TriNetraApplication.kt
├── sensors/           call . sms . notification
├── interaction/       InteractionManager . Interaction
├── core/
│   ├── events/        SecurityEvent . EventNormalizer . RiskLevel
│   ├── identity/      caller resolution + reputation
│   ├── intelligence/  context . groq . risk
│   ├── incident/      SecurityIncidentEngine . IncidentType
│   ├── protection/    ProtectionPolicyEngine . ProtectionAction
│   ├── digitalarrest/ engine . controller . PDF report
│   ├── alert/         EmergencyAlarmManager . FamilyAlertService . TextBee
│   └── firewall/ settings/
├── callaudio/webrtc/  live call intelligence (VCD + Vosk + Groq)
├── vcd/               Voice Clone Defence module (see above)
├── data/              Room database . DAOs . entities . repositories
└── ui/                Compose screens . overlay . theme
```

179 Kotlin sources, 14 test sources.

---

## Licence

Not yet specified. The bundled ONNX and Vosk models carry their own upstream licences — check those
before redistributing.
