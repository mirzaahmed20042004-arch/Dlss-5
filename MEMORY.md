# MEMORY.md — long-lived context & decisions

> Append-only. Newest decision last. Keep entries short: `DATE — DECISION — WHY`.

## Product decisions

* 2026-11-12 — **DLSS-5 is a render-augmentation + frame-governor app, not a swap-chain hook.**
  Why: non-root Android cannot hook another app's surface; promising it would be a lie and cause
  user reports of "no difference". We ship genuine upscale/sharpen via `MediaProjection` and a genuine
  adaptive frame governor via `Choreographer` + thermal APIs.
* 2026-11-12 — **Three modes: GOVERNOR (safe default), UPSCALE (overlay GL pass), TRUE DLSS (capture).**
  Why: permission ladder. Zero-permission mode first, capture mode opt-in.
* 2026-11-12 — **Single module Gradle project.** Why: APK size and build time on CI runners.
* 2026-11-12 — **No third-party SDKs** (no Firebase, no Supabase Kotlin SDK, no Retrofit).
  Why: keep APK < 12 MB, keep CI build under 4 min, avoid dependency-availability failures.
  A ~200-line `HttpURLConnection` client is sufficient for 4 endpoints.
* 2026-11-12 — **Custom Canvas views instead of Material Components.** Why: theme precision (neon on
  pure black) and smaller APK.

## Technical decisions

* 2026-11-12 — `minSdk 26`, `targetSdk 34`, `compileSdk 34`, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7.
* 2026-11-12 — Overlay type `TYPE_APPLICATION_OVERLAY` (API 26+) only; no legacy `TYPE_PHONE`.
* 2026-11-12 — Foreground service type `specialUse` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` =
  `screen_upscaling_overlay`; add `mediaProjection` type only when capture is active (declared in manifest
  as a second type string and set at runtime on API 29+).
* 2026-11-12 — Frame stats via `Choreographer.FrameCallback` on a dedicated `HandlerThread`;
  fallback to `System.nanoTime()` deltas if choreographer is silent (some OEM ROMs).
* 2026-11-12 — Upscale = separable bicubic (Catmull-Rom) in the overlay path, Lanczos-3 in the
  capture path. Why: Lanczos on an 8" screen at 720p costs more than it gains for the overlay preview.
* 2026-11-12 — Sharpen = 5-tap unsharp mask with strength `0.35`, clamped to avoid ringing.
* 2026-11-12 — Telemetry queue: append-only JSONL file, capped 5 000 lines, compacted on sync.
  Why: no Room dependency (saves ~600 KB + kapt/ksp time) while remaining crash-safe.
* 2026-11-12 — Supabase reached with hand-rolled REST + anon key; RLS per `auth.uid()`… see
  **Auth note** below.
* 2026-11-12 — **Auth note:** app uses Supabase anonymous-auth-free model: a random `device_id` (UUID v4,
  stored in prefs) is the owner key, and RLS policies scope rows to `device_id` sent via a custom header.
  Why: no login UI in a one-button optimizer. Trade-off: device_id is a bearer credential — acceptable for
  non-sensitive numeric telemetry; documented in `docs/PRIVACY.md`.
* 2026-11-12 — CI: GitHub Actions `ubuntu-latest` + `android-actions/setup-android`; release APK signed with
  a keystore generated in-workflow (if no repo secrets) so the APK is installable. Documented in
  `docs/BUILD.md`.

## Known constraints

* Google Play rejects overlay-over-game apps; distribution is direct APK + GitHub Releases.
* `MediaProjection` requires a fresh user consent per capture session on Android 14+; we re-prompt from
  the puck's MODE menu and continue safely in governor mode if denied.
* Emulator GLES emulation is unreliable; CI does not run GL instrumented tests.

## Open questions

* Should BOOT_COMPLETED auto-restore be default-on? (currently opt-in via Settings toggle)
* Per-game resolution presets vs. one global preset — currently global + SoC-tier defaults from edge fn.
