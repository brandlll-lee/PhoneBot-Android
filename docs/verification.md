# Verification

## Required checks

Run from the repository root:

```powershell
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 assembleDebug testDebugUnitTest lintDebug
./scripts/gradle.ps1 connectedDebugAndroidTest
```

The runtime tests must pass for both source and bundled entry points. They check two model HTTP requests, the host tool result, repeated execution, tool errors, cancellation, HTTPS validation, and busy rejection.

The JVM test checks available controls for each engine phase. Lint treats warnings as errors.
Dependency update advice is disabled because the build uses a pinned, compatible toolchain.

The Android test must show that the engine PID differs from the app PID. It must complete a Pi-to-Kotlin tool round trip. It must stop, restart with a new PID, report an injected process failure, and run again after that failure.

## APK inspection

Use SDK Build Tools `zipalign -c -P 16 -v 4` on the APK. Inspect native `LOAD` segments with the NDK's `llvm-readelf -l`. Each bundled native library must have at least `0x4000` segment alignment.

The APK must contain `libnode.so` and `libphonebot.so` for both configured ABIs. It must contain the runtime bundle, its version hash, and license notices.

## Device acceptance

Start the app from its launcher. Run self-check. Keep the app in the background and confirm that the foreground notification remains. Use its Stop action and confirm that the engine process ends. Start it again and run self-check.

Use real provider credentials to validate cloud requests. Keep keys out of command output, test reports, and committed files. Report cloud verification separately from local fixture verification.

## Validation record

Validated on 2026-10-30:

- Runtime tests: 5 passed. Source and bundled entry points completed the two-request tool loop.
- JVM tests: 1 passed.
- Android instrumentation: 1 passed on Android 16, API 36, x86-64, with 4 KB memory pages. This test covered the tool round trip, stop, restart, process failure, and recovery.
- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest`: passed through the project wrapper.
- Lint: no issues.
- APK ZIP alignment: passed `zipalign -c -P 16 4`.
- Native ELF alignment: all packaged library `LOAD` segments use `0x4000` alignment.
- APK inspection: both configured ABIs contain Node and the JNI host. The runtime bundle and notices are present.
- Manual UI: Start and self-check passed. The notification remained after Home. The notification Stop action ended the engine process and the app showed `Engine stopped`.

Cloud provider requests, ARM64 hardware, Android API 28/37 devices, and a device with 16 KB memory pages were not part of this run. Node's Android distribution remains a pinned prerelease.
