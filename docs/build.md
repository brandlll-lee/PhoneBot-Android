# Build PhoneBot

## Requirements

Use Windows, JDK 21, and Node 22.19 or later. Set `JAVA_HOME` to the JDK directory.

Keep the Android SDK under `.tools/android-sdk` on the project drive. Use Android Studio's SDK Manager to install these packages into that SDK:

| Package | Version |
| --- | --- |
| Android SDK Platform | API 37 (`platforms;android-37.0`) |
| Android SDK Build Tools | 36.0.0 |
| Android SDK Platform Tools | Required for device installation and tests |
| NDK | 28.2.13676358 |
| CMake | 3.31.6 |

The PowerShell wrapper selects `.tools/android-sdk` when that directory exists. You can also set `sdk.dir` in `local.properties` to this location.

## Build the APK

Run from the repository root:

```powershell
./scripts/gradle.ps1
```

This command builds the debug APK and runs JVM tests and lint. The first build needs network access. Gradle downloads the pinned Node Android archive, verifies its SHA-256, installs locked npm packages, and bundles the runtime. It includes the runtime and license notices in the APK.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The APK includes ARM64 and x86-64 libraries. This build uses the debug signing key. Android can update an existing installation only when its package name and signing key match.

## Storage locations

The wrapper keeps Gradle caches in `.tools/gradle-home`, npm caches in `.tools/npm-cache`, Android user files in `.tools/android-user`, and virtual devices in `.tools/android-user/avd`. Native runtime downloads and extracted libraries also stay under `.tools/`.

## Runtime development

For JavaScript-only work, run from the repository root:

```powershell
npm --prefix runtime ci --registry=https://registry.npmjs.org --cache .tools/npm-cache
npm --prefix runtime run build
npm --prefix runtime test
```

The bundle is generated in `runtime/dist/` and copied into the app's runtime assets. To rebuild both the native artifacts and the JavaScript bundle, use the reproducible preparation script:

```powershell
node scripts/prepare-runtime.mjs
```

## Android tests

Connect a test device or start an emulator, then run:

```powershell
./scripts/gradle.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
./scripts/gradle.ps1 connectedDebugAndroidTest
```

Run the complete instrumentation suite on a test installation. The authentication test changes credentials and model selection. For a configured personal phone, select the controlled test described in [verification](verification.md).

Read [runtime design](runtime.md) before changing the engine, IPC, native code or Pi integration. Read [interface design](interface.md) before changing Compose screens or interactions.

[简体中文](../README.md) · [English](../README.en.md)
