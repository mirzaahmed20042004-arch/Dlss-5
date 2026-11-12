# RULES.md — engineering rules for DLSS-5

## Hard rules

1. **No placeholders.** Every function ships with a working body. No `// TODO`, no `// add code here`.
2. **No main-thread IO.** Disk, network, GL, and any loop > 2 ms live off the main thread.
3. **No unguarded crash paths.** Every service lifecycle entry point, every GL call site, and every
   network call is wrapped: failures degrade a feature, they never take down the app.
4. **Permissions are requested, never assumed.** Missing permission = explanatory UI + intent, never a crash.
5. **Touch safety.** The overlay window must remain `FLAG_NOT_TOUCH_MODAL`; only explicit control views
   may be touchable. Nothing may cover the game's fire/aim area with a blocking view.
6. **Battery honesty.** No busy loops. Engine cadence ≤ target fps, telemetry ≤ 1 Hz, sync batched.
7. **Offline-first.** Cloud features are additive. Pull the network, the app must still be 100 % usable.
8. **No secrets in the repo.** Only the Supabase *anon/publishable* key may be embedded; RLS must make it
   safe. Service-role keys never leave the edge function environment.
9. **Kotlin style.** 4-space indent, 120-col soft limit, no wildcard imports, explicit types on public APIs.
10. **Compatibility floor.** `minSdk 26`. Any API > 26 usage must be behind a `Build.VERSION` check.

## Coding conventions

* Package root: `com.dlss5.optimizer`.
* Views: `AppCompat`-free where possible; use `TextView`/`View` + `Canvas` for custom graphics (smaller APK).
* Coroutines: `viewLifecycleOwner`-free (no fragments); use `lifecycleScope` in the activity.
* Prefs: single `Prefs` object wrapping `SharedPreferences`, keys as `const val`.
* Logging: `Logger` wraps `Log` and drops `VERBOSE` in release builds.
* Errors: never `catch (Throwable t) {}` silently — always log with a tag and, if user-visible, surface
  a single non-blocking toast at most once per session.

## Git / CI

* Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `build:`).
* CI must be green before a release tag. `android.yml` builds release APK and attaches it to the release.
* APK artifacts are published to GitHub Releases, not committed to Git history (repo stays small).

## Testing

* Unit tests for `FrameGovernor`, `Upscaler` kernel weights, `TelemetryQueue` cap logic.
* Instrumented smoke test (optional in CI) launching `MainActivity` and asserting no crash.
* Manual matrix in `docs/TESTING.md`: Android 8/10/12/14 × {permission granted, denied, revoked}.
