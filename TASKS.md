# TASKS.md — DLSS-5 build board

Status legend: `[x]` done · `[~]` in progress · `[ ]` open

## Phase 0 — Repo & build system
- [x] Gradle wrapper, `settings.gradle.kts`, root + app build files
- [x] Version catalog-free (inline versions for CI determinism)
- [x] GitHub Actions release pipeline (`.github/workflows/android.yml`)

## Phase 1 — Design system & assets
- [x] `colors.xml`, `themes.xml` (pure black / neon green / white)
- [x] Conceptual logo → `docs/logo/dlss5-logo-512.png`, adaptive icon + legacy mipmaps
- [x] `NeonRingView`, `GlowButton` custom views, drawables

## Phase 2 — Core engine
- [x] `DeviceProfiler` (SoC tier, GPU string, refresh rate, thermal)
- [x] `FrameMonitor` (Choreographer deltas, p95, dropped frames)
- [x] `FrameGovernor` (PI controller, slew limit, dead-band, thermal clamp)
- [x] `Upscaler` (bicubic Catmull-Rom + Lanczos-3 kernels, JVM-testable)
- [x] `SharpenKernel` (5-tap unsharp mask)
- [x] `TemporalBlender` (EMA frame blend)
- [x] `RenderPipeline` (GL passes, try/catch, FBO lifecycle, fallback flag)

## Phase 3 — Overlay & service
- [x] `DlssOverlayService` (foreground, notification, wakelock, START_STICKY)
- [x] `OverlayController` (WindowManager add/remove, flags, insets, rotation)
- [x] `FloatingPuckView` (drag, snap, long-press menu, accessibility)
- [x] Touch transparency verification (flags + `setTouchableRegion` note)
- [x] `MediaProjection` opt-in capture path

## Phase 4 — UI
- [x] `MainActivity` (permission gate, engage flow, live stats, history, safe mode)
- [x] `activity_main.xml` + neon drawables
- [x] Boot receiver (opt-in restore), notification actions

## Phase 5 — Backend (Supabase)
- [x] Project `dlss-5` provisioned (`dpafgmtrnotdctriwplb`, ap-south-1)
- [x] Schema: devices, sessions, telemetry_samples, config_versions + RLS
- [x] `dlss-config` edge function (SoC-tier presets)
- [x] `SupabaseClient`, `TelemetryQueue`, `SyncWorker`, `ConfigRepository`

## Phase 6b — LIVE HDR graphics (v1.1)
- [x] `HdrCompositor` (bright-pass, separated blur, tone-map, bloom, vibrance, unsharp)
- [x] Correction-layer compositing (convergent feedback) + capture-side neutralisation
- [x] `HdrSurfaceView` translucent live layer + preset badge
- [x] Five PUBG presets (EXTREME HDR / CINEMATIC / VISIBILITY / GAME VIVID / NATIVE) + blend control
- [x] `HDR` mode chip, live preset switcher, puck menu actions (HDR LAYER, HDR PRESET)
- [x] Backend: `sessions.hdr_preset`, per-tier HDR tuning, `hdr_performance` view
- [x] Unit tests for preset safety/cycling

## Phase 6 — Hardening & release
- [x] Crash shield on all entry points, ANR-free IO
- [x] Unit tests for governor/upscaler/queue
- [x] CI green `assembleRelease`, APK attached to GitHub Release
- [x] `README.md` + `docs/BUILD.md` + `docs/TESTING.md` + `docs/PRIVACY.md`
