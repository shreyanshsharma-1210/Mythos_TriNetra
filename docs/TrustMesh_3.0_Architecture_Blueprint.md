# TrustMesh 3.0
## Architecture Blueprint — Dialer-Independent Security Layer

---

# 1. System Context

TrustMesh is a standalone Android security companion.

It does not contain a dialer.

It does not replace the user's phone application.

```text
                        USER DEVICE
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
          ▼                 ▼                 ▼
     DEFAULT DIALER     NOTIFICATIONS      APPS / ACTIONS
          │                 │                 │
          ▼                 ▼                 ▼
      ANDROID TELECOM   NOTIFICATION      PACKAGE / USAGE
          │              LISTENER            SIGNALS
          │                 │                 │
          └─────────────────┼─────────────────┘
                            ▼
                     TRUSTMESH CORE
```

---

# 2. Core Pipeline

```text
                 ┌───────────────────────┐
                 │   EVENT SOURCES       │
                 │                       │
                 │ Calls                 │
                 │ Notifications / SMS   │
                 │ Package events        │
                 │ Usage/context events  │
                 │ High-risk actions     │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ EVENT NORMALIZER      │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ INTERACTION MANAGER   │
                 │                       │
                 │ Interaction ID        │
                 │ Monitoring window     │
                 │ Temporal state        │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ CONTEXT GRAPH         │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ INTELLIGENCE          │
                 │                       │
                 │ Identity              │
                 │ Psychology            │
                 │ Intent                │
                 │ Attack Context        │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ EVIDENCE FUSION       │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ RISK + CONSEQUENCE    │
                 └───────────┬───────────┘
                             │
                             ▼
                 ┌───────────────────────┐
                 │ POLICY ENGINE         │
                 └───────────┬───────────┘
                             │
               ┌─────────────┼─────────────┐
               ▼             ▼             ▼
            ALLOW         VERIFY        PROTECT
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │ ACTION FIREWALL │
                                  └────────┬────────┘
                                           │
                         ┌─────────────────┼────────────────┐
                         ▼                 ▼                ▼
                      WARNING         FULL SCREEN        ESCALATE
                                                        / REPORT
```

---

# 3. Call Flow

## Incoming call

```text
Caller
  │
  ▼
Android Telecom
  │
  ├───────────────► User's Default Dialer
  │
  └───────────────► TrustMesh CallScreeningService
                           │
                           ▼
                    Initial Call Context
                           │
                           ▼
                    Interaction Created
                           │
                           ▼
                       Risk State
```

TrustMesh does not become the dialer.

---

# 4. After Answer

```text
USER ANSWERS
     │
     ▼
DEFAULT DIALER CONTINUES THE CALL
     │
     ▼
TRUSTMESH:
START MONITORING WINDOW
     │
     ├── Notifications
     ├── SMS where available
     ├── Package events
     ├── Usage/context events
     ├── Action observations
     └── Identity/context history
```

The call audio is deliberately not used.

---

# 5. Monitoring Window

```text
CALL DETECTED
     │
     ▼
INITIAL RISK
     │
     ▼
USER ANSWERS
     │
     ▼
┌──────────────────────────────┐
│ TEMPORARY MONITORING WINDOW  │
│                              │
│ Event collection             │
│ Context correlation          │
│ Temporal reasoning           │
└──────────────┬───────────────┘
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
    normal   risky    critical
       │       │        │
       ▼       ▼        ▼
     quiet    warn    protect
```

The monitoring window should decay/end when the interaction resolves.

---

# 6. Event Model

All adapters output:

```text
SecurityEvent
```

Conceptual fields:

```text
event_id
interaction_id
timestamp
source
event_type

claimed_identity
identity_evidence

intent
requested_action

amount
recipient
url
package

urgency
psychology_signals

verification_state
consequence_class

confidence
```

---

# 7. Context Graph

```text
                [CALL]
                  │
         unknown caller
                  │
                  ▼
              [MESSAGE]
                  │
          suspicious link
                  │
                  ▼
              [PACKAGE]
                  │
            new APK
                  │
                  ▼
              [ACTION]
                  │
              ₹50,000
                  │
                  ▼
               [RISK]
```

Every node carries evidence.

Every edge can carry:

- timestamp delta
- relationship
- shared entity
- shared number
- shared domain
- shared package
- interaction ID

---

# 8. Intelligence Layer

```text
                 SECURITY EVENTS
                        │
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
     IDENTITY       PSYCHOLOGY         INTENT
        │               │                │
        └───────────────┼────────────────┘
                        ▼
                 ATTACK CONTEXT
                        │
                        ▼
               TEMPORAL REASONING
```

---

# 9. Identity Model

```text
identity_confidence
relationship_confidence
verification_state
context_anomaly
```

Example:

```text
Known contact: YES
Number known: YES
Action unusual: YES
Current intent: HIGH RISK
```

The engine should not automatically downgrade risk simply because the contact is known.

---

# 10. Psychology Model

Signals:

```text
AUTHORITY
FEAR
URGENCY
SECRECY
ISOLATION
REWARD
SCARCITY
INTIMIDATION
EMOTIONAL_PRESSURE
VERIFICATION_SUPPRESSION
```

---

# 11. Intent Model

Examples:

```text
SEND_MONEY
SHARE_OTP
SHARE_PASSWORD
OPEN_LINK
INSTALL_APP
ENABLE_PERMISSION
SCREEN_SHARE
DISCLOSE_DOCUMENT
CALL_NUMBER
```

---

# 12. Consequence Model

```text
                    CONSEQUENCE
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
   Low consequence    High consequence   Critical
        │                │                 │
        ▼                ▼                 ▼
      Warn            Verify            Protect
```

Possible classes:

```text
INFORMATIONAL
CREDENTIAL_COMPROMISE
FINANCIAL_LOSS
ACCOUNT_TAKEOVER
DEVICE_COMPROMISE
PRIVACY_EXPOSURE
IDENTITY_THEFT
IRREVERSIBLE_EXTERNAL_ACTION
```

---

# 13. Risk State Machine

```text
                 ┌─────────┐
                 │   LOW   │
                 └────┬────┘
                      │ evidence
                      ▼
                ┌───────────┐
                │ ELEVATED  │
                └─────┬─────┘
                      │ escalation
                      ▼
                 ┌────────┐
                 │  HIGH  │
                 └───┬────┘
                     │ severe consequence
                     ▼
               ┌──────────┐
               │ CRITICAL │
               └──────────┘
```

Behavior:

```text
LOW
→ quiet

ELEVATED
→ small security card

HIGH
→ explanation + verification

CRITICAL
→ Action Firewall + protection UI
```

---

# 14. Protection Layer

```text
                 POLICY DECISION
                       │
              ┌────────┼─────────┐
              ▼        ▼         ▼
            ALLOW    VERIFY    PROTECT
                                │
                 ┌──────────────┼───────────────┐
                 ▼              ▼               ▼
              WARNING       FULL-SCREEN      ESCALATE
                                                │
                                       trusted contact /
                                       incident report
```

---

# 15. User Experience State Flow

```text
                    IDLE
                      │
                      ▼
               INCOMING CALL
                      │
                      ▼
             CALLER INFORMATION
                      │
                      ▼
                 USER ANSWERS
                      │
                      ▼
                 MONITORING
                      │
         ┌────────────┼─────────────┐
         ▼            ▼             ▼
       LOW         ELEVATED       HIGH/CRITICAL
         │            │             │
         ▼            ▼             ▼
      QUIET         CARD        PROTECTION
                                      │
                                      ▼
                                  CALL ENDS
                                      │
                                      ▼
                               DETAILED REPORT
```

---

# 16. UI Architecture

## Pre-call

```text
CALLER CARD
   ↓
Identity
Relationship
Initial risk
Reason for monitoring
```

## During call

```text
SMALL SECURITY STATE
       OR
ELEVATED WARNING
       OR
FULL-SCREEN PROTECTION
```

## After call

```text
INTERACTION REPORT
       ↓
Timeline
Evidence
Risk reasons
Actions
Recommendation
```

---

# 17. Post-Interaction Report

```text
CALL END
   ↓
FINALIZE EVENT TIMELINE
   ↓
FINAL RISK
   ↓
EXPLAINABILITY SUMMARY
   ↓
USER REPORT
```

Example timeline:

```text
10:42  Unknown caller detected
10:43  Call answered
10:44  Authority-style notification received
10:45  Suspicious link opened
10:46  New APK installed
10:47  New recipient selected
10:47  Payment action initiated
10:47  TrustMesh protection activated
```

---

# 18. Android Service / Adapter Layer

```text
ANDROID SYSTEM
      │
      ├── CallScreeningService
      │
      ├── NotificationListenerService
      │
      ├── BroadcastReceiver
      │      └── PACKAGE_ADDED / related events
      │
      ├── UsageStatsManager / UsageEvents
      │
      └── User-facing Activities / UI
              │
              ▼
        TRUSTMESH ADAPTERS
```

Important:

The adapters are capability-specific.

The architecture must not assume universal access to another app's private internal events.

---

# 19. Action Observation Matrix

| Signal / Action | Preferred Source | MVP Status |
|---|---|---|
| Incoming call metadata | CallScreeningService | YES |
| Outgoing call context | CallScreeningService where supported | YES / VERIFY |
| Notification content | NotificationListenerService | YES |
| Package installed | Package broadcast / package APIs | YES |
| App foreground context | UsageEvents where available | INVESTIGATE |
| Suspicious URL opened | system/app-specific signals | LIMITED |
| Payment initiation | controlled demo / app integration | SIMULATE |
| OTP sharing | controlled demo / app integration | SIMULATE |
| Screen-sharing | app/system-specific signal | INVESTIGATE |
| Raw cellular call audio | privileged/OEM path | OUT OF SCOPE |
| Call transcription | requires audio | OUT OF SCOPE |

---

# 20. TrustMesh App Components

```text
TrustMeshApp
│
├── Call
│   └── CallScreeningService
│
├── Notifications
│   └── NotificationListenerService
│
├── Packages
│   └── PackageEventReceiver
│
├── Usage
│   └── UsageContextProvider
│
├── Interaction
│   ├── InteractionManager
│   └── TemporalStateManager
│
├── Events
│   ├── SecurityEvent
│   ├── EventNormalizer
│   └── EventRepository
│
├── Intelligence
│   ├── IdentityEngine
│   ├── PsychologyEngine
│   ├── IntentEngine
│   └── AttackContextEngine
│
├── Risk
│   ├── EvidenceFusion
│   ├── ConsequenceEngine
│   ├── RiskEngine
│   └── PolicyEngine
│
├── Firewall
│   ├── ActionFirewall
│   ├── ProtectionController
│   └── EscalationController
│
└── UI
    ├── CallerRiskCard
    ├── SecurityOverlay
    ├── ProtectionScreen
    ├── InteractionTimeline
    └── IncidentReport
```

---

# 21. Data Flow Example

## Digital Arrest / Financial Scam

```text
UNKNOWN CALL
      │
      ▼
CALL SCREENING
      │
      ▼
Interaction TM-7821
      │
      ▼
USER ANSWERS
      │
      ▼
MONITORING WINDOW
      │
      ├─────────────► Notification
      │                  │
      │                  ▼
      │              "Account under investigation"
      │                  │
      │                  ▼
      │             AUTHORITY + FEAR
      │
      ├─────────────► Suspicious Link
      │                  │
      │                  ▼
      │             LINK EVENT
      │
      ├─────────────► APK Installation
      │                  │
      │                  ▼
      │             ATTACK-CHAIN SIGNAL
      │
      └─────────────► Payment Action
                         │
                         ▼
                 ₹50,000 / new recipient
                         │
                         ▼
                    RISK ENGINE
                         │
                         ▼
                      CRITICAL
                         │
                         ▼
                  ACTION FIREWALL
                         │
                         ▼
                 FULL-SCREEN ALERT
                         │
                         ▼
                    CALL ENDS
                         │
                         ▼
                INTERACTION REPORT
```

---

# 22. Privacy Data Flow

```text
ANDROID EVENT
     ↓
LOCAL PARSER
     ↓
MINIMAL SECURITY EVENT
     ↓
TEMPORAL CONTEXT
     ↓
RISK DECISION
     ↓
RAW DATA EXPIRY
```

---

# 23. No-Audio Architecture Boundary

```text
                     AUDIO
                       │
                       X
                       │
                NOT USED IN MVP
                       │

CALL METADATA ─────────┐
NOTIFICATIONS ─────────┤
PACKAGE EVENTS ────────┤
USAGE/CONTEXT ─────────┤
ACTION SIGNALS ────────┤
IDENTITY ──────────────┤
                       ▼
                 TRUSTMESH CORE
                       │
                       ▼
                   RISK ENGINE
                       │
                       ▼
               ACTION FIREWALL
```

This is intentional.

---

# 24. Architecture Boundaries

## TrustMesh owns

- event correlation
- intelligence
- risk
- consequence
- policy
- protection UI
- interaction report

## Android owns

- cellular call transport
- default dialer call UI
- system call state
- notification delivery
- package lifecycle

## Other apps own

- their private internal workflows
- their private data
- their own transaction mechanisms

TrustMesh must respect those boundaries.

---

# 25. Implementation Sequence

```text
PHASE 0
Android capability audit
      ↓
PHASE 1
TrustMesh app shell
      ↓
PHASE 2
CallScreeningService
      ↓
PHASE 3
Notification intelligence
      ↓
PHASE 4
Package + context events
      ↓
PHASE 5
Identity / Psychology / Intent
      ↓
PHASE 6
Risk + Consequence
      ↓
PHASE 7
Action Firewall
      ↓
PHASE 8
Post-call report + final demo
```

---

# 26. Critical Success Criteria

The system is successful when this sequence works:

```text
UNKNOWN CALL
     ↓
TRUSTMESH CREATES INTERACTION
     ↓
USER ANSWERS
     ↓
RELATED EVENTS ARE CORRELATED
     ↓
INTENT / PSYCHOLOGY / IDENTITY ARE EXTRACTED
     ↓
CONSEQUENCE IS ASSESSED
     ↓
RISK ESCALATES
     ↓
ACTION FIREWALL INTERVENES
     ↓
USER UNDERSTANDS WHY
     ↓
POST-CALL REPORT GENERATED
```

The system does **not** require call audio to satisfy this core demonstration.

---

# 27. Phase 0 Audit Deliverables

Before implementation, produce:

```text
1. Android capability matrix
2. CallScreening integration design
3. Notification access design
4. Package-event design
5. Usage-event feasibility
6. Overlay/UI feasibility
7. Action-observation matrix
8. Permission/role matrix
9. Exact target Android compatibility
10. Exact TrustMesh project/module structure
```

No coding should begin until these are understood.

---

# 28. Final Architecture Principle

> **TrustMesh does not need to understand every word spoken during a call to protect the user. It needs to recognize when an interaction is escalating toward a dangerous consequence and intervene before that consequence occurs.**

The core system is therefore:

```text
CALL
  ↓
CONTEXT
  ↓
EVENTS
  ↓
INTELLIGENCE
  ↓
RISK
  ↓
CONSEQUENCE
  ↓
ACTION FIREWALL
  ↓
PROTECTION
  ↓
EXPLANATION
```

---

# 29. Out of Scope

```text
Audio recording                  OUT
Cellular call PCM                OUT
STT                               OUT
Voice cloning detection           OUT
VoIP media                        OUT
Fossify dependency                OUT
Custom dialer                     OUT
Universal app-action interception OUT
Real bank API control             OUT for MVP
Legal adjudication                OUT
```

---

# 30. MVP End State

```text
                    TRUSTMESH
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
        PRE-CALL             ACTIVE CALL
        CALLER RISK           MONITORING
             │                   │
             └─────────┬─────────┘
                       ▼
                 CONTEXT GRAPH
                       │
                       ▼
                  INTELLIGENCE
                       │
                       ▼
                    RISK
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
        ALLOW        VERIFY      PROTECT
                                     │
                           ┌─────────┴─────────┐
                           ▼                   ▼
                       FULLSCREEN           REPORT
                         ALERT              AFTER CALL
```

**This is the architecture we should implement.**
