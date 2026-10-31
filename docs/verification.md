# Verification

## Required checks

Run from the repository root:

```powershell
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 assembleDebug testDebugUnitTest lintDebug
./scripts/gradle.ps1 connectedDebugAndroidTest
```

The runtime tests must pass for both source and bundled entry points. They check two model HTTP requests, host tool results, repeated execution, tool errors, cancellation, catalog selection, busy rejection, Pi login, argument validation, and JPEG image content. Context tests cover stable prefixes, image limits, complete tool pairs, pressure-driven compression, reconstructed diff baselines, and interrupted tool calls. A batch fixture verifies that two local actions need one host call and two model requests.

The JVM test checks available controls for each engine phase. Lint treats warnings as errors.
Dependency update advice is disabled because the build uses a pinned, compatible toolchain.

Android tests cover process isolation, a Pi-to-Kotlin round trip, stop, restart, process failure, and recovery. They also cover Pi catalog/authentication, saved model selection, encrypted credential updates/deletion, Chinese input with stable IDs and read-back, ambiguous target rejection, full-screen bottom coordinates, gestures, app navigation, JPEG dimensions/mapping, and Chinese/English OCR. Batch tests check successful execution and early stop for a missing target or dialog. Journal tests cover credential redaction, torn writes, interruption recovery, and terminal states. A real Pi authentication failure must preserve its task journal and transcript.

Run the full suite on a test installation. The authentication test changes provider credentials and the default model. For the user's configured phone, select only the controlled page test:

```powershell
adb -s DEVICE shell am instrument -w -e class dev.phonebot.android.PhoneToolsTest#accessibilityReadsActsVerifiesChineseAndRecognizesPixels dev.phonebot.android.test/androidx.test.runner.AndroidJUnitRunner
```

This test opens the debug fixture. It restores the prior accessibility setting. It does not call a cloud model.

## APK inspection

Use SDK Build Tools `zipalign -c -P 16 -v 4` on the APK. Inspect native `LOAD` segments with the NDK's `llvm-readelf -l`. Each bundled native library must have at least `0x4000` segment alignment.

The APK must contain `libnode.so` and `libphonebot.so` for both configured ABIs. It must contain the runtime bundle, its version hash, and license notices.

## Device acceptance

Start the app from its launcher. Open Settings and run self-check. Check chat, drawer, settings, provider authentication, and permissions. Check a compact display with 1.3 font scale. Open the keyboard and confirm that the composer stays visible. Keep the app in the background and confirm that the foreground notification remains. Use its Stop action and confirm that the engine process ends. Start it again and run self-check.

Use real provider credentials to validate cloud requests. Keep keys out of command output, test reports, and committed files. Report cloud verification separately from local fixture verification.

## Validation record

Validated on 2026-10-31:

- Runtime tests: 17 passed. Source and bundled entry points completed the two-request tool loop. Provider authentication used test credentials and the host store. Two actions used one batch dispatch. Context tests covered image and token budgets, retained prefixes, reconstructed baselines, and interrupted tool results.
- JVM tests: 1 passed.
- Android instrumentation: 5 passed on Android 16, API 36, x86-64, with 4 KB memory pages. The controlled page test covered stable IDs, diffs, bottom coordinates, image mapping, OCR, batch success, and early stop. A failed Pi task retained both its journal and transcript.
- Samsung S21: the controlled phone page and engine lifecycle tests passed on Android 14, API 34, ARM64. The phone test covered bottom-screen taps, Chinese input, OCR, JPEG mapping, and batch interruption. The engine test completed the local Pi transport/tool loop and process restart checks. These tests use local fixtures.
- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest`: passed through the project wrapper.
- Lint: no issues.
- APK ZIP alignment: passed `zipalign -c -P 16 4`.
- Native ELF alignment: all packaged library `LOAD` segments use `0x4000` alignment.
- APK inspection: both configured ABIs contain Node and the JNI host. The runtime bundle and notices are present.
- Manual UI: chat, drawer, settings, provider list, and 1.3 font scale were inspected. The composer remained visible above the keyboard on a 960 × 1800 display. Seeded chat messages checked layout only. They are not cloud-task evidence.

Real cloud task latency, Android API 28/37 devices, and a device with 16 KB memory pages were not part of this run. Request-count checks use a local model fixture. Node's Android distribution remains a pinned prerelease.
