# PhoneBot Android

PhoneBot runs Pi Agent Core inside an Android APK. Kotlin hosts the engine in a separate process. A Compose chat controls tasks on the unlocked main display.

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

1. Open PhoneBot. The foreground engine starts with the app.
2. Open **设置 → 模型服务**. Select a Pi provider. Use its API-key or browser login flow. Select a model. Press **验证连接** to send a small real request. Provider charges can apply.
3. Open **设置 → 权限**. Enable the accessibility service. Keep the display on and unlocked. Notification permission enables the notification stop control.
4. Enter a task in the chat. Expand execution steps to inspect tool results. Use the square Stop control to cancel the task. A permission pause offers an explicit resume control after authorization.
5. Use the drawer to find or delete conversations. Use **设置 → 停止引擎** to end the engine process. Use **启动引擎** to start a fresh process.

Pi supplies the provider catalog, authentication flows, token refresh, model requests, and agent loop. Android encrypts API keys and OAuth credentials with Keystore. Provider and model defaults survive restart. A successful connection check marks that exact model as verified on this installation. Other entries remain unverified.

The tools read accessibility elements, capture screenshots, recognize Chinese and English text, list and open apps, tap, swipe, enter Chinese text, go Back, and go Home. Input includes a read-back check. Actions return a fresh screen. Screenshots and OCR require Android 11 or later. Image results require a vision model. Secure windows can block screenshots.

Page text and images can reach the selected model provider during a task. Credential login stays outside the conversation. Local conversations and screenshots remain in app-private storage. Delete a conversation through its drawer menu. Screenshot cache entries expire as new captures replace them.

**设置 → 运行自检** uses a local HTTP fixture and the `runtime_info` host tool. It verifies embedded Node, Pi transport, and the tool round trip. It does not validate a cloud account.

## Development

```powershell
npm --prefix runtime ci --registry=https://registry.npmjs.org --cache .tools/npm-cache
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 connectedDebugAndroidTest
```

Use a connected device or a running emulator for instrumentation tests. See [verification](docs/verification.md) for acceptance checks. Read [runtime design and primary sources](docs/runtime.md) before changing the engine. Read the [interface contract](docs/interface.md) before changing Compose screens.
