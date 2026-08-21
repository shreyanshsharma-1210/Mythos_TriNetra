# TRINETRA 3.0 — RISK INTELLIGENCE V2

## Overview
This document outlines the architecture and deterministic behavior of the upgraded Risk Intelligence Engine V2 (Phase 7+). The system transitions from simple keyword matching to a Context-Aware Scam Detection system by introducing multi-dimensional signals, event correlation, and temporal intelligence.

### Privacy First
- 100% Local Processing.
- No cloud dependencies or external ML APIs.
- No raw SMS content logged or uploaded.

---

## 1. Signal Taxonomy (`ScamSignalType`)
Signals are classified into broad behavioral categories extracted via `SmsIntentClassifier` and `UrlAnalyzer`.

- **IDENTITY**: `UNKNOWN_CALLER`, `UNRESOLVED_CALLER`, `KNOWN_CONTACT`, `TRUSTED_CALLER`
- **FINANCIAL**: `BANKING`, `UPI`, `PAYMENT`, `TRANSACTION`, `DEBIT`, `CREDIT`, `REFUND`, `REWARD`, `CASHBACK`, `ACCOUNT_SUSPENSION`
- **AUTHENTICATION**: `OTP`, `VERIFICATION_CODE`, `LOGIN_CODE`, `SECURITY_CODE`, `PASSWORD_RESET`, `ACCOUNT_VERIFICATION`
- **TELECOM**: `KYC_EXPIRY`, `SIM_BLOCK`, `MOBILE_DISCONNECTION`, `NUMBER_SUSPENSION`, `TRAI_IMPERSONATION`, `DOT_IMPERSONATION`
- **UTILITY**: `ELECTRICITY_BILL`, `GAS_CONNECTION`, `WATER_BILL`, `BILL_DISCONNECTION`
- **DELIVERY**: `PARCEL`, `COURIER`, `DELIVERY_FAILURE`, `CUSTOMS`, `PACKAGE_HOLD`
- **GOVERNMENT**: `GOVERNMENT_IMPERSONATION`, `POLICE_IMPERSONATION`, `TRAFFIC_CHALLAN`, `ECHALLAN`, `COURT_NOTICE`, `TAX_NOTICE`
- **REMOTE_ACCESS**: `APK_INSTALL_REQUEST`, `UNKNOWN_APP_INSTALL`, `REMOTE_ACCESS_APP`, `SCREEN_SHARING_REQUEST`
- **URGENCY**: `IMMEDIATE_ACTION`, `DEADLINE`, `DISCONNECTION_THREAT`, `ACCOUNT_BLOCK_THREAT`, `LEGAL_THREAT`
- **SOCIAL_ENGINEERING**: `CALL_THIS_NUMBER`, `CONTACT_AGENT`, `SHARE_DETAILS`, `SHARE_OTP`, `SHARE_PIN`, `SHARE_PASSWORD`, `SEND_MONEY`, `UPI_REQUEST`, `REMOTE_SUPPORT`
- **LINK**: `SUSPICIOUS_URL`, `SHORTENED_URL`, `IP_ADDRESS_URL`, `NON_EXPECTED_DOMAIN`, `URL_WITH_URGENCY`

---

## 2. Risk Scoring Model
The system uses an additive scoring model with bounding constraints (Clamped to 0..100).

**Score Formula:**
`Final Risk = BaseIdentity + ContentSignals + BehaviorSignals + CorrelationBonus - TrustAdjustment`

### Thresholds
- **LOW:** 0-24
- **ELEVATED:** 25-49
- **HIGH:** 50-74
- **CRITICAL:** 75-100

### Base Weights (Examples)
- **Identity:** Unknown Caller (+10)
- **Authentication:** Normal OTP (+8)
- **Financial Context:** Banking/UPI (+15)
- **Remote Access Threat:** APK/AnyDesk Request (+30)
- **Social Engineering:** Request to Share OTP (+25)
- **Trust Adjustments:** Known Contact (-20)

---

## 3. Correlation Rules
Keywords in isolation do not trigger CRITICAL risk. It requires the confluence of independent signals.

**Examples of Correlation:**
1. `OTP (8) + Unknown Caller (10) = ELEVATED (18)`
2. `OTP (8) + Urgent Account Threat (25) + Unknown Caller (10) = HIGH (43)`
3. `OTP (8) + Financial Context (15) + Share Request (25) + Unknown Caller (10) + Active Call Correlation (+20) = CRITICAL (78)`

---

## 4. Temporal Rules (Time Windows)
- `CALL -> SMS`: Correlated within a sliding window (e.g., 5 minutes) via `RiskEngineConfig.RELATED_EVENT_WINDOW_MS`.
- `CALL -> OTP`: Rapid succession (< 60s) receives higher sequence weights in `AttackContextEngine`.

---

## 5. False-Positive Protections (Negative Evidence)
To reduce false positives and alert fatigue:
1. **Known Contacts:** Presence of a known contact reduces base risk drastically, suppressing minor heuristic hits.
2. **Contextual OTPs:** A lone OTP without a corresponding active unknown call or urgent social engineering language generates minimal risk.

---

## 6. Attack Contexts (`ContextType`)
When combinations exceed thresholds, the engine infers high-level contexts:
- `OTP_THEFT`
- `KYC_SCAM`
- `BANKING_SOCIAL_ENGINEERING`
- `TELECOM_IMPERSONATION`
- `UTILITY_SCAM`
- `PARCEL_SCAM`
- `CHALLAN_SCAM`
- `REMOTE_ACCESS_SCAM`
- `GOVERNMENT_IMPERSONATION`
- `REWARD_SCAM`

---

## 7. Explainability
The `RiskAssessment` natively produces an `explanation` array mapped directly from the individual `RiskFactor`s and `AttackContext` triggered, feeding directly into the Post-Call Report Summary without LLM hallucination.
