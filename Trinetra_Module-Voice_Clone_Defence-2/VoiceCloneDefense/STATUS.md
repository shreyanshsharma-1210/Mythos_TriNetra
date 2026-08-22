# Voice Clone Defense — status, findings and blockers

Last updated 22 August 2026. Branch `v1-updated`.

Every number below was measured. Nothing here is estimated, and where something has not been
measured this document says so rather than filling the gap.

---

## 1. What exists

A standalone, installable Android app. Kotlin, Jetpack Compose, minSdk 26, targetSdk 35. It installs
and runs on real hardware — verified on a **LAVA LXX504 (Android 15, API 35, arm64-v8a)**.

| Piece | State |
|---|---|
| Enrolment — consent, 30–60 s guided recording, encrypted voiceprint | Working |
| Test Mode — run an audio file through the live pipeline | Working, verified on device with real clips |
| Live Verification — foreground service, disclosure interlock, 3 s scoring loop | Built and correct; **cannot get call audio — see blocker 1** |
| Capture diagnostics (Phase 0) | Working. Produced the blocker-1 result. |
| Fusion — SAFE / SUSPICIOUS / CRITICAL / INDETERMINATE | Working |
| On-device models (ONNX Runtime) | Working, 690 ms per window |
| TRINETRA Call — WebRTC VoIP with remote-audio tap | Built and unit-tested; **never run between two phones — see 5b** |
| Dialler — mDNS discovery, ring / accept / decline, contacts, recents | Built; discovery and address book verified on one handset |

**Privacy properties, verified in the built APK:**

- No `CAPTURE_AUDIO_OUTPUT`, no `READ_PHONE_STATE`, no `InCallService`, and no attempt on
  `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK`.
- Permissions held: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS`, and — since the VoIP module (5b) — `INTERNET`, `ACCESS_NETWORK_STATE`,
  `MODIFY_AUDIO_SETTINGS`.
- **`INTERNET` is new and the old claim is retired.** This app used to hold no network permission
  at all, and said so. Carrying a WebRTC call makes that impossible. Inference is still entirely
  on-device and no audio, embedding or derived signal is sent anywhere for analysis — but "the app
  cannot upload anything because it has no network" is no longer a true sentence and is not
  written anywhere any more.
- Raw audio never touches disk. Enrolment audio is zero-filled after embedding; the live ring
  buffer is scrubbed on stop.
- Only the 256-float embedding persists, encrypted with Android Keystore AES-256-GCM.
- The microphone cannot open unless the disclosure banner is on screen, and capture stops if the
  banner ever leaves the screen mid-session.

**Test coverage:** 75 JVM unit tests, 9 instrumented tests. All passing except the newest
device test, which has not been run.

---

## 2. What has been measured

**Model conversion.** Exported ONNX vs. the original PyTorch checkpoints: cosine 1.000000,
max |Δp| 0.000000. int8 quantisation was measured and **rejected** — speaker min cosine 0.336
against a 0.995 bar, spoof max |Δp| 0.189 against a 0.05 bar. Ships float32.

**Latency, on the LAVA LXX504:**

```
spoof-only 557 ms · full path (embedder + spoof) 690 ms · budget 3000 ms
median across a 27-window real clip: 762 ms
```

23–25 % of the 3-second live hop. A handset four times slower would still keep up. One device is
not a range.

**Desk-vs-device agreement.** The same clips scored on the desk in PyTorch and on the handset
through the shipped ONNX models agree **to four decimal places on every window** (desk
0.8262/0.9892 vs device 0.8261/0.9892). The models on the phone compute the same thing as the
reference, on real audio — a stronger claim than the conversion gate, which only covered three
LibriSpeech clips.

**Speaker verification works.** Two genuine recordings of the same person: whole-clip cosine
**0.9370**, per-window median similarity **0.8875**, against a 0.75 match threshold. Correct call.

---

## 3. Blocker 1 — live in-call capture is impossible

**Observed, on a real cellular call, speakerphone on:** the app receives nothing from the
microphone. The instant the call ends, the same screen picks up audio normally.

That rules out a permission fault, a routing fault, and a bug in the capture code. The platform is
deliberately withholding call audio from a third-party recorder.

This is expected behaviour, not a surprise:

- Android 10+ restricts concurrent capture. The telephony stack holds the microphone; a normal app
  is handed silence rather than an error.
- The APIs that *can* read call audio — `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK` — are
  signature-permission, system-dialler only.

**No permission, manifest entry, targetSdk change, or amount of app code unlocks this.** It was a
hard constraint on the project from day one and the app does not attempt to circumvent it.

One handset is not a survey — some OEM builds are reported to pass speakerphone audio through, and
the diagnostics screen checks any phone in about a minute. But the honest default assumption is now
that live in-call capture **does not work**, rather than that it might.

**Mitigation shipped:** the app diagnoses the condition instead of showing a dead level meter. When
capture returns only silence it reads `AudioManager.getMode()`; if a call is in progress it states
that the handset does not pass call audio to third-party apps, that this is an OS restriction
rather than a fault or a missing permission, and points at Test Mode.

**Consequence:** Test Mode is the working path. It runs the identical decoder, slicer, models,
thresholds and fusion rules — `AudioWindow.Provenance` is the only difference between it and live
capture. It is a real path, not a consolation prize, but the "screen a call as it happens" story is
unproven on real hardware.

---

## 4. Blocker 2 — the anti-spoofing model does not work on this audio

The most important measured result in the project, and it is a negative one.

Two clips, **both confirmed genuine recordings of a real person**, scored by the anti-spoofing
model:

| Clip | AASIST | AASIST-L |
|---|---|---|
| `voice1.mp3` | 0.9991 | 0.9988 |
| `voice2.mp3` | 0.9997 | 1.0000 |

Before mitigation the app reported **CRITICAL — possible cloned voice on 26 of 27 windows** of a
real person speaking. That is the worst failure this app can have: telling a family their relative
is a computer-generated impostor.

Every plausible explanation was tested and ruled out:

| Hypothesis | Control | Result |
|---|---|---|
| MP3 compression | genuine speech through the identical 48k → 112 kbps → 16k chain | 0.0009 → 0.0010 |
| Clipping | census of near-full-scale samples and flat runs | 0.000 % clipped, no flat runs |
| Denoiser artifacts | additive noise at 40 / 30 / 20 dB SNR | 0.9991 → 0.9983 |
| Bad checkpoint | re-ran with AASIST-L | 0.9988 / 1.0000 |
| Recording level | scaled 10× down | 0.999 → 0.006, but a louder LibriSpeech clip scores 0.0009 |

The clips are also *cleaner* than the LibriSpeech baseline (noise floors −57/−60 dBFS vs −47/−52),
so noise is not the explanation either. The detector is shaky even on LibriSpeech: one genuine clip
scores a median of 0.70, and the mean across genuine LibriSpeech is roughly 0.33.

**Cause.** AASIST is trained on ASVspoof 2019 LA, whose *bonafide* class is clean studio audio and
whose *spoof* class is 2019-era TTS. These recordings are out of distribution on both axes. The
model is not detecting anything; it is failing to recognise the domain.

**Mitigation shipped — the per-contact baseline.** Enrolment measures the detector against the one
recording whose provenance is not in doubt: the 30–60 s the contact just recorded after consenting.
The median becomes their `baselineSynthetic`:

| Baseline | State | Effect |
|---|---|---|
| ≥ 0.85 | `UNRELIABLE` | Clone check **dropped** for this contact, and the UI says so. Identity matching carries the verdict. |
| measured, with headroom | `USABLE` | Alert threshold becomes `max(0.50, baseline + 0.15)` |
| never measured | `NO_BASELINE` | Ordinary thresholds — **CRITICAL still fires** — with a caveat on the verdict |

`NO_BASELINE` deliberately does not suppress the alert. "Never measured" is a different claim from
"measured and useless", and suppressing on it would silently switch clone detection off for every
contact enrolled before baselines existed — the users least likely to notice. An earlier version of
this guard did exactly that and had to be fixed.

Verified on device: baseline 0.9991 → `UNRELIABLE`, zero CRITICAL windows on genuine audio, while
the same window with `baselineSynthetic = null` still returns CRITICAL/CLONE_SIGNATURE — so the
suppression comes from the measured baseline, not from the alert being broken.

**This is mitigation, not a fix.** It works by switching the clone check off for affected voices.

---

## 5. Blocker 3 — the app has never been tested against a real clone

This is the gap that matters most, and it is the one discussed at the end of the session.

Everything known about detection comes from **genuine** audio. There is no measurement of what the
pipeline does when shown an actual clone. That means:

- The false-positive behaviour is measured. The **true-positive** behaviour is not.
- No precision, recall, or false-positive rate is claimed anywhere in the app or its docs, because
  none has been measured.
- Every decision about whether the detector is salvageable is currently speculation.

**The test that resolves it:** clone `voice1.mp3` with any current tool (ElevenLabs, RVC, XTTS,
F5-TTS), run it through Test Mode with the same contact selected, and read the per-window CSV.

| Clone scores | Meaning | Action |
|---|---|---|
| meaningfully above the 0.9991 genuine baseline | the absolute number is junk but the ordering carries signal | keep the relative rule, calibrate the margin against real pairs |
| same as or below the genuine baseline | the detector is worthless in this domain | drop it, say so, lean on identity |
| similarity also falls below 0.75 | the clone is not good enough to fool the encoder | the working half already catches it |

One afternoon of work. Nothing else about detection should be decided before it.

---

## 5b. The VoIP module — a way around blocker 1

Blocker 1 says the OS will not give a third-party app cellular call audio. It says nothing about a
call the app makes itself. If TRINETRA carries the call, the far end's audio arrives as a decoded
WebRTC track inside our own process, and no platform policy is involved.

```
Device A  <--- WebRTC / DTLS-SRTP --->  Device B
                                             |
                            remote AudioTrack (decoded PCM, pre-playback)
                                             |
                                    RemoteAudioAdapter
                                    48 kHz int16 -> mono float -> 16 kHz
                                             |
                              existing VerificationPipeline + Fusion
                                             |
                                  SAFE / SUSPICIOUS / CRITICAL
```

**The remote audio is taken before playback, not after.** `AudioTrack.addSink(AudioTrackSink)`
hands over decoded PCM straight from the decoder. Routing it out to the speaker and back in through
the microphone would add the room, the speaker response, the microphone response and the device's
echo canceller to every sample — all of them exactly the kind of artefact an anti-spoofing model
mistakes for synthesis, on top of a detector that is already unreliable (blocker 2).

**Library.** `io.getstream:stream-webrtc-android:1.3.10`. Google's own `org.webrtc:google-webrtc`
was last published in 2021 and its `AudioTrack` has no sink API at all, which makes it unusable for
this — the sink is the entire point. Verified before writing code:

```
public interface org.webrtc.AudioTrackSink {
  void onData(java.nio.ByteBuffer, int, int, int, int, long);
}
public class org.webrtc.AudioTrack ... {
  public void addSink(org.webrtc.AudioTrackSink);
}
```

**Discovery and signalling.** Devices advertise themselves over mDNS (`NsdManager`,
`_trinetra._tcp`) and browse for each other, so the dialler lists people to tap rather than
addresses to type. `SignalingServer` owns a listening socket that outlives any individual call —
being reachable is a property of a device, not of a call, and an earlier version that folded the
accept loop into the call session meant a phone could only be rung during the seconds it sat on a
"waiting" screen.

The signalling channel itself is a plain TCP socket carrying newline-delimited JSON. No backend, no
accounts. It is **unencrypted and unauthenticated** — anyone on the same network can connect to a
waiting device. Fine for a POC on a known network, nowhere near sufficient for real use, and
confined to two files so replacing it is a contained job. Media is DTLS-SRTP encrypted by WebRTC
regardless.

**Ringing is a real handshake, not presentation:**

```
caller -- invite{name} -->  callee      callee rings
caller <-- accept ---------  callee     (or decline, and it stops here)
caller -- offer ---------->  callee
caller <-- answer ---------  callee
```

The first version went straight to the offer, which meant WebRTC negotiated before anybody had
agreed to talk — **the microphone opened on the receiving device before its owner had answered.**
The microphone now opens in `answer()` and nowhere earlier, because that is the moment consent
exists. This is the same rule the microphone path already follows with its disclosure banner,
applied to a different audio source.

**The dialler shows the phone's own address book and a Recents list**, because that is what a
dialler is. Two deliberate limits:

- **Tapping a contact does not dial their phone number.** Every call goes to a TRINETRA device on
  the same Wi-Fi, and the screen says so rather than leaving the user to find out when somebody
  unexpected answers. Recents records both the name that was tapped and the device that actually
  answered, because those are different facts. With several devices present the user is asked which
  one instead of the call landing somewhere arbitrary.
- **Recents is the app's own table, not the system call log.** Reading that would mean asking for
  `READ_CALL_LOG` — every cellular call the user has ever made — to show a handful of ours, which
  are not in that log anyway. Migration 2 → 3, additive; no voiceprint is touched.

Contact names are read only to populate the list. They are never stored, matched, or sent anywhere.

**An incoming call reaches the user with the app closed:** a full-screen-intent notification on a
high-importance channel with answer and decline actions. The channel is deliberately silent —
`Ringer` plays the user's *own* ringtone, so a TRINETRA call rings with the sound they already
associate with being called, and respects silent and vibrate modes.

**This costs the app its "no network capability" property.** The manifest previously stated the app
held no `INTERNET` permission and therefore could not upload anything at all. That claim is no
longer true and has been removed rather than left standing. What has not changed: no audio,
embedding or derived signal is sent anywhere for analysis, the models still run on-device, and
there is no server to send to. The only audio leaving the device is the user's own microphone going
to the person they chose to call, which is what a phone call is.

**Verified.** The library exposes the sink — checked by disassembling the AAR before any code was
written, because the whole module is pointless without it. The format conversion is covered by 7
JVM unit tests: int16 scaling, stereo-to-mono averaging (opposite channels must cancel, which
catches reading a stereo buffer as mono), 48 kHz → 16 kHz level preservation, refusal to pad a
short window, silence detection, buffer scrubbing. That arithmetic is worth pinning down off-device
because getting it wrong still yields plausible floats and a plausible score from audio that was
never on the wire.

On a Redmi Note 12 Pro (Android 14), the app installs, the dialler renders, the address book loads,
dialling is correctly disabled while no peer is present, and:

```
SignalingServer: listening for calls on port 47821
PeerDiscovery: advertising as Xiaomi 22101316I
```

**Not verified: anything that needs two phones.** Only one handset has ever been visible to `adb`
in the same session — Windows never enumerated the second one, which is a cable or port problem
rather than a software one. That leaves unrun:

- **Phase 1's success criterion: Device A speaks, Device B receives non-zero remote frames.**
- Whether ICE completes between two handsets on a real Wi-Fi.
- Every latency figure — call setup, first remote frame, speech-to-result. The diagnostics capture
  all of them; none has a value yet.
- Whether the ring, accept, decline and hang-up paths behave on real hardware.

Until that first line is true, this module is **plausible rather than proven**. Worth remembering
that the previous audio source also looked entirely plausible right up until it returned silence.

---

## 5c. Channel mismatch — measured, and largely fixed

Enrolment records straight from the microphone. A call arrives after the sender's gain control,
through a low-bitrate codec, band-limited. Same voice, different signal — and nobody had checked
what the difference costs the speaker encoder.

Measured with `tools/channel_experiment.py`, one person, two genuine recordings, enrolment channel
down the side and call channel across the top:

| enrolled through | mic | voip-wb | voip-nb |
|---|---|---|---|
| **mic** (what the app did) | 0.9370 | 0.9144 | **0.7655** |
| voip-wb | 0.9074 | 0.9388 | 0.7898 |
| voip-nb | 0.7335 | 0.7753 | **0.9766** |

**A microphone voiceprint scores 0.7655 against narrowband call audio from the same speaker** — on
top of the 0.75 match threshold. That is a measured, mechanical cause of somebody's own voice
coming back as "not confirmed", and it is nothing to do with the models being weak. Enrolling
through the matching channel recovers it to 0.9766, a gain of +0.21.

Anti-spoofing barely moves across channels (0.9991 → 0.9998), so the codec is **not** what drives
the 0.999 in blocker 2. Two independent problems, now cleanly separated: identity was fixable
without new data, clone detection still is not.

**What the app does now.** One recording, several voiceprints. Enrolment derives an embedding per
channel condition — microphone, wideband VoIP, narrowband telephony — and stores all of them, each
with its own anti-spoofing baseline, because the reading that matters is the one taken through the
channel the call actually arrives over. Scoring takes the **best** match, not the mean: the prints
differ by channel on purpose, so averaging would drag a good match down with the two conditions
this call is not in. Cost is one cosine per extra print, which is nothing beside an inference.

Storage packs the variants into the one existing encrypted blob, so a contact stays a single
encrypted object with one key operation. Migration 3 → 4 is additive; rows enrolled earlier hold a
single print, are read as the microphone condition, and keep working with the old behaviour until
the contact re-enrols.

**Honest limits.** The channel conditions are simulations, not Opus — there is no encoder on the
desk and none is needed on the device. They reproduce the parts that move an embedding
(band-limiting, gain, quantisation noise), and the narrowband case uses a real G.711 mu-law round
trip. The +0.21 is a real measurement of a real effect; the exact numbers against *actual* WebRTC
audio have not been taken, and would need a call recorded through the app.

Not yet run on hardware: `RealClipPipelineTest.channelMatchedPrintsBeatAMicrophoneOnlyPrintOnCallLikeAudio`
asserts the ordering on the device rather than the desk, and is written but unexecuted — the test
handset disconnected before it could run.

---

## 6. Smaller open issues

- **No speaker separation.** On speakerphone the microphone hears both parties. Nothing in the
  pipeline separates them, so windows containing the user's own speech are scored against the
  contact's voiceprint and return a mismatch. An own-voice energy gate would largely fix it;
  neither that nor diarisation is built.
- **The app does not detect calls.** By design — no `READ_PHONE_STATE`, no `InCallService`. The
  user opens the app and presses start.
- **Thresholds are uncalibrated.** `FusionThresholds.PROVISIONAL` values are starting points, not
  measured operating points, and the UI says so wherever a score appears.
- **Voiceprints are tied to a model build.** Prints from a different encoder are marked stale and
  refused rather than compared, because a cosine between embeddings from two different encoders is
  a meaningless number that would still render as a confident percentage.
- **APK is 55.8 MB** (arm64): ONNX Runtime's native library is ~17 MB, WebRTC's is ~12 MB, and the
  models are ~8 MB. Nothing is tuned for size. The universal build is now 142 MB, above GitHub's
  100 MB per-file limit.
- **Signalling is unauthenticated.** Anyone on the same network can connect to a waiting device,
  and mDNS advertises the device's name to everyone on that network. POC-only; see 5b.
- **Availability dies with the process.** The listening socket and the mDNS advertisement live in a
  singleton, not a foreground service, so a device stops being reachable if Android kills the app.
  Fine while the app is open; not how a phone behaves.
- **The far end's audio has been through the sender's noise suppression, AGC and the Opus codec**
  before we ever see it. That is a different signal from a microphone recording, and what it does
  to either model has not been measured.
- **`voice1.mp3` and `voice2.mp3` are committed to git history** (in `b54b310`, before this
  work). They are recordings of a real person. Worth stripping.

---

## 7. Honest summary

**What works, measured on real hardware:**

> *"Is this the person I enrolled?"* — 0.8875 median similarity on real audio, 762 ms per window,
> matching the desk reference to four decimals.

**What does not work:**

> *"Is this a clone?"* — on a voice where the detector has been shown not to work. And nowhere at
> all during a live cellular call, because the OS does not permit it.

**What is built but unproven:**

> *"Screen a call as it happens"* — the VoIP module routes around the platform block by carrying
> the call itself, and every part of it that can be tested without two phones has been. The part
> that matters most has not: no remote audio frame has ever arrived on a second device.

The correct description of this project today is: **a working on-device speaker-verification system
with an anti-spoofing stage that is only trustworthy on voices where it has been shown to work; a
cellular-capture path blocked by platform policy rather than by anything in the code; and a VoIP
path that should sidestep that block but has not yet been run between two handsets.**

---

## 8. Recommended next steps, in order

1. **Run the two-phone test** (see 5b — the cheapest open item, and the one gating the most).
   Install on a second handset, switch both to Reachable, call, and read the diagnostics panel. `frames received`
   climbing with `voiced frames` above zero is Phase 1's whole success criterion, and everything
   built on top of the VoIP module is speculation until that line is true.
2. **Measure against a real clone** (blocker 3). Highest value for the detection question, one
   afternoon, and everything else about detection depends on the outcome. Nothing in 5c touched
   the detector: identity accuracy improved, clone-detection accuracy did not, and it cannot until
   there is a cloned sample to measure against.
3. **Challenge-response at enrolment.** A codeword or question agreed in advance, stored encrypted
   beside the voiceprint and surfaced on the alert screen. A perfect clone of a voice does not carry
   a shared secret. Best fraud prevention per line of code available in this codebase, and it does
   not depend on the detector working.
4. **Replace the anti-spoofing checkpoint** if the clone test shows the current one is unsalvageable —
   ASVspoof 2021 DF (deepfake track, includes codec-compressed audio) or an in-the-wild-trained
   model.
5. **Own-voice gate**, if any speakerphone scenario is going to matter.
6. **Check third-party VoIP capture.** WhatsApp and Signal calls use `MODE_IN_COMMUNICATION`, not
   `MODE_IN_CALL`. The same concurrent-capture rules probably apply, but it is untested and takes a
   minute with the Phase 0 diagnostics screen. If it works, that is a second live path that is not
   cellular and does not require the other party to be running this app.
7. **Replace the signalling layer** before anyone uses this outside a lab. It is unauthenticated
   and unencrypted today, which is a deliberate POC choice, not an oversight.

---

## Appendix — commits on `v1-updated`

| Commit | Subject |
|---|---|
| `5241b1b` | Fix uncalibrated contacts losing clone detection entirely |
| `99c46f4` | Measure on-device inference latency instead of only asserting it fits |
| `64be479` | Consume window insets, and prove the pipeline on a real handset |
| `f476a89` | Phase 0: report the real reason there is no call audio, and record the result |
| `69c4cb0` | Add STATUS.md — what is built, what is measured, and what is blocked |
| `c089877` | Add the arm64-v8a debug APK at the repo root |
| `fdfdafe` | Add a WebRTC VoIP module that taps remote audio before playback |
| `8de34d6` | Turn the VoIP POC into a dialler |
