# PhoneBot Android

PhoneBot runs Pi Agent Core inside an Android APK. Kotlin hosts the engine in a separate process. Compose provides start, stop, self-check, and model verification controls.

## Build

Use Windows, JDK 21, and Node 22.19 or later. Set `JAVA_HOME` to a JDK. Set `ANDROID_HOME` to the SDK in `.tools/android-sdk`, or set `sdk.dir` in `local.properties`.

Install these SDK packages with Android Studio or the Android CLI:

```powershell
android --sdk="$PWD/.tools/android-sdk" sdk install 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.31.6'
```

Run from the repository root:

```powershell
./scripts/gradle.ps1
```

This command builds the APK and runs JVM tests and lint. Gradle downloads the pinned Node Android archive, verifies its SHA-256, installs locked npm packages, and bundles the runtime. The first build needs network access.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The wrapper defaults to `.tools/gradle-home` and `.tools/android-user`. The PowerShell entry point also places npm caches and virtual devices under `.tools/`. These paths stay on the project drive. `runtime/.npmrc` sets the npm cache for commands run in `runtime/`.

## Run

Install the APK on Android 9 or later. Supported ABIs are ARM64 and x86-64.

1. Open PhoneBot and press **Start**. Wait for **Engine ready**.
2. Press **Run self-check**. The result must include `requests: 2` and Android runtime information.
3. To test a provider, enter an HTTPS OpenAI Chat Completions base URL, a tool-capable model ID, and an API key. Press **Verify model**. This sends a real request and can incur provider charges.
4. Press **Stop**. Wait for **Engine stopped**. Start again to create a new engine process.

Model fields stay in memory. The app does not save the API key. Model verification uses Pi's request implementation and a single Kotlin `runtime_info` tool.

## Development

```powershell
npm --prefix runtime ci --registry=https://registry.npmjs.org --cache .tools/npm-cache
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 connectedDebugAndroidTest
```

Use a connected device or a running emulator for instrumentation tests. See [verification](docs/verification.md) for the acceptance checks. See [runtime design and primary sources](docs/runtime.md) before changing the engine.
