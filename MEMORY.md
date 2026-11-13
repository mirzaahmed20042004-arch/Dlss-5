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
* 2026-11-12 — CI: GitHub Actions `ubuntu-latest` + `android-actions/setup-android`; release APK signed in
  workflow. A repository secret (`DLSS_KEYSTORE_B64`/`DLSS_KEYSTORE_PASSWORD`) takes precedence; when it
  is absent CI falls back to a *deterministic* PKCS12 key (alias `dlss5`, pass `dlss5release`) so
  consecutive builds keep one identity and users can update in place. Documented in `docs/BUILD.md`.
* 2026-11-12 — **APK delivery triple-path:** GitHub Release asset (`build-latest`), committed copy at
  `dist/DLSS-5.apk` (pushed by CI with `[skip ci]`), and the Actions artifact. Why: the release link is
  the "direct download" a user expects, the committed file survives release churn, and the artifact helps
  forks.
* 2026-11-12 — **GL history ping-pong, not a feedback loop.** The temporal blend renders into a third
  texture and then swaps `prev`/`next`. Rendering into a texture you sample is undefined in GLES and was
  caught in review before shipping.
* 2026-11-12 — **UPSCALE vs TRUE DLSS are genuinely different paths** (bicubic@20 fps vs Lanczos-3@12 fps),
  both fed by MediaProjection, so mode selection means something measurable.

## v1.1 — LIVE HDR graphics (2026-11-13)

* 2026-11-13 — **HDR is a correction layer, never a replacement.** MediaProjection captures our overlay,
  so an opaque graded copy would compound into a feedback tunnel. We emit a translucent per-pixel
  correction solved from `src·α + game·(1−α) = graded`; loop gain is `1−α`, so frames converge on the
  target grade. Rejected alternative: opaque presentation (looks instant, but recurses into garbage).
* 2026-11-13 — **Capture-side neutralisation** subtracts the previous layer before grading
  (`game ≈ (observed − α·layer)/(1−α)`) so the solution is accurate rather than converged-over-time only.
  Costs one `getPixels/setPixels` on the capture thread; gated by `useFeedbackNeutralisation` for weak SoCs.
* 2026-11-13 — **New mode `HDR` (=3, append-only enum)**; capture-required modes are centralised in
  `Prefs.requiresCapture`.
* 2026-11-13 — **Presets ship clamped** (`HdrPreset.sanitized()`), and remote tuning can only move the
  uniform inside safe ranges — a bad backend payload can never produce a black or blown-out screen.
* 2026-11-13 — Foreground service promotes to `specialUse|mediaProjection` at capture start (API 34 rule).
* 2026-11-13 — Honest framing in UI/docs: this is a real GLSL HDR grade over captured frames, not
  injection into PUBG's renderer (impossible without root/Vulkan layer).

## v1.2.0 — root causes of the "GPU blocked" reports (2026-11-13)

* 2026-11-13 — **`GL_ALPHA` is not a renderable format in GLES 2.0.** The upscale pipeline used alpha
  textures as FBO attachments → `FRAMEBUFFER_UNSUPPORTED` on nearly every mobile driver → "upscale
  pipeline failed". All render targets are `GL_RGBA8` now (`GpuTextures`).
* 2026-11-13 — **Never ship one monolithic shader.** A single unsupported construct rejects the whole
  program, which read to users as "my GPU is blocked". Every stage now has a tier ladder
  (`hdr_full → hdr_lite → hdr_minimal → software_cpu`, `lanczos3 → bicubic → linear_sharpen`), the first
  compile wins, and failures are cached in `ProgramCache.bannedTiers` so we never retry on a frame.
* 2026-11-13 — **Probe FBO support and shrink.** `GpuTextures.supportsRenderTarget()` validates the exact
  size; the pipeline steps down 3/4 at a time. Old GPUs get a smaller working surface instead of an error.
* 2026-11-13 — **CPU grading fallback** (`SoftwareGrader` + pure `ColorMath`) means even a device that
  rejects every shader still gets a real, visible colour change. Honest label: "compatibility mode".
* 2026-11-13 — **Contrast belongs in display space.** Applying the contrast pivot in linear light crushed
  shadows to black (linear 0.5 = sRGB 0.73). HDR ops stay linear; tone-map first, then
  contrast/shadow-lift/saturation. Unit tests lock this in.
* 2026-11-13 — **Diagnostics over apologies:** the in-app ENGINE DIAG panel reports the live shader tier,
  display policy and thermal state, so "it doesn't work" becomes observable.

## Known constraints

* Google Play rejects overlay-over-game apps; distribution is direct APK + GitHub Releases.
* `MediaProjection` requires a fresh user consent per capture session on Android 14+; we re-prompt from
  the puck's MODE menu and continue safely in governor mode if denied.
* Emulator GLES emulation is unreliable; CI does not run GL instrumented tests.

## Open questions

* Should BOOT_COMPLETED auto-restore be default-on? (currently opt-in via Settings toggle)
* Per-game resolution presets vs. one global preset — currently global + SoC-tier defaults from edge fn.
