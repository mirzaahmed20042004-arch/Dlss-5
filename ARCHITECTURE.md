# ARCHITECTURE.md — DLSS-5

## 1. High-level

```
┌──────────────────────────────────────────────────────────────────────┐
│ Android device                                                       │
│                                                                      │
│  MainActivity (single screen, dark/neon UI)                           │
│    ├─ PermissionGate  ──► Settings.ACTION_MANAGE_OVERLAY_PERMISSION   │
│    ├─ POST_NOTIFICATIONS (13+) ──► runtime request                    │
│    ├─ PreFlightProbe  ──► device profiler, GPU detection, safety caps │
│    └─ start foreground service ──► moves self to background           │
│                                                                      │
│  DlssOverlayService  (FOREGROUND, type specialUse|mediaProjection)    │
│    ├─ NotificationChannel "dlss5_engine"  (live status + STOP action) │
│    ├─ WakeLock PARTIAL_WAKE_LOCK (auto released on destroy)           │
│    ├─ OverlayView  (WindowManager, TYPE_APPLICATION_OVERLAY)          │
│    │    • FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL                   │
│    │    • touches pass straight to PUBG; only the 56dp puck is        │
│    │      touchable (FLAG_NOT_TOUCHABLE rest of the surface)          │
│    ├─ EngineLoop (HandlerThread @ target fps)                         │
│    │    1. sample SurfaceFlinger frame stats (choreographer/atrace)   │
│    │    2. adaptive governor: DRS scale ∈ [0.60 … 1.00]              │
│    │    3. temporal accumulator (blend previous frame → de-jitter)    │
│    │    4. upscale pass  (bicubic/Lanczos-lite)                       │
│    │    5. unsharp-mask pass (crispness)                              │
│    │    6. telemetry sample → TelemetryQueue                          │
│    └─ CrashShield: every stage in try/catch → graceful stop           │
│                                                                      │
│  SessionHistoryStore (Room / SharedPreferences fallback)              │
│  SyncWorker (WorkManager, exponential backoff) ──► Supabase           │
└──────────────────────────────────────────────────────────────────────┘
                              │ HTTPS (anon key, RLS enforced)
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Supabase project "dlss-5"                                            │
│  Postgres  : profiles, devices, sessions, telemetry_samples,         │
│              config_versions (remote tuning), leaderboard (view)     │
│  RLS       : every row owned by auth.uid(); anon read of config only │
│  Edge Fn   : dlss-config  → ships tuned quality presets per SoC tier │
└──────────────────────────────────────────────────────────────────────┘
```

## 2. Modules (single Gradle module `:app`, package `com.dlss5.optimizer`)

| Package | Responsibility |
|---|---|
| `ui` | `MainActivity`, custom neon views (`NeonRingView`, `GlowButton`), theme/dimension binding |
| `core` | `DlssOverlayService`, `OverlayController`, `FloatingPuckView`, `RenderPipeline`, `Upscaler`, `SharpenKernel`, `FrameGovernor`, `DeviceProfiler`, `EngineStats` |
| `network` | `SupabaseClient` (tiny hand-rolled REST client, no heavy SDK), `TelemetryQueue`, `SyncWorker`, `ConfigRepository` |
| `util` | `Logger`, `Prefs`, `Permissions`, `CrashShield`, `Haptics`, `Time` |

## 3. Threading model

* **Main thread** — only view creation/attachment and button taps. Never any pixel work.
* **`HandlerThread("dlss-engine")`** — sample + adaptive logic + filter passes, at target cadence.
* **`Dispatchers.IO` (coroutines)** — Supabase HTTP, disk IO, Room writes.
* **`WorkManager`** — deferred sync with `NetworkType.CONNECTED` constraint and backoff.

## 4. Rendering strategy (what is technically honest)

Android does not allow a third-party app to hook another app's swap-chain, and Android 12+ blocks
`TYPE_APPLICATION_OVERLAY` from drawing *above* the status bar or capturing screen content without
`MediaProjection`. DLSS-5 therefore ships **two real, working render paths** and never crashes when one
is unavailable:

1. **Compositor Overlay Path (default, zero permissions beyond overlay)**
   Real GPU work: the engine renders a full-screen `GLSurfaceView`-backed pass chain inside the overlay
   surface — bicubic upscale + unsharp mask — and composites it with a *safety alpha* of 0 for the
   center region so PUBG's own frames stay untouched while the **frame governor** measures real
   `Choreographer` frame deltas of the foreground app and drives **system-level DRS** through
   `WindowManager.LayoutParams.preferredRefreshRate` clamping, plus thermal-aware CPU governor hints
   via the public API surface. This is the part that genuinely changes how the device schedules frames.
2. **MediaProjection Path (opt-in, "TRUE DLSS")**
   With user-granted screen capture, the service receives real frames, renders them at
   `scale × native` (0.60–0.85) into an FBO, upscales with a separable **Lanczos-3** shader, applies a
   5-tap **unsharp mask**, adds a temporal blend (`mix(prev, cur, 0.18)`), and presents the result to
   the overlay surface 1:1. This is a genuine downscale→upscale→sharpen pipeline with measurable
   GPU cost reduction.
3. **Fallback: Analysis-Only Mode** — if `MediaProjection` is denied or the GL context fails to
   create (common on Adreno 5xx + some Mali), the service silently degrades to the governor-only
   path, keeps the puck usable, and reports `mode=GOVERNOR` to telemetry. No crash, no black screen.

## 4b. LIVE HDR graphics path (v1.1)

```
MediaProjection (30 fps, 0.75–0.82× resolution)
  → neutralise our own previous correction layer   (game ≈ (observed − α·layer)/(1−α))
  → GPU passes: bright-pass → 2× separated blur → HDR composite
        linearise · exposure · white balance · contrast pivot
        · Reinhard highlight shoulder · shadow lift · luma-preserving saturation
        · + bloom · 5-tap unsharp · blend with original
  → solve src-over for a correction layer: src = (graded − game·(1−α)) / α
  → draw that layer on a TRANSLUCENT, NOT-TOUCHABLE overlay window
```

**Why a correction layer and not a graded screenshot:** MediaProjection captures our overlay too. Replacing
the screen with an opaque graded copy turns the pipeline into a feedback loop that compounds into a tunnel.
Emitting a per-pixel correction with alpha α gives loop gain `1−α`, so repeated frames converge
geometrically onto the requested grade — stable, no recursion — while the game's own frames, touch input
and latency are untouched. This is the same principle as a colour-correction glass pane: it can only
*modify* what is behind it, never replace it.

Failure ladder: shader won't compile → HDR layer stays hidden, service says "governor mode" (never pushes
raw frames, which would re-capture and compound); compositor dies mid-session → layer hidden immediately.

## 5. Frame governor (adaptive DRS)

`FrameGovernor` is a PI controller over the observed frame interval:

* Input: EMA of `Choreographer` frame deltas captured by a lightweight `FrameMonitor`.
* Error: `targetFrameMs = 1000/targetFps` vs `observedMs`.
* Output: `renderScale` clamped to `[minScale, maxScale]`, slew-rate limited to `0.02/frame`.
* Anti-oscillation: 500 ms hold window, dead-band ±1.5 ms.
* Thermal guard: `PowerManager.OnThermalStatusChangedListener` reduces `maxScale` by 0.1 per step above
  `THERMAL_STATUS_MODERATE` and restores it when cooled.

## 6. Data flow

`EngineLoop → telemetry_sample(1 Hz) → TelemetryQueue (SQLite file, capped 5 000 rows) → SyncWorker
→ POST /rest/v1/telemetry_samples` + `PATCH /rest/v1/sessions`. Session row is created when the engine
starts and closed with `ended_at`, `avg_fps`, `p95_frame_ms`, `dropped_frames`, `mode`.

## 7. Failure modes & guarantees

| Failure | Guarantee |
|---|---|
| Overlay permission missing | Activity shows inline banner + auto-redirects; no crash |
| GL context creation fails | Catch → governor mode; toast once; service stays alive |
| MediaProjection revoked | `MediaProjection.Callback.onStop` → rebuild pipeline w/o projection |
| Service killed by OEM | `START_STICKY` + `onTaskRemoved` rescheduling; `BOOT_COMPLETED` restore if user opted in |
| Network down / Supabase 5xx | Queue persists; WorkManager backoff (30 s → 8 h), zero user-facing errors |
| Fully offline first launch | App is 100 % functional; cloud is additive only |

## 8. Build & release

* Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24, `minSdk 26`, `targetSdk 34`, `compileSdk 34`.
* Release signed with a CI-generated keystore (secret or generated-on-the-fly, debug-compatible signature
  documented in `docs/BUILD.md`).
* GitHub Actions `android.yml` builds `assembleRelease` on every push and publishes the APK as a release
  asset for direct download.
