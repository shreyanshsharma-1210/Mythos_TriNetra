# PHASE 0 AUDIT: TrustMesh 3.0 Capability Report

## 1. Environment Summary
- **OS:** Windows
- **Java:** OpenJDK 17.0.19 (Temurin)
- **SDK Path:** `C:\Users\Shreyansh\AppData\Local\Android\Sdk`

## 2. Android SDK Summary
- **Installed Platforms:** `android-34`, `android-36`, `android-36.1`
- **Installed Build Tools:** `34.0.0`, `36.0.0`, `36.1.0`, `37.0.0`
- **Recommended JDK:** Java 17

## 3. Recommended Android Configuration
- **minSdk:** `29` (Android 10) - Required for `RoleManager` (specifically `ROLE_CALL_SCREENING`) which handles call screening reliably and standardizes many modern privacy permissions.
- **targetSdk:** `34` (Android 14) - Matches the latest stable installed SDK platform and ensures compliance with recent Android security features.
- **compileSdk:** `34` - To support the latest APIs while targeting Android 14.
- **Kotlin:** `2.0.0` (or `1.9.24`)
- **AGP:** `8.4.x` or `8.5.x`

## 4. CallScreeningService Feasibility
- **Capabilities:** Can receive incoming call metadata (caller number), assess risk, and reject/silence the call or let it ring. No audio access.
- **Requirements:** 
  - Manifest declaration: `<service>` with `android.permission.BIND_SCREENING_SERVICE` and `<intent-filter>` for `android.telecom.CallScreeningService`.
  - Runtime: App must request and be granted `ROLE_CALL_SCREENING` via `RoleManager`.
- **Limitations:** Only works for incoming calls. Outgoing calls require `NEW_OUTGOING_CALL` broadcast (deprecated in API 29) or `CallRedirectionService` (API 29+).

## 5. NotificationListener Feasibility
- **Capabilities:** Can read incoming notifications, including sender and content, from any app (e.g., SMS, messaging apps).
- **Requirements:** 
  - Manifest declaration: `<service>` with `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`.
  - Runtime: User must explicitly grant "Device & app notifications" access in Settings.
- **Limitations:** Cannot read encrypted content before it hits the UI. User must trust the application significantly.

## 6. Package Event Feasibility
- **Capabilities:** Detect when apps are installed, updated, or removed.
- **Requirements:** 
  - Manifest receiver for `ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REPLACED`, `ACTION_PACKAGE_FULLY_REMOVED`.
  - Permission: `QUERY_ALL_PACKAGES` (API 30+) is required to see all apps, which has strict Google Play policies.
- **Limitations:** Broadcasts can be delayed by the system for battery optimization.

## 7. UsageStats/UsageEvents Feasibility
- **Capabilities:** Detect foreground application transitions and track app usage time.
- **Requirements:** 
  - `UsageStatsManager` API.
  - Permission: `PACKAGE_USAGE_STATS` (granted via Settings > Usage Access).
- **Limitations:** Events can be batched; not strictly real-time (can be delayed by seconds).

## 8. Security UI / Overlay Feasibility
- **Capabilities:** Draw floating warnings or full-screen protection overlays over other apps.
- **Requirements:**
  - Permission: `SYSTEM_ALERT_WINDOW`.
  - Implementation: Foreground service using `WindowManager` to add a view with `TYPE_APPLICATION_OVERLAY`.
- **Limitations:** Android 12+ restricts overlays on top of sensitive screens (like permissions dialogs or system settings).

## 9. Action Observation Matrix

| Signal | Preferred Android Source | Permission / Access | Android Limitation | MVP Feasibility | Implementation Recommendation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Incoming call metadata | `CallScreeningService` | `ROLE_CALL_SCREENING` | No outgoing calls | HIGH | Use `CallScreeningService` |
| Outgoing call context | `CallRedirectionService` | `ROLE_CALL_REDIRECTION` | API 29+ | HIGH | Use `CallRedirectionService` |
| Notification content | `NotificationListenerService` | Notification Access | Must be enabled in Settings | HIGH | Use `NotificationListenerService` |
| SMS notification/context | `NotificationListenerService` | Notification Access | Relies on SMS app posting it | HIGH | Extract from Notifications |
| Package installed | BroadcastReceiver | `QUERY_ALL_PACKAGES` | Play Store policy restricted | HIGH | Register manifest receiver |
| Package changed | BroadcastReceiver | `QUERY_ALL_PACKAGES` | Play Store policy restricted | HIGH | Register manifest receiver |
| Foreground application | `UsageStatsManager` | `PACKAGE_USAGE_STATS` | Events might be slightly delayed | MEDIUM | Poll `queryEvents` |
| Suspicious URL opened | AccessibilityService / Intents | `BIND_ACCESSIBILITY_SERVICE` | High friction, battery heavy | LIMITED | Use Accessibility only if strictly needed |
| Payment initiation | Notification / Accessibility | Notification Access | Depends on payment app UI | LIMITED | Infer from notifications |
| OTP sharing | NotificationListenerService | Notification Access | Reliable if in notification | HIGH | Regex on notifications |
| Screen sharing | MediaProjection callback | None direct | Difficult to detect system-wide | INVESTIGATE | Listen for screen record notifications |
| New recipient/payment | AccessibilityService | `BIND_ACCESSIBILITY_SERVICE` | Fragile UI scraping | SIMULATED | Out of scope for pure network/event |
| Raw cellular call audio | NONE | NONE | Strictly prohibited by OS | OUT OF SCOPE | Do not implement |
| Call transcription | NONE | NONE | Cannot get audio to transcribe | OUT OF SCOPE | Do not implement |

## 10. Permission / Role Matrix

| Capability | Android API | Permission/Role | User Action Required | Runtime/Settings Configuration | MVP Required? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Call Screening | `CallScreeningService` | `ROLE_CALL_SCREENING` | Yes | System Role Dialog | Yes |
| Notifications | `NotificationListenerService`| Notification Access | Yes | Settings > App Access | Yes |
| App Usage | `UsageStatsManager` | `PACKAGE_USAGE_STATS` | Yes | Settings > Usage Access | Yes |
| Overlays | `WindowManager` | `SYSTEM_ALERT_WINDOW` | Yes | Settings > Display over apps | Yes |
| Package Discovery| `PackageManager` | `QUERY_ALL_PACKAGES` | No | Manifest declaration | Yes |

## 11. Physical Device vs Emulator Testing Requirements
- **Emulator:** Good for UI, UsageStats, Overlays, Notifications.
- **Physical Device:** **Mandatory** for telephony testing (`CallScreeningService`), realistic background execution limits, and accurate `UsageStats` timing.

## 12. Known Android Restrictions
- Background Activity Starts are blocked (API 29+); must use full-screen intents or overlays.
- `CallScreeningService` strictly denies audio access.
- Google Play policies restrict `QUERY_ALL_PACKAGES` and Accessibility Services severely.

## 13. MVP Capability Boundaries
- TrustMesh will **only** correlate metadata (who is calling, what app is foreground, what notifications arrive).
- It will **not** inspect audio or perfectly intercept in-app actions (like clicking "send" in WhatsApp).

## 14. Recommended Phase 1 Project Structure
```
TrustMeshApp/
├── core/
│   ├── events/
│   │   ├── SecurityEvent
│   │   ├── EventNormalizer
│   │   └── EventRepository
│   ├── intelligence/
│   │   ├── IdentityEngine
│   │   ├── PsychologyEngine
│   │   ├── IntentEngine
│   │   └── AttackContextEngine
│   └── risk/
│       ├── EvidenceFusion
│       ├── ConsequenceEngine
│       ├── RiskEngine
│       └── PolicyEngine
├── sensors/
│   ├── call/
│   │   └── CallScreeningService
│   ├── notifications/
│   │   └── NotificationListenerService
│   ├── packages/
│   │   └── PackageEventReceiver
│   └── usage/
│       └── UsageContextProvider
├── firewall/
│   ├── ActionFirewall
│   ├── ProtectionController
│   └── EscalationController
├── interaction/
│   ├── InteractionManager
│   └── TemporalStateManager
└── ui/
    ├── CallerRiskCard
    ├── SecurityOverlay
    ├── ProtectionScreen
    ├── InteractionTimeline
    └── IncidentReport
```

## 15. Phase 0 Risks / Unknowns
- Latency of `UsageStatsManager` events may make real-time context slightly delayed.
- User friction during onboarding is extremely high (needs 4+ special permissions).

## 16. Final Go / No-Go Recommendation
**GO.** The Phase 1 environment is ready. The Android APIs for the required capabilities (excluding audio) are present and well-understood. Phase 1 implementation can safely begin.
