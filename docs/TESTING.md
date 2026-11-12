# TESTING.md — DLSS-5

## Automated (CI, every push)

| Check | Command | Expected |
|---|---|---|
| Unit tests | `:app:testDebugUnitTest` | 13 tests green (governor, kernels, bounds) |
| Release build | `:app:assembleRelease` | BUILD SUCCESSFUL, APK ≈ 0.9 MB |
| Resource linking | part of build | no `AAPT: error` |
| Manifest sanity | `aapt2 dump badging` | `sdkVersion:'26'`, `targetSdkVersion:'34'`, package `com.dlss5.optimizer` |
| Permissions | `aapt2 dump permissions` | SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(_SPECIAL_USE/_MEDIA_PROJECTION), WAKE_LOCK, POST_NOTIFICATIONS, INTERNET |
| Signature | `apksigner verify --verbose` | `Verifies`, v2 scheme true |

## Manual matrix (device)

Run each row on Android 8.0, 10, 12 and 14 if available.

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | Fresh install, permission denied | Install → tap ENGAGE without granting overlay | Banner "Please allow Display over other apps…", Settings opens, **no crash** |
| 2 | Permission granted | Grant overlay → ENGAGE | Button shows CALIBRATING…, app minimises in ~1.1 s, puck appears |
| 3 | Overlay touch-through | Start PUBG, tap/hold/swipe everywhere except the puck | Game responds normally; only the puck captures input |
| 4 | Puck drag + snap | Drag puck to the other edge, release | Snaps to nearest edge with a 10 dp margin |
| 5 | Puck long-press menu | Long-press puck | Neon menu: STOP / TOGGLE HUD / NEXT MODE / CAPTURE DLSS |
| 6 | HUD toggle | Tap puck | Stats panel hides/shows (FPS, ms, p95, scale, mode, sparkline) |
| 7 | Notification survives | Minimise for 10 min with a game in the foreground | Notification still live, FPS text updating ≤ 1.5 s cadence |
| 8 | Notification STOP | Expand notification → STOP | Overlay disappears, engine stored with final stats, app shows ENGAGE |
| 9 | Thermal | Run 20 min in a warm room | `THERMAL` card rises (LIGHT → MODERATE); render scale ceiling drops; no crash |
| 10 | SAFE MODE | Enable SAFE MODE → ENGAGE | Adaptive governor disabled, scale pinned 0.75×, HUD shows SAFE MODE |
| 11 | TRUE DLSS | Chip → TRUE DLSS → allow capture | Dimmed 35 % preview of upscaled+sharpened frames; HUD status "TRUE DLSS" |
| 12 | Capture denied | Same but press Back in the capture dialog | Falls back to GOVERNOR, toast explains, no crash |
| 13 | Capture revoked mid-run | Android 14: revoke from the system chip | `projection stopped` → auto-fallback to GOVERNOR, puck stays alive |
| 14 | Low-end GPU fallback | Entry-tier device or emulator | HUD status GOVERNOR MODE, toast "This GPU blocked hardware upscaling…", no black screen |
| 15 | Offline | Airplane mode → session → re-enable data | Session stays `LOCAL`, sync drains within 5 min, cloud dot turns green |
| 16 | Kill service | Settings → Apps → DLSS-5 → Force stop | No crash dialog; engine flag cleared; relaunch works |
| 17 | Rotation | Rotate while overlay is up | HUD re-measures to the new viewport; puck stays on-screen |
| 18 | Battery saver | Enable battery saver → ENGAGE | Toast warns FPS boost is limited; engine still runs |
| 19 | Reboot restore (opt-in) | Enable boot restore, reboot | Engine auto-starts only if the overlay permission survived |
| 20 | Update install | Install a newer APK over the old one | Installs in place (same signing key), sessions preserved |

## Ad-hoc checks

```bash
adb logcat -s DLSS5/Service DLSS5/Engine DLSS5/Capture DLSS5/Overlay DLSS5/RenderPipeline
adb shell dumpsys activity services com.dlss5.optimizer     # FGS alive + type
adb shell dumpsys window | grep -i "application overlay"    # two windows, HUD not touchable
```

## Known emulator caveats

* `Choreographer` cadence on emulators is synthetic — FPS numbers are not meaningful, but the app must not
  crash and the fallback clock must engage.
* `MediaProjection` capture works on recent system images but the GL pass chain may fail on SwiftShader;
  that path is exactly what the governor fallback exists for.
