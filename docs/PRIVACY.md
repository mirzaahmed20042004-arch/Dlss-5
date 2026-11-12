# PRIVACY.md — DLSS-5

DLSS-5 is a local-first performance tool. It has no accounts, no ads, no third-party analytics SDKs.

## What stays on the device

* Session history (last 50 runs): fps, frame times, render scale, thermal level, duration.
* Telemetry queue (up to 5 000 numeric samples) until it is uploaded.
* All settings, including the anonymous device id.

Nothing is read from other apps. Screen content is **never** uploaded; in TRUE-DLSS mode the captured
frames are processed in GPU memory on the device and displayed in a dimmed overlay — they are not written
to disk and never transmitted.

## What is sent (opt-out: Settings → TELEMETRY)

Only numeric performance data, keyed to a random UUID generated on first launch:

| Table | Fields |
|---|---|
| `devices` | manufacturer, model, Android release, SDK, SoC string, SoC tier, RAM MB, core count, max refresh Hz, GLES3 support, app version |
| `sessions` | timings, target fps, avg fps, p95 frame ms, dropped frames, avg/final render scale, thermal max, kernel used |
| `telemetry_samples` | 1 Hz: fps, frame ms, render scale, thermal, GPU pass cost |

There is no IMEI, no advertising id, no location, no contacts, no file access.

## Your controls

* **TELEMETRY chip** — turns upload on/off instantly. Off also empties future queued samples.
* `rotateDeviceId()` (developer hook, `Prefs`) — issues a brand-new identity so future rows can never be
  linked to the old one. Previously uploaded rows remain, but are no longer attributable.
* Clearing app data removes every local artifact.

## Security model of the backend

The APK embeds only the Supabase **publishable** key. Postgres Row Level Security restricts each row to
the `x-device-id` header the client presents, so a leaked key cannot read another player's history. The
service-role key exists only inside the Supabase project and its edge function.

The anonymous device UUID functions as a bearer token for that device's own rows. It is treated as
non-sensitive (numeric telemetry only). Nothing in the schema contains personal data.
