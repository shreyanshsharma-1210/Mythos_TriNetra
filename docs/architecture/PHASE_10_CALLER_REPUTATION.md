# Phase 10: Caller Reputation & External Identity Provider Foundation

## Overview
Phase 10 of TrustMesh 3.0 establishes a robust, provider-agnostic framework for caller reputation lookup and integration into the core intelligence engine. This phase focuses on extending the existing Phase 6 architecture, maintaining local-first privacy rules without hard-coding specific commercial providers.

## Key Principles Addressed
1. **Zero Audio:** No audio is recorded or processed.
2. **Local-First Privacy:** TrustMesh only looks up the incoming phone number (opt-in) without uploading the user's contacts.
3. **No Accessibility Service:** We rely on standard CallScreeningService.
4. **Provider-Agnostic:** Built an interface-driven abstraction (`ExternalCallerIdentityProvider`) to allow seamless plugging of future APIs.

## Key Components Implemented

### 1. `CallerReputation` Model
A data model containing details returned from an external provider (e.g., `ReputationLevel`, `CallerCategory`, `confidence`, and report metrics).

### 2. `ExternalCallerIdentityProvider` Abstraction
Defined the core contract for reputation lookup to keep the engine decoupled from concrete API implementations. Includes error handling, timeouts, and fallback logic to protect the critical call-screening path.

### 3. `PhoneNumberNormalizer` & `CallerReputationCache`
Ensures that phone number queries are consistent across locales (e.g., formatting to E.164) and introduces a short-lived LRU cache (`CallerReputationCache`) to minimize redundant network requests during rapid call bursts.

### 4. `ResolvedCaller` Entity
Replaced the direct `CallerIdentity` return on local lookup to wrap both a `CallerIdentity` and an optional `CallerReputation`. The local database remains the top-tier source, while external reputation acts as an enrichment layer.

### 5. `CompositeCallerIdentityResolver` Enhancements
Upgraded to merge local `CallerIdentity` with external `CallerReputation` lookups gracefully.

### 6. Room Database Persistence
Updated `InteractionEntity` and Room migrations (`version 1 -> 2`) to durably persist reputation fields (`repCategory`, `repLevel`, `repSpamReports`, `repFraudReports`) inside local application storage, fully preserving the architecture constraints implemented in Phase 8.

### 7. Overlay Integration
Refactored `ProtectionOverlay` and `ProtectionController` to pass down and visualize `CallerReputation` metrics natively in the existing overlay modes, mapping styles to reputation risk levels natively (e.g., coloring HIGH_RISK as red).

### 8. Mock Provider
Provided `MockExternalCallerIdentityProvider` to simulate multiple external source behavior (Spam, Business, Failure scenarios) for deterministic UI and Risk Engine testing without relying on network APIs.

## Next Steps
- Implement integration tests across the deterministic risk engine weighting for reputation metrics.
- Prepare a real external identity provider class implementing `ExternalCallerIdentityProvider` when business accounts/API credentials become available.
