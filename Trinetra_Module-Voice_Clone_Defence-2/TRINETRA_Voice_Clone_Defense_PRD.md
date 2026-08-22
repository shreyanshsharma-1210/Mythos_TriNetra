# Voice Clone Defense — PRD

**Live In-Call Voiceprint Verification & Anti-Spoofing Module**
A TRINETRA Module — Track 2: Speaker-Dependent, Consent-Disclosed Capture

**Team:** MYTHOS · **Hackathon:** IKIGAI 2026 · **Problem ID:** IHNG6
**Parent Product:** TRINETRA v3.1 · **Document Type:** Product Requirements Document (PRD)
**Version:** 1.0 — Draft

> "TRINETRA does not need to hear the call to protect the user — but when the user asks it to listen, it says so out loud."

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Goals & Success Metrics](#3-goals--success-metrics)
4. [Users & Use Cases](#4-users--use-cases)
5. [Scope](#5-scope)
6. [User Flow](#6-user-flow--demo-journey)
7. [Technical Architecture](#7-technical-architecture)
8. [Functional Requirements](#8-functional-requirements)
9. [Non-Functional Requirements](#9-non-functional-requirements)
10. [Data, Privacy & Consent](#10-data-privacy--consent)
11. [Risks & Mitigations](#11-risks--mitigations)
12. [Demo Plan & Acceptance Criteria](#12-demo-plan--acceptance-criteria)
13. [Milestones](#13-milestones)
14. [Open Questions](#14-open-questions)

---

## 1. Executive Summary

Voice cloning has made family-impersonation and digital-arrest scams dramatically more convincing — a scammer no longer needs to sound plausible, they can sound exactly like someone's son, mother, or manager. This module adds live, in-call voice verification to TRINETRA: when the existing risk pipeline flags a call as suspicious, the app can capture the caller's voice through the device microphone (call on speaker, with mandatory on-screen disclosure), compare it against a voiceprint the user's trusted contact enrolled in advance, and separately check whether the voice shows signs of being AI-generated.

This document specifies a scoped, honestly-labeled hackathon build: **Track 2 — speaker-dependent, consent-disclosed, live capture.** It does not attempt silent cellular call interception, which is blocked at the Android OS level for third-party apps and is out of scope by design, not by omission (see Section 5).

> **A clone can copy a voice. It cannot copy a documented, disclosed verification process.**

---

## 2. Problem Statement

Voice-cloning tools (ElevenLabs, RVC, and similar) can now produce a convincing clone of a specific person's voice from as little as a few seconds of reference audio. Combined with a plausible pretext ("I'm stuck, I need money urgently, don't tell anyone"), this is now a practical, low-cost attack — not a theoretical one.

TRINETRA's existing pipeline (identity, psychology, intent, context) already scores this kind of interaction as risky using metadata and behavioral signals. What it currently cannot do is answer the most direct version of the question a worried user actually asks in the moment:

> **"Does this actually sound like my son, or does it just sound like a voice?"**

Voice similarity alone cannot answer this safely, because a good clone is specifically optimized to score well on similarity. The missing piece is a second, independent signal: does this audio show the statistical fingerprints of synthetic generation, regardless of how similar it sounds?

---

## 3. Goals & Success Metrics

### 3.1 Goals

- Give users a concrete, on-demand way to verify a suspicious caller's voice against a trusted contact's enrolled voiceprint.
- Detect synthetic/cloned speech as a signal independent of raw similarity, so a good clone doesn't defeat the check.
- Do this entirely through legitimate, disclosed, on-device capture — no cellular interception, no silent recording.
- Feed the result into TRINETRA's existing evidence-fusion and Action Firewall pipeline as one more Identity Evidence signal, not a parallel system.

### 3.2 Success Metrics (Hackathon Scope)

| Metric | Target |
|---|---|
| Demo repeatability | Real-vs-cloned demo scenario succeeds 5 of 5 runs |
| Time to result | Risk classification returned within one 3–5s audio window after capture starts |
| Real-clip false positive rate | Enrolled contact's real voice does not trigger CRITICAL in the demo set |
| Cloned-clip detection rate | Cloned demo clips trigger CRITICAL synthetic_probability in the demo set |
| Disclosure visibility | Banner/tone confirmed visible within 1s of capture starting, in every run |

*These are hackathon-demo-set metrics, not population-level accuracy claims — see Section 12 for the exact demo protocol and Section 9 for why broader accuracy figures are intentionally left TBD.*

---

## 4. Users & Use Cases

| User | Use Case |
|---|---|
| Primary User (e.g., a parent) | Receives an unusual, urgent call claiming to be from their enrolled child; wants to verify it's really them before acting. |
| Trusted Contact (e.g., the child) | Enrolls their voice once, in advance, so their parent's TRINETRA can verify calls claiming to be them. |
| Hackathon Judge | Watches a live, repeatable demo distinguishing a real voice clip from a cloned one, scored transparently on stage. |

### 4.1 Primary User Story

> As a parent receiving a call that claims to be my son asking for urgent money,
> I want TRINETRA to tell me whether the voice is really his or a synthetic clone,
> so that I can decide whether to trust the call before I act.

---

## 5. Scope

### 5.1 In Scope — Track 2

- Live mic capture during an active call, only while the call is on speaker and only after the user is shown a persistent disclosure banner (and optional tone).
- Voiceprint enrollment for trusted contacts, with explicit per-contact consent.
- On-device speaker-embedding comparison (`voice_similarity`) against the enrolled voiceprint.
- On-device anti-spoofing / synthetic-speech detection (`synthetic_probability`), independent of similarity.
- Fusion of both scores into TRINETRA's existing Identity Evidence and Action Firewall pipeline.

### 5.2 Out of Scope

| Item | Reason |
|---|---|
| Silent cellular call interception | Blocked at the Android OS level for third-party apps since Android 10 (`VOICE_CALL`/`VOICE_UPLINK`/`VOICE_DOWNLINK` are signature-only); not a policy choice, a platform wall. |
| Analyzing calls not on speaker | No legitimate on-device audio path exists without the call being audible to the microphone. |
| Undisclosed / background listening | Would cross into wiretap-law territory in many jurisdictions and directly contradicts TRINETRA's Privacy Contract. |
| Becoming the default dialer for audio access | Massive scope increase (telecom certification, emergency-calling compliance); breaks the "we don't replace your dialer" architecture rule. |
| Population-level accuracy claims | Hackathon demo set is small and curated; broader accuracy requires a proper evaluation dataset (see Section 9). |

> **This is a deliberate boundary, not a shortcut — and it is the boundary that keeps the feature legal, demoable, and consistent with the rest of TRINETRA.**

---

## 6. User Flow — Demo Journey

This is the exact sequence the hackathon demo follows, end to end, using two physical devices so the whole interaction is visible on stage.

```
1. Enroll trusted contact's voice
   30–60s clean speech, off-camera, before demo
        ↓
2. Second device plays a REAL clip
   of the enrolled contact, on speaker
        ↓
3. TRINETRA scores it
   voice_similarity HIGH · synthetic_probability LOW → SAFE
        ↓
4. Second device plays a CLONED clip
   ElevenLabs / RVC clone of same voice, on speaker
        ↓
5. TRINETRA scores it
   voice_similarity HIGH · synthetic_probability HIGH → CRITICAL
        ↓
6. Full-screen alert fires on stage
   identity risk explained live to the judges
```

---

## 7. Technical Architecture

**Phase 0 validation (do this before writing any ML code):** confirm on the actual demo device that `AudioRecord`/`MediaRecorder` can capture clean mic audio while a call is active and on speaker. Mic behavior during `AudioManager.MODE_IN_CALL` is OEM-dependent — this is the single biggest technical risk in this module, and it should be resolved in the first hour of work, on at least two devices if possible.

```
Risk crosses HIGH  (existing evidence-fusion pipeline, §4.7)
        ↓
Prompt: "Put call on speaker to verify"
        ↓
Disclosure banner + tone starts
"This call is being screened for fraud protection by TRINETRA"
        ↓
Foreground mic capture begins
RECORD_AUDIO, foregroundServiceType=microphone
        ↓
Rolling 3–5s audio window
        ↓
Speaker Embedding Model              Anti-Spoofing Model
Resemblyzer / ECAPA-TDNN             AASIST / RawNet2
(TFLite, int8)                       (TFLite, int8, ASVspoof-trained)
        ↓                                    ↓
   voice_similarity                  synthetic_probability
   (cosine similarity vs.            (likelihood of AI-generated
    enrolled voiceprint)              speech)
        └─────────────┬─────────────────────┘
                       ↓
         Fuse into Identity Evidence (§4.7)
                       ↓
          Action Firewall alert (§4.10)
```

### 7.1 Model Choices

| Component | Options | Recommendation |
|---|---|---|
| Speaker embedding | Resemblyzer · ECAPA-TDNN (SpeechBrain `spkrec-ecapa-voxceleb`) | Resemblyzer for speed of setup; ECAPA-TDNN if time allows, for accuracy |
| Anti-spoofing | AASIST · RawNet2, ASVspoof 2019/2021 LA-trained | Use an existing pretrained checkpoint — do not train from scratch |
| On-device runtime | TensorFlow Lite (int8 post-training quantized) | Convert via ONNX as an intermediate step from PyTorch checkpoints |

### 7.2 Conversion Pipeline

```
PyTorch checkpoint
  → ONNX (torch.onnx.export)
  → TensorFlow (onnx-tf)
  → TensorFlow Lite (TFLiteConverter)
  → int8 post-training quantization
  → org.tensorflow:tensorflow-lite (Android inference)
```

*Budget real time for this conversion step on day one — it is consistently the part that eats unexpected hours.*

---

## 8. Functional Requirements

Requirements are numbered `FR-VOICE-<n>` and slot into the existing TRINETRA traceability scheme as a new module under the Identity Engine (§4.3).

### 8.1 Enrollment (FR-VOICE-ENR)

| ID | Requirement |
|---|---|
| FR-VOICE-ENR-1 | The system shall allow a user to enroll a trusted contact's voice only with that contact's explicit, informed consent. |
| FR-VOICE-ENR-2 | The system shall capture 30–60 seconds of clean speech across a small number of short prompts during enrollment. |
| FR-VOICE-ENR-3 | The system shall derive a fixed-length speaker embedding from the enrollment audio and discard the raw audio after embedding extraction. |
| FR-VOICE-ENR-4 | The system shall store only the resulting voiceprint (embedding vector), encrypted at rest, never the raw enrollment audio. |
| FR-VOICE-ENR-5 | The system shall allow the enrolled contact to revoke consent and have their voiceprint permanently deleted on request. |

### 8.2 Disclosure & Capture (FR-VOICE-CAP)

| ID | Requirement |
|---|---|
| FR-VOICE-CAP-1 | The system shall only capture call audio via the device microphone while the call is on speaker; it shall not attempt any cellular/VOICE_CALL audio source. |
| FR-VOICE-CAP-2 | The system shall display a persistent, clearly visible disclosure banner ("This call is being screened for fraud protection by TRINETRA") for the full duration of capture, and shall start it within 1 second of capture beginning. |
| FR-VOICE-CAP-3 | The system shall run mic capture as a foreground service with `foregroundServiceType="microphone"` declared, per Android 14+ requirements. |
| FR-VOICE-CAP-4 | The system shall request `RECORD_AUDIO` permission during onboarding, not mid-call, and shall degrade gracefully (no capture, clear messaging) if permission is denied. |
| FR-VOICE-CAP-5 | The system shall process audio in rolling 3–5 second windows and shall not persist raw captured audio beyond the window needed for inference. |

### 8.3 Verification Pipeline (FR-VOICE-VER)

| ID | Requirement |
|---|---|
| FR-VOICE-VER-1 | For each audio window, the system shall compute `voice_similarity` as the cosine similarity between the live speaker embedding and the enrolled voiceprint. |
| FR-VOICE-VER-2 | For each audio window, the system shall independently compute `synthetic_probability` using an anti-spoofing model, without relying on the similarity score. |
| FR-VOICE-VER-3 | The system shall treat high `voice_similarity` combined with high `synthetic_probability` as the specific signature of a clone attack, and shall classify this combination as at least HIGH identity risk regardless of how the similarity score alone would have been read. |
| FR-VOICE-VER-4 | The system shall feed `voice_similarity` and `synthetic_probability` into the existing Identity Evidence structure (§4.7) rather than producing a separate, parallel verdict. |
| FR-VOICE-VER-5 | The system shall run both models fully on-device; no audio or embedding shall be transmitted off-device for this verification path. |

### 8.4 Alerting (FR-VOICE-ALT)

| ID | Requirement |
|---|---|
| FR-VOICE-ALT-1 | The system shall reuse the existing Action Firewall UI pattern (§4.10) for voice-verification results: quiet at LOW, compact card at ELEVATED, warning at HIGH, full-screen at CRITICAL. |
| FR-VOICE-ALT-2 | A CRITICAL voice-verification result shall explain, in plain language, both scores that led to it (e.g., "sounds like [contact] but shows signs of being computer-generated"). |

---

## 9. Non-Functional Requirements

### 9.1 Performance

- **NFR-VOICE-PERF-1:** A risk classification shall be available within one 3–5s audio window after capture begins — no multi-window wait before the first result.
- **NFR-VOICE-PERF-2:** Both models shall run as quantized (int8) TFLite models to keep on-device inference latency compatible with a "live" feel.

### 9.2 Accuracy — Intentionally Left TBD

Precision, recall, and false-positive rate for both models are not stated here as fixed numbers. They are to be measured against the team's actual curated demo set (real clips + cloned clips of the same enrolled voices) before the final freeze, and reported alongside the demo — not estimated in advance. This mirrors the same discipline applied to the rest of TRINETRA's SRS: a number a judge can ask about should be a number the team has actually measured.

| Metric | Status |
|---|---|
| Real-clip similarity score (expected range) | TBD — measure on demo set |
| Cloned-clip synthetic_probability (expected range) | TBD — measure on demo set |
| False-positive rate on real, stressed/excited speech | TBD — measure on demo set |
| End-to-end latency, capture start → first result | TBD — measure on target device |

### 9.3 Reliability

- **NFR-VOICE-REL-1:** If mic capture is unavailable (permission denied, OEM restriction, background attenuation), the system shall clearly state that live voice verification is unavailable rather than silently failing or reporting a false score.
- **NFR-VOICE-REL-2:** The rest of TRINETRA's pipeline (identity, psychology, intent, context) shall continue functioning normally if this module is unavailable — it is an additional signal, not a dependency.

---

## 10. Data, Privacy & Consent

This module handles biometric data and live audio, both of which carry real legal and ethical weight. It follows the same Privacy Contract already established for TRINETRA, applied specifically here:

**Voice Data Contract**

1. Enrollment requires the enrolled person's own explicit consent — not the primary user's consent on their behalf.
2. Raw audio (enrollment and live capture) is never persisted beyond the moment it's needed for embedding/inference.
3. Only the derived voiceprint (a vector, not audio) is stored, and only encrypted at rest.
4. Live capture never happens without a visible, active disclosure banner.
5. No audio, embedding, or raw signal leaves the device for this verification path.
6. Any enrolled contact can revoke consent and trigger permanent deletion of their voiceprint.

### 10.1 Legal Framing

Because capture only happens on a speakerphone call with an active on-screen (and optionally audible) disclosure, this is meaningfully different from silent interception: the audio is already audible in the room, and the app is not hiding that it's listening. This keeps the feature aligned with informed-consent expectations rather than wiretap-style covert monitoring — but this is a product design position, not a legal opinion, and should be reviewed against local telecom/privacy regulations before any production use.

---

## 11. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Mic capture during MODE_IN_CALL is unreliable or OEM-restricted | Validate on the actual demo device in Phase 0, before any other work; have a fallback (pre-recorded clip demo) ready regardless |
| A good clone defeats similarity-only detection | Never rely on voice_similarity alone — synthetic_probability from a dedicated anti-spoofing model is the core defense, not a bonus signal |
| Anti-spoofing model doesn't generalize to newer TTS/cloning tools | Use recent ASVspoof-trained checkpoints; acknowledge that detection models age as cloning tech improves and will need periodic refresh |
| Real speech under stress sounds "unusual" and false-triggers as synthetic | Explicitly test this case in the demo set (Section 9.2); tune threshold with this case in mind, not just clone detection |
| On-device model conversion (PyTorch → TFLite) takes longer than expected | Do this on day one; treat it as the highest-risk engineering step in the whole module, not a routine export |
| Judges read live-audio capture as "call interception" | Lead the demo explanation with the disclosure banner and the on-speaker requirement — Section 5.2 exists specifically to preempt this question |

---

## 12. Demo Plan & Acceptance Criteria

### 12.1 Demo Protocol

1. Enroll a teammate's voice ahead of time, off-camera (Section 6, step 1).
2. On stage: play a real clip of that teammate's voice from a second device, on speaker, into the primary device running TRINETRA.
3. Show TRINETRA scoring it — high similarity, low synthetic_probability, SAFE.
4. Play an ElevenLabs/RVC-cloned clip of the same voice, same setup.
5. Show TRINETRA scoring it — high similarity, high synthetic_probability, CRITICAL — and the full-screen alert firing.
6. Narrate the disclosure banner explicitly at the moment capture starts, so judges see consent-handling as part of the design, not an afterthought.

### 12.2 Acceptance Criteria

| ID | Criterion |
|---|---|
| AC-01 | Given the demo protocol above, the real clip is scored SAFE and the cloned clip is scored CRITICAL, in that order, without manual intervention between steps. |
| AC-02 | The disclosure banner is visibly active before any score is shown, in every run. |
| AC-03 | The full sequence (enrollment excluded) completes in under 60 seconds on stage. |
| AC-04 | The sequence succeeds 5 out of 5 consecutive rehearsal runs before the actual judged demo. |
| AC-05 | If mic capture fails on the demo device for any reason, a pre-recorded fallback video of a successful run is ready as backup. |

---

## 13. Milestones

| Milestone | Exit Condition |
|---|---|
| M0 — Capture feasibility | Confirmed clean mic capture during an active speakerphone call, on the actual demo device |
| M1 — Model conversion | Both models running on-device as quantized TFLite, producing a score on a static test clip |
| M2 — Enrollment flow | A teammate's voice can be enrolled end-to-end through the app UI |
| M3 — Live pipeline | Real-time 3–5s window scoring works during an actual live speakerphone call |
| M4 — Fusion & alerting | Scores feed into existing Identity Evidence and trigger the correct Action Firewall UI state |
| M5 — Demo hardening | 5-of-5 successful rehearsal runs per Section 12.2, plus fallback video recorded |

---

## 14. Open Questions

- Which specific pretrained AASIST/RawNet2 checkpoint gives the best accuracy/speed tradeoff on our target device — needs a short bake-off during M1.
- Exact similarity/synthetic-probability thresholds for the LOW/ELEVATED/HIGH/CRITICAL mapping — to be calibrated against the demo set, not fixed in advance (consistent with the main SRS's calibration methodology).
- Whether an audible tone (in addition to the visual banner) is worth the added complexity for the hackathon timeline, or a v2 addition.
- How voice enrollment should be re-triggered over time as a person's voice naturally changes — out of scope for the hackathon build, worth flagging for the roadmap.

---

*TRINETRA — Voice Clone Defense · Team MYTHOS · IKIGAI 2026 Hackathon · Problem ID IHNG6*

*A clone can copy a voice. It cannot copy a documented, disclosed verification process.*
