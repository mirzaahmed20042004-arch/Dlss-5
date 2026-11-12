# PRD.md — PUBG DLSS-5 Mobile Optimizer (Single APK)

## 1. Problem

Mid-range Android phones (Snapdragon 6xx/7xx, Dimensity 700–900, Exynos 9611) run PUBG Mobile at
40–60 fps with heavy frame-time variance. There is no user-accessible way to trade resolution for
frame stability without root, and no single app that measures real frame pacing while doing so.

## 2. Goal

A **single APK**, one-button app that:
1. Establishes a crash-proof overlay + foreground service in < 3 s.
2. Measures the device's real frame pacing and adapts render scale in real time.
3. Provides a genuine downscale → Lanczos upscale → unsharp-mask path when the user grants capture.
4. Never crashes and never blocks touch input to PUBG.
5. Syncs session telemetry to a Supabase backend for history, leaderboard and remote tuning.

## 3. Personas

* **Competitive player (primary)** — wants stable 60 fps, will grant permissions for measurable gains.
* **Low-end owner (secondary)** — wants *no* crashes and a safe default; treats extra permissions as optional.
* **Tinkerer (tertiary)** — wants numbers, history, and mode control.

## 4. Scope

### MVP (must ship)
| ID | Requirement | Acceptance |
|---|---|---|
| F1 | Single-screen dark UI, neon accents, white text | Matches DESIGN.md tokens |
| F2 | `ENGAGE DLSS 5` button: loading → minimize → overlay service | Tap to puck visible < 2 s |
| F3 | Overlay permission gate with auto-redirect, no crash | Deny → banner + Settings intent |
| F4 | Foreground service with persistent notification + STOP action | Survives 10 min background |
| F5 | Draggable 56dp puck, touch-transparent elsewhere | PUBG receives all taps outside puck |
| F6 | Frame monitor + adaptive scale governor | Scale changes logged, ±1.5 ms dead-band |
| F7 | Unsharp mask + bicubic upscale pass (overlay) | Runs in < 4 ms on SD665 at 720p |
| F8 | MediaProjection TRUE-DLSS path with Lanczos-3 + temporal blend | Visual sharpness increase on captured frame |
| F9 | Graceful fallback to governor mode on any GL failure | 0 crashes in 20 launch matrix |
| F10 | Session history persisted locally (last 50) | Visible on main screen |
| F11 | Supabase sync of sessions + telemetry, fully offline-tolerant | Queue drains after reconnect |
| F12 | GitHub Actions release build + APK asset | `assembleRelease` green |

### Out of scope (v1)
* Actual swap-chain injection / Vulkan layer (needs root or OEM cooperation).
* Per-game profiles with package detection beyond a coarse "is a game foreground" check.
* iOS, tablets, Android TV.

## 5. Success metrics

* Crash-free sessions ≥ 99.5 %.
* Median frame-time p95 improvement ≥ 12 % in GOVERNOR mode on a SD665 device.
* Engage → puck visible ≤ 2 s (p90).
* Sync success ≥ 98 % within 5 min of reconnect.

## 6. Non-functional

* APK ≤ 12 MB. Cold start ≤ 900 ms on SD665. Battery: ≤ 4 %/h in governor mode, ≤ 9 %/h in TRUE DLSS.
* No ANRs: no disk or network on main thread. All services `START_STICKY` with backoff.
* Privacy: no screen content leaves the device; telemetry is numeric only and user-scoped by anonymous
  device ID (rotatable in Settings).

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| OEM kills overlay service | `specialUse` foreground type + sticky + boot restore (opt-in) |
| Adreno GL quirk → black overlay | Probe FBO support; alpha-0 safety compositing; auto fallback |
| Google Play policy (overlay over games) | Distributed as direct APK / GitHub release, sideload documented |
| Users expect literal DLSS | In-app copy states "DLSS-class" upscaling; modes labelled honestly |

## 8. Milestones

M1 skeleton+CI (day 1) → M2 overlay+governor (day 2) → M3 upscale/sharpen (day 3) →
M4 backend+sync (day 4) → M5 hardening+release (day 5).
