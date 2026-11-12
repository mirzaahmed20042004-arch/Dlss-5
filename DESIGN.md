# DESIGN.md — DLSS-5

## Brand

**Name:** DLSS-5 · **Tagline:** *Desktop-class frames. Phone-sized cost.*
**Voice:** engineering-grade, confident, zero hype-words that cannot be measured.

## Logo (conceptual)

`docs/logo/dlss5-logo-512.png` — a neon-green hexagonal shield containing three stacked, pixel-gridded
chevrons that grow from a lower-left *pixelated* cluster (low-res source) to a crisp upper-right wedge
(upscaled output). It encodes the exact product promise: **small pixels in, sharp frames out**.
Adaptive icon foreground = logo glyph; background = `#000000` with a radial glow.

## Color system

| Token | Value | Usage |
|---|---|---|
| `dlss_black` | `#000000` | App background, overlay puck base |
| `dlss_surface` | `#0A0F0A` | Cards, sheets |
| `dlss_surface_2` | `#101710` | Inputs, chips |
| `neon_green` | `#39FF14` | Primary accent, button fill, glow |
| `neon_green_dim` | `#1F8F12` | Strokes on dark, disabled |
| `neon_cyan` | `#00FFC6` | Secondary metric accents (graphs) |
| `neon_amber` | `#FFC400` | Thermal warning |
| `neon_red` | `#FF2D55` | Danger / stop |
| `text_white` | `#FFFFFF` | All primary text |
| `text_muted` | `#9CA89C` | Secondary text (still light on black) |

Contrast: white on black = 21:1; neon green on black = 15.9:1; muted text on black = 9.6:1 (all ≥ WCAG AA).

## Typography

* Display / numerals: **Roboto Condensed** (system) — uppercase, `letterSpacing 0.08em`.
* Body: **Roboto** (system) regular / medium.
* Metric numerals use tabular figures so FPS counters don't jitter.

## Layout — Main screen (top → bottom, single column, 24dp gutters)

1. **Header row** — logo glyph 32dp + `DLSS-5` wordmark; right side: cloud status dot (green=synced).
2. **Hero ring** — 220dp `NeonRingView`: animated neon arc showing live/measured FPS; center label
   `ENGINE IDLE` / `ENGINE ACTIVE`; sub-label `0.70× → native`.
3. **Primary button** — full-width 64dp, black fill, 2dp neon stroke, neon glow shadow:
   `ENGAGE DLSS 5`. On press: stroke animates to a filled sweep + haptic tick; label becomes
   `CALIBRATING…` then app minimizes.
4. **Stat grid** — 2×2 cards: `TARGET FPS`, `RENDER SCALE`, `FRAME TIME`, `THERMAL`. Values white,
   labels muted, values animate on change.
5. **Mode selector** — segmented chips: `GOVERNOR` · `UPSCALE` · `TRUE DLSS`.
6. **Session history** — last 5 sessions (date, mode, avg fps, sparkline).
7. **Footer** — version + `SAFE MODE` toggle (forces governor-only).

## LIVE HDR graphics (v1.1)

| Preset | Look | Tailored for |
|---|---|---|
| **EXTREME HDR** | +10 % exposure, +20 % contrast, +35 % vibrance, strong bloom (0.52), heavy unsharp (0.48) | showcase / cinematic play |
| **CINEMATIC** | filmic roll-off, soft bloom, warm-neutral | screenshots, long-range spotting |
| **VISIBILITY** | shadows lifted +11 %, contrast flat, sharpen 0.62, almost no bloom | competitive spotting |
| **GAME VIVID** | balanced default | ranked play |
| **NATIVE** | no colour work | pure DLSS path |

The HDR layer is a full-screen, **translucent, non-touchable** window that sits above the game and below
the stats HUD. Its pixels are a per-pixel *correction* solved on the GPU (see ARCHITECTURE §4), so the
game keeps rendering underneath: no latency, no replaced frames, and every touch still reaches PUBG.

## Floating puck (overlay)

56dp circle, 78 % opaque black, 2dp neon ring, logo glyph inside. Long-press → 96dp ring menu with
`STOP`, `MODE`, `STATS`. Draggable anywhere, snaps to screen edge within 8dp. Never intercepts touches
outside its own bounds (window is `FLAG_NOT_TOUCH_MODAL`).

## Motion

* All transitions 180–260 ms, `FastOutSlowIn` (standard) — no bouncy effects.
* Ring sweep uses `ValueAnimator` with `LinearInterpolator` for a "scanning" feel, pauses when idle.
* Glow pulse on activation: 3 cycles, 600 ms, alpha 0.35 → 0.9.

## Sound / haptics

`HapticFeedbackConstants.CONFIRM` on engage, `REJECT` on permission failure. No audio.

## Accessibility

* Every control ≥ 48dp, `contentDescription` on puck and ring.
* Ring exposes `AccessibilityNodeInfo` with live FPS text.
* Reduced-motion: honour `Settings.Global.ANIMATOR_DURATION_SCALE == 0` by disabling sweeps.

## Empty / error states

| State | Copy |
|---|---|
| No overlay permission | `Please allow "Display over other apps" permission to continue.` + `OPEN SETTINGS` |
| GL unavailable | `This GPU blocked hardware upscaling. Running in GOVERNOR mode — still safe.` |
| No sessions yet | `No runs yet. Engage DLSS 5 to start your first session.` |
| Offline | Cloud dot grey, no toast (silent). |
