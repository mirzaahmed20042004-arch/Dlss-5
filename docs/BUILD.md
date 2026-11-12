# BUILD.md — how to build the DLSS-5 APK

## Option A — download the prebuilt APK (recommended)

1. Open **Releases** → **DLSS-5 latest build** (or the `v1.0.0` tag).
2. Download `DLSS-5-<n>.apk`.
3. On the phone: allow "Install unknown apps" for your browser/file manager, tap the APK, install.
4. Open **DLSS-5** → the app asks for **Display over other apps** → enable it → back → **ENGAGE DLSS 5**.
5. Launch PUBG Mobile. The neon puck sits on the screen edge; drag it, tap it for the HUD,
   long-press it for the menu (STOP / TOGGLE HUD / NEXT MODE / CAPTURE DLSS).

> Every push to this repo rebuilds the APK automatically in GitHub Actions and attaches it to the
> `build-latest` release. No Android Studio needed.

## Option B — build locally

### Requirements

| Tool | Version |
|---|---|
| JDK | 17 (Temurin/OpenJDK) |
| Android SDK | Platform 34, Build-Tools 34.0.0 |
| Gradle | wrapper included (8.7) — do **not** install Gradle manually |
| Android Studio | Koala 2024.1+ (optional) |

### Command line

```bash
git clone https://github.com/mirzaahmed20042004-arch/Dlss-5.git
cd Dlss-5
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"   # or your SDK path
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :app:assembleRelease      # release APK
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests
```

Artifacts:

```
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/debug/app-debug.apk
```

### Signing

CI generates an ephemeral RSA-2048 keystore (`keystore/dlss5-release.jks`, alias `dlss5`,
store/key password `dlss5release`) so every build is installable. For your own permanent key:

```bash
keytool -genkeypair -v -keystore keystore/dlss5-release.jks -alias dlss5 \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass <storepass> -keypass <keypass> \
  -dname "CN=DLSS-5, O=DLSS-5, C=PK"
```

and export before building:

```bash
export DLSS_KEYSTORE_PATH=$PWD/keystore/dlss5-release.jks
export DLSS_STORE_PASSWORD=<storepass>
export DLSS_KEY_ALIAS=dlss5
export DLSS_KEY_PASSWORD=<keypass>
```

If no keystore exists the release build falls back to the debug signature so the artifact still installs
(useful for CI on a fork without secrets).

## Install without a USB cable

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# or
adb install -r DLSS-5-<n>.apk
```

## Gradle/AGP matrix (pinned for reproducibility)

| Component | Version |
|---|---|
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| Java target | 17 |

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | create `local.properties` with `sdk.dir=/path/to/Sdk` |
| `Unsupported class file major version` | your JDK is not 17; `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | different signing key — `adb uninstall com.dlss5.optimizer` first |
| App installs but puck never appears | grant **Display over other apps** (Settings → Apps → DLSS-5 → Display over other apps) |
| Puck appears but no FPS numbers | some ROMs starve background `Choreographer`; the engine falls back to its own clock automatically |

## CI

`.github/workflows/android.yml`:

1. JDK 17 + Android SDK 34
2. ephemeral keystore
3. `:app:testDebugUnitTest`
4. `:app:assembleRelease`
5. `aapt2 dump badging` + `apksigner verify` sanity checks
6. uploads the APK and publishes it to the `build-latest` GitHub Release
7. (diagnostics) mirrors `build.log` to the `ci-logs` branch on failure
