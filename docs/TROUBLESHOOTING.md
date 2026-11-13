# TROUBLESHOOTING.md — DLSS-5 v1.2.0

Every message the app can show, what it means, and what v1.2.0 changed.

## Fixed in v1.2.0

| Message you used to see | Root cause | Fix |
|---|---|---|
| **"This GPU blocked the HDR shader."** | One monolithic shader with `highp`, 6×6 Lanczos taps and a bloom sampler. Any driver that dislikes *one* construct rejected the *whole* effect. | Shader **tier ladder**: `hdr_full → hdr_lite → hdr_minimal → software_cpu`. The first tier that compiles is used; failures are remembered and never retried. The message is gone — you now get "HDR compatibility tier active". |
| **"Capture stopped (upscale pipeline failed)"** | The upscale pipeline allocated `GL_ALPHA` textures. On most Adreno/Mali drivers `GL_ALPHA` is **not a renderable colour format**, so the FBO came back `FRAMEBUFFER_UNSUPPORTED` and every frame failed. | All render targets are `GL_RGBA`/`UNSIGNED_BYTE` (the only spec-guaranteed renderable format). Plus FBO probing, automatic working-resolution reduction, and one automatic pipeline rebuild before ever reporting a failure. |
| "Running DLSS upscale instead" on modern phones | Fallback triggered too eagerly: a single failed frame killed the pipeline. | Two retries with a pipeline rebuild; EGL context is re-established per frame; the tier is reported in the diagnostics panel. |

## What each message means now

| Message | Meaning | Action |
|---|---|---|
| `HDR running in compatibility mode (<tier>)` | A lower shader tier compiled (e.g. `hdr_lite` = no bloom). Colour grade is still fully active. | None — it works. |
| `HDR running on the CPU (compatibility mode)` | Every GPU tier was rejected; grading happens on the CPU at a reduced cadence. | None — it works. Use the NATIVE preset for max speed. |
| `GPU upscaling is not supported here — GOVERNOR frame pacing is still active` | No upscale shader could compile *and* the CPU path is unavailable for that mode. | Use **HDR** or **GOVERNOR**. |
| `Screen capture ended — GOVERNOR frame pacing continues` | The projection was revoked (screen off, another recorder started, or you tapped stop in the status chip). | Re-engage from the app. |
| `Saved to gallery: Pictures/DLSS-5` | Snapshot written — proof the grade is real. | Open Google Photos to compare. |
| `Cloud tuning unreachable — keeping your local preset` | No network / Supabase unreachable. | Nothing; presets are local-first. |

## If the effect looks too strong

1. Tap **BLEND** in the app: 100 % → 75 % → 50 %.
2. Or switch preset: **EXTREME HDR** is the punchiest; **GAME VIVID** is the balanced one.
3. **SHARP** cycles 12 / 25 / 50 / 80 % if edges look crunchy.

## If the overlay does not appear

* Grant **Display over other apps** (Android 12+: Settings → Apps → DLSS-5 → Display over other apps).
* Some OEMs (MIUI, ColorOS, Funtouch) also need **Autostart** / **Background activity** allowed.
* Battery saver on? The app warns you; FPS gains shrink but nothing breaks.

## Diagnostics panel (in-app)

The **ENGINE DIAG** box shows, live: engine state, shader ladder status, chosen tier, display refresh
policy, thermal level, and the SoC tier the app decided on. If you report an issue, screenshot that box.

```bash
adb logcat -s DLSS5/ProgramCache DLSS5/HdrGL DLSS5/RenderPipeline DLSS5/Capture DLSS5/Service
```
