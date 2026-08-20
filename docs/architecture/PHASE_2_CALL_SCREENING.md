# Phase 2: Real Call Screening Foundation

## 1. Objective
Establish the foundational Android `CallScreeningService` connection, extracting minimal call metadata to create a normalized `SecurityEvent`, and converting it to an `Interaction` for the UI, without capturing or processing any call audio.

## 2. Android CallScreeningService Integration
Implemented `TrustMeshCallScreeningService` extending `CallScreeningService`.
Registered in `AndroidManifest.xml` with `BIND_SCREENING_SERVICE` and the appropriate intent filter.

## 3. Role Acquisition
Implemented `RoleManagerHelper` to check for and request `ROLE_CALL_SCREENING`. The request is initiated explicitly by the user via the Settings screen.

## 4. Metadata Available
Android exposes standard details through `Call.Details`. For this phase, we extract the caller handle (number), creation time, and direction (incoming). The initial assumption for any unknown number is strictly `UNKNOWN`.

## 5. SecurityEvent Model
Created a pure architecture model (`SecurityEvent`) representing events inside TrustMesh. Type is set to `INCOMING_CALL` and source to `CALL_SCREENING_SERVICE`.

## 6. Event Normalization
Implemented `EventNormalizer` to convert Android-specific `Call.Details` into our decoupled `SecurityEvent`.

## 7. Interaction Creation
`InteractionManager` processes normalized events, maps them to an `Interaction` object with an interaction ID, and pushes them to the in-memory UI state flow.

## 8. Persistence Approach
For Phase 2, a lightweight in-memory `StateFlow` backed by `InteractionManager` and `EventRepository` provides immediate persistence sufficient for UI rendering. Local database persistence (e.g., Room) will be added in a future phase.

## 9. UI Integration
Updated `HomeScreen`, `HistoryScreen`, and `ReportScreen` to observe the real `InteractionManager` state flow. The prototype sample data has been replaced with the live data feed.

## 10. Default Screening Policy
The fallback and primary response is explicitly ALLOW (`setDisallowCall(false)`, `setRejectCall(false)`). TrustMesh acts in a purely observational capacity.

## 11. Privacy Boundaries
Zero audio recording or analysis is performed. No external analytics, cloud databases, or extra permissions (`RECORD_AUDIO`) have been added. Everything is processed and stored locally.

## 12. Testing Procedure
1. Enable Call Protection in Settings and grant the Call Screening role.
2. Confirm Settings shows Active status.
3. Simulate/place an incoming call.
4. Verify the incoming call is not blocked and no audio is requested.
5. Verify an Interaction is created and visible in the Home and History screens.
6. Verify the detailed Interaction report timeline.

*Status: VERIFIED ON PHYSICAL DEVICE*

## 13. Known Android Limitations
Call Screening relies on the OS correctly binding to the designated Role holder. Some OEM variations might alter timing. Full metadata (such as exact carrier verification) depends on the API level and carrier support.

## 14. Future Integration Points
- Implement full `RiskEngine` to replace the initial `LOW` state.
- Wire in `NotificationListenerService`.
- Establish permanent local storage using Room.
