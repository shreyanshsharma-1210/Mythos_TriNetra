# TrustMesh 3.0 — Phase 9: Advanced Evidence Correlation

## Overview
Phase 9 transitions the TrustMesh Risk Engine from analyzing individual events in isolation to understanding security context across multiple correlated events. The core component introduced is the `AttackContextEngine`.

## Architecture

### 1. AttackContextEngine
The `AttackContextEngine` correlates incoming calls with recent security and financial notifications within a configurable time window (default 5 minutes). It operates deterministically using local metadata without audio or accessibility hooks.

### 2. Time Windows
- **Related Event Window**: Configured in `RiskEngineConfig.RELATED_EVENT_WINDOW_MS` (5 minutes by default).
- The engine searches for security notifications or suspicious calls that occurred prior to the current interaction within this window.

### 3. Intent Inference Logic
The `AttackContextEngine` categorizes interactions into intents (`InferredIntent`) using keyword-based heuristics on notification content:
- **OTP_THEFT**: Triggered if notifications contain keywords like "otp", "code", "verification", or "password".
- **FINANCIAL_FRAUD**: Triggered if notifications contain keywords like "debit", "credit", "bank", "account", "payment", "rupees", "inr", or "$".
- **UNKNOWN**: When correlation exists but no specific intent can be confidently inferred.

### 4. Integration with RiskEngine
The `RiskEngine` consumes the output of the `AttackContextEngine`. If a suspicious pattern is detected (e.g., an incoming call followed by an OTP notification), the engine:
1. Applies an additional risk weighting score (+20 points).
2. Generates an `AttackContext` containing the `InferredIntent` and a natural language explanation.
3. Attaches this `AttackContext` to the final `RiskAssessment`.

### 5. UI Updates
- **ProtectionOverlay**: Updated to extract and present `AttackContext` explanation and intent. Adaptive UI ensures non-intrusiveness while conveying clear context.
- **InteractionCard & ReportScreen**: History and reporting UI updated to show correlated events as a sequence, highlighting the `AttackContext` badges visually (e.g., "OTP THEFT", "FINANCIAL FRAUD") and displaying the explanation text.

## Security & Privacy Compliance
- **Zero Cloud**: No external network dependencies.
- **Zero Audio**: No audio recording or transcription.
- **No Accessibility Services**: Functions purely on `NotificationListenerService` and `CallScreeningService` metadata.
