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
3. Open **设置 → 权限**. Enable the accessibility service. Keep the display on and unlocked. Enable notifications and the **任务结果横幅** channel to receive task results. Tap a result notification to open its conversation and task.
4. Use the model button above the input to select a model from the current provider. Use **管理模型服务** to configure providers. Enter a task. The input grows to five visible lines. Accepted sends clear the input and close the keyboard. Expand execution steps to inspect tool results. Use the square Stop control to cancel the task. A permission pause offers an explicit resume control after authorization.
5. Use the drawer to find or delete conversations. Use **设置 → 停止引擎** to end the engine process. Use **启动引擎** to start a fresh process.

Pi supplies the provider catalog, authentication flows, token refresh, model requests, and agent loop. Android encrypts API keys and OAuth credentials with Keystore. Provider and model defaults survive restart. A successful connection check marks that exact model as verified on this installation. Other entries remain unverified.

The tools read accessibility elements, capture screenshots, recognize Chinese and English text, list and open apps, tap, swipe, enter Chinese text, go Back, and go Home. Input includes a read-back check. Actions return screen changes with a snapshot baseline. `do_steps` runs predictable actions locally and stops on a failed check or detected dialog. All coordinates use the full main display. Screenshots and OCR require Android 11 or later. Images use JPEG quality 80 and a longest edge of 1280 pixels. Image results require a vision model. Secure windows can block screenshots.

An unavailable accessibility tree returns an empty observation. Screenshot, Home, and app launch remain available. A dispatched action and a verified goal have separate results. Unknown navigation stops the batch for review. It does not by itself mark the action as failed.

System tools read and set media volume, brightness, and Wi-Fi. Brightness needs **修改系统设置** authorization. Direct Wi-Fi changes and system commands need a running, authorized [Shizuku](https://shizuku.rikka.app/guide/setup/) connection. Otherwise, Wi-Fi opens the system panel for user or UI action. Commands use the connection's shell or root privileges. They have a ten-second execution limit and a 32768-character output limit. Commands cannot bypass Android's permission rules.

Alarm, timer, and calendar tools use standard Android intents. They open the system handler and report that the result still needs verification. `app_send_intent` dispatches Android actions with scalar extras. `phone_open_link` opens supported links or standard sound, display, network, and app-detail settings pages.

Pi receives the user's original message and runs its agent loop. Tool results return to the model. Its final reply ends execution and becomes the displayed answer. A result notification means execution ended. The reply explains what succeeded or remains unresolved. Input read-back, system-state read-back, and action postconditions run inside their tools.

Assistant replies render as Markdown, including lists, links, tables, and highlighted code with Copy. Intermediate text, returned thinking, and tools appear in an ordered execution trace. It expands during execution and collapses after execution. The final reply stays outside it. Task completion posts one result notification in foreground or background. A stable page means its tree is quiet. Actions use explicit postconditions to verify loaded results. Earlier screen images remain available by reference instead of entering every new task's requests.

Page text and images can reach the selected model provider during a task. Context budgets limit old images and compress older tool rounds. Pi transcripts remain separate from the model request view. Credential login stays outside the conversation. Local conversations, task event journals, and screenshots remain in app-private storage. Task journals retain request timing, payload size, tool results, errors, and interrupted work. Delete a conversation through its drawer menu to remove its transcript and journals. Screenshot cache entries expire as new captures replace them.

**设置 → 运行自检** uses a local HTTP fixture and the `runtime_info` host tool. It verifies embedded Node, Pi transport, and the tool round trip. It does not validate a cloud account.

## Development

```powershell
npm --prefix runtime ci --registry=https://registry.npmjs.org --cache .tools/npm-cache
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 connectedDebugAndroidTest
```

Use a connected device or a running emulator for instrumentation tests. See [verification](docs/verification.md) for acceptance checks. Read [runtime design and primary sources](docs/runtime.md) before changing the engine. Read the [interface contract](docs/interface.md) before changing Compose screens.
