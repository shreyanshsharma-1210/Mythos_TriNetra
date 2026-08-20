# Phase 1: Application Shell and UI Foundation

## 1. Project Structure
The project has been configured with a standard Android modular structure:
- `com.trustmesh.app.core`: Future engine and risk processing logic
- `com.trustmesh.app.sensors`: Android-specific interaction detectors
- `com.trustmesh.app.firewall`: Protection controllers
- `com.trustmesh.app.interaction`: State managers and interaction representation
- `com.trustmesh.app.ui`: Compose-based UI elements

## 2. Architectural Decisions
- **UI:** Jetpack Compose (Material 3 style adapted for TrustMesh dark theme)
- **Navigation:** Jetpack Navigation Compose
- **State:** Deterministic mock data for UI prototyping in Phase 1
- **Gradle:** Kotlin DSL (`build.gradle.kts`), single-module structure for now

## 3. UI State Model
- **Risk Levels:** `LOW`, `ELEVATED`, `HIGH`, `CRITICAL`
- Currently driven by a dummy `Interaction` data model.

## 4. Known Limitations & Future Integration
- **Actual Sensors:** `CallScreeningServiceStub` and others are intentionally blank placeholders.
- **Overlays:** `ProtectionScreen` is currently just a composable; WindowManager behavior is omitted.
- **Real-Time Data:** All screens rely on hardcoded `sampleInteractions`.
