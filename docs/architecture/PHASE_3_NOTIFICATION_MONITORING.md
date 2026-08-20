# Phase 3: Notification Monitoring Foundation

## 1. Objective
Establish the second real security signal for TrustMesh: Android Notifications. Connect `NotificationListenerService` directly to the `SecurityEvent` and `InteractionManager` pipeline without modifying the existing Phase 2 call pipeline, maintaining strict observation-only logic and zero data exfiltration.

## 2. NotificationListenerService
Implemented `TrustMeshNotificationListenerService` extending `NotificationListenerService`.
Registered in `AndroidManifest.xml` with `BIND_NOTIFICATION_LISTENER_SERVICE`. 

## 3. Notification Access
Implemented `NotificationAccessHelper` to check for active listener packages and explicitly direct the user to Android's Notification Access settings when setup is requested.

## 4. Metadata Extraction
Android `StatusBarNotification` objects are observed in `onNotificationPosted()`. Safe metadata is extracted, including package name, title, text, and timestamp, handling nulls without crashing.

## 5. NotificationNormalizer
Implemented `NotificationNormalizer` to isolate Android-specific UI classes (`StatusBarNotification`, `Notification`, `Bundle`) from the core architecture, converting them cleanly into `SecurityEvent` representations.

## 6. SecurityEvent Extension
Extended `EventType` with `NOTIFICATION_POSTED` and `EventSource` with `NOTIFICATION_LISTENER_SERVICE`, maintaining a single canonical `SecurityEvent` model that accepts both Call and Notification inputs.

## 7. Repository Integration
Both calls and notifications route securely through the existing in-memory `EventRepository`. 

## 8. Interaction Integration
Extended `InteractionManager` to intercept `NOTIFICATION_POSTED` events and represent them locally as lightweight `Interaction` objects with a default `LOW` risk state. Cross-correlation is intentionally excluded from this phase.

## 9. UI Integration
- **Home UI**: Renders a standard interaction card utilizing the application package name instead of a caller handle.
- **History UI**: Tracks both `INCOMING_CALL` and `NOTIFICATION_POSTED` histories side by side.
- **Report UI**: Outlines timeline events, signals (App Name, Title available, Content available), and the exact observation time.
- **Settings UI**: Refined to correctly reflect Notification Monitoring's active/pending states via `NotificationAccessHelper`.
- **Onboarding UI**: Expanded to clarify that TrustMesh can observe notifications but specifically does NOT exfiltrate them.

## 10. Privacy Boundaries
All implementations remain firmly local-first. Notification content is not sent to external APIs, logged raw, or evaluated by cloud engines. The app requests no extraneous permissions (e.g. `RECORD_AUDIO`).

## 11. Lifecycle Handling
Safe integration handles `onListenerConnected()` and `onListenerDisconnected()` without application failure. Reconnections safely resume monitoring.

## 12. Performance Considerations
`onNotificationPosted` executes without synchronous processing delays (e.g., image loading, networking, or machine learning), directly converting the payload to memory.

## 13. Testing Procedure
1. Install TrustMesh, enable Notification Monitoring in Settings.
2. Confirm Settings UI transitions to ACTIVE.
3. Trigger a notification from a safe third-party application.
4. Verify the notification appears inside Home, History, and Report UI.
5. Verify call screening defaults (Phase 2) still function correctly alongside notifications.

## 14. Notification Identity & Deduplication
- **Multiple Callbacks**: Android's `NotificationListenerService` can emit multiple callbacks for a single notification as it undergoes state changes (e.g., progress updates, text changes). Without deduplication, this results in spamming identical events (such as multiple Google Dialer notifications during an ongoing call).
- **Identity Strategy**: TrustMesh identifies notifications using the Android-provided `StatusBarNotification.key`. This compound key ensures distinct tracking for different notifications from the same app, while correctly mapping repeated updates back to their original observation.
- **Updates vs. New Notifications**: A new notification (unseen key) results in a new Interaction being recorded. When an existing notification (seen key) receives an update, TrustMesh updates the underlying interaction's timeline, timestamp, and available evidence in-place rather than creating duplicate UI cards.
- **Dialer Notification Support**: Google Dialer notifications are deliberately NOT blacklisted. They contain essential context that will feed into future multi-signal risk models.
- **Separation of Events**: Currently, `INCOMING_CALL` and `NOTIFICATION_POSTED` events are independent streams. Call events bypass notification deduplication to guarantee recording. Future phases will introduce a Context Engine to intelligently group related call and dialer-notification observations into a single unified session.

## 15. Physical-Device Verification
NOT VERIFIED ON PHYSICAL DEVICE.

## 16. Known Limitations
- Storage is purely in-memory (to be replaced with Room in a future phase).
- Notification images/icons are not extracted or mapped.
- Interaction grouping/correlation relies on future AI architectures.

## 17. Future Integration Points
- EvidenceFusion and RiskEngine implementations will correlate Bank/Payment apps alongside unknown Call activities to compute complex dynamic risks.
- Notification grouping and session tracking based on conversation updates.
