# UI Architecture: TrustMesh

## 1. Visual Design Direction
- **Vibe:** Premium, calm, security-focused, minimal, modern.
- **Theme:** Dark-first.
- **Backgrounds:** `#0B0D10` (Base), `#15181D` (Surface), `#1D2128` (Elevated)

## 2. Typography
- Hierarchy uses `Typography.titleLarge`, `titleMedium`, `bodyLarge`, and `labelLarge`.
- Prioritizes clear numbers, high readability, and plain English risk descriptions.

## 3. Component Hierarchy
- **Top Bar:** `TrustMeshTopBar`
- **Lists:** `HomeScreen`, `HistoryScreen` mapping to `InteractionCard`
- **Detail:** `ReportScreen` mapping to `RiskBadge` and detailed text

## 4. Risk-State Visual Behavior
- **LOW:** `#4CAF50` (Green/Calm)
- **ELEVATED:** `#FFB300` (Amber)
- **HIGH:** `#FF5722` (Orange)
- **CRITICAL:** `#D50000` (Red) - Can commandeer the entire screen.

## 5. Accessibility Principles
- Sufficient contrast for all text over dark backgrounds.
- State not conveyed by color alone (`RiskBadge` has text).
