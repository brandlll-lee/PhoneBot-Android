<p align="center"><a href="README.md">简体中文</a> · English</p>

<h1 align="center">PhoneBot</h1>
<h3 align="center">Your AI can use your phone now.</h3>

<p align="center">
An AI companion that lives on your phone. It operates your phone and handles everyday tasks.<br />
You give the task. It takes action.
</p>

<p align="center">
<a href="#quick-start">Quick start</a> ·
<a href="#what-it-can-do">Features</a> ·
<a href="#frequently-asked-questions">FAQ</a> ·
<a href="#development">Build from source</a>
</p>

**No Root or Termux required. The runtime comes inside the APK.** Connect a model and enable accessibility to start operating your phone. Shizuku is optional. It enables system commands and direct Wi-Fi changes.

## Demo

## What it can do

- **Choose your model.** Use Pi's providers, model catalog, authentication and request implementations. Switch models above the input. Credentials stay encrypted on your phone.
- **Operate your phone.** Open apps, read screens, tap, swipe, enter Chinese text, go Back and go Home. Use Chinese/English OCR or screenshots when needed.
- **Handle everyday settings.** Read and adjust media volume, set brightness and open system settings. Use system apps for alarms, timers and calendar entries.
- **Follow the process.** Expand the trace to inspect returned thinking, intermediate text, tool calls and results. Final replies support Markdown, tables and code blocks.
- **Stop at any time.** Run one task at a time. Tasks pause when a required permission is missing. Stop the current task or return to its conversation from a result notification.

## Quick start

Use a test APK supplied by the maintainer, or [build from source](docs/build.md). The app currently uses Chinese interface labels. The steps below retain those labels so you can find each control.

### 1. Install and open

Download the APK to your phone. Open it in your file manager. Allow that file manager to install apps when Android asks. Open PhoneBot. The embedded engine starts automatically.

### 2. Connect a model

Open **设置 → 模型服务** (Settings → Model services). Choose a provider. Enter an API key or use the account login offered by that provider. Select a model and press **验证连接** (Verify connection).

The model must support tool calling. Screenshot input also needs image support. Pi supplies the catalog. Each provider defines its authentication methods. A successful connection check confirms that the selected model and credentials can make a request. It does not establish success for every phone task.

### 3. Enable permissions

Open **设置 → 权限** (Settings → Permissions). Enable **无障碍服务** (Accessibility service). Keep the screen on and unlocked while PhoneBot operates other apps.

Enable **通知权限** (Notifications) and **任务结果横幅** (Task result banners) if you want result notifications. Brightness changes need **修改系统设置** (Modify system settings). Basic phone control does not require Shizuku.

### 4. Give it a task

Start with a task that is easy to check:

> Set media volume to 30%.

You can also try:

> Open the system display settings.

> Open the gallery and tell me which controls are on the current page.

The button above the input selects a model from the current provider. Sending a task closes the keyboard. Expand the trace to inspect each step. Press the square Stop button to cancel. After a permission pause, grant the required permission and press Continue.

## Requirements and current status

| Item | Requirement or status |
| --- | --- |
| Android | Android 9 or later; screenshots and OCR need Android 11 or later |
| Architecture | ARM64 phones and x86-64 devices or emulators |
| Model | A working model service and network access; provider fees and limits apply |
| Phone control | Uses the main screen while it is on and unlocked; occupies that screen |
| Optional system access | Direct Wi-Fi changes and system commands need a running, authorized Shizuku connection |
| Tested devices | Samsung S21 with Android 14 on ARM64; Android 16 x86-64 emulator |

PhoneBot is in early testing. Results depend on the model, Android system and third-party app pages. Providers and models in Pi's catalog have not all been tested on Android. Virtual displays, screen-off operation and remote takeover are not available. See the [verification record](docs/verification.md) for the tested scope.

## Data and privacy

- Requests go to your configured model provider. Task-related screen text and screenshots can be included.
- API keys and login credentials are encrypted on the phone with Android Keystore.
- Conversations, execution logs and screenshots stay in app-private storage. Logs contain page data. Review and redact them before sharing.
- Deleting a conversation from the sidebar also deletes its execution logs, Pi transcript and image archive.

## Frequently asked questions

**Do I need a computer, Root or Termux?**

Not for basic use. Install the APK, connect a model and enable accessibility on your phone. Building from source requires a computer.

**Does an on-device engine mean offline model inference?**

PhoneBot runs the Pi engine on the phone. Your configured provider handles model inference. Model access usually needs a network connection.

**How do I diagnose a problem?**

Run **设置 → 运行自检** (Settings → Runtime self-check) to test the embedded engine and tool bridge. Then use **模型服务 → 验证连接** (Model services → Verify connection) to test a real model request. Self-check uses a local fixture and does not consume cloud model quota. It does not verify your model connection.

## Development

PhoneBot uses Kotlin and Jetpack Compose. A separate engine process embeds Node and Pi Agent Core. Pi owns model requests and the agent loop. Kotlin provides Android tools.

- [Build guide](docs/build.md): Windows toolchain, APK output and development commands.
- [Runtime design](docs/runtime.md): processes, Pi integration, tools, permissions and storage.
- [Interface design](docs/interface.md): chat, traces, input and settings.
- [Verification](docs/verification.md): test commands, device records and validation limits.

When reporting a problem, include the PhoneBot version, phone model, Android version, provider, model, original task and failing step. Remove keys, login credentials and personal information before sharing logs.
