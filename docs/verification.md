# Verification

## Required checks

Run from the repository root:

```powershell
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 assembleDebug testDebugUnitTest lintDebug
./scripts/gradle.ps1 connectedDebugAndroidTest
```

The runtime tests must pass for both source and bundled entry points. They check two model HTTP requests, host tool results, repeated execution, tool errors, cancellation, catalog selection, busy rejection, Pi login, argument validation, and image content.

The JVM test checks available controls for each engine phase. Lint treats warnings as errors.
Dependency update advice is disabled because the build uses a pinned, compatible toolchain.

Android tests cover process isolation, a Pi-to-Kotlin round trip, stop, restart, process failure, and recovery. They also cover Pi catalog/authentication, saved model selection, encrypted credential updates/deletion, Chinese input with read-back, stale element rejection, gestures, app navigation, screenshots, and Chinese/English OCR.

## APK inspection

Use SDK Build Tools `zipalign -c -P 16 -v 4` on the APK. Inspect native `LOAD` segments with the NDK's `llvm-readelf -l`. Each bundled native library must have at least `0x4000` segment alignment.

The APK must contain `libnode.so` and `libphonebot.so` for both configured ABIs. It must contain the runtime bundle, its version hash, and license notices.

## Device acceptance

Start the app from its launcher. Open Settings and run self-check. Check chat, drawer, settings, provider authentication, and permissions. Check a compact display with 1.3 font scale. Open the keyboard and confirm that the composer stays visible. Keep the app in the background and confirm that the foreground notification remains. Use its Stop action and confirm that the engine process ends. Start it again and run self-check.

Use real provider credentials to validate cloud requests. Keep keys out of command output, test reports, and committed files. Report cloud verification separately from local fixture verification.

## Validation record

Validated on 2026-10-31:

- Runtime tests: 10 passed. Source and bundled entry points completed the two-request tool loop. Provider authentication used test credentials and the host store.
- JVM tests: 1 passed.
- Android instrumentation: 4 passed on Android 16, API 36, x86-64, with 4 KB memory pages.
- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest`: passed through the project wrapper.
- Lint: no issues.
- APK ZIP alignment: passed `zipalign -c -P 16 4`.
- Native ELF alignment: all packaged library `LOAD` segments use `0x4000` alignment.
- APK inspection: both configured ABIs contain Node and the JNI host. The runtime bundle and notices are present.
- Manual UI: chat, drawer, settings, provider list, and 1.3 font scale were inspected. The composer remained visible above the keyboard on a 960 × 1800 display. Seeded chat messages checked layout only. They are not cloud-task evidence.

Cloud provider requests, ARM64 hardware, Android API 28/37 devices, and a device with 16 KB memory pages were not part of this run. Node's Android distribution remains a pinned prerelease.
