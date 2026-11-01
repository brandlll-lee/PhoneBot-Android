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

**No Root or Termux required. The runtime comes inside the APK.** Connect a model and enable accessibility to operate the main screen. Use the built-in helper or Shizuku for virtual displays and system commands.

## Demo

## What it can do

- **Choose your model.** Use Pi's providers, model catalog, authentication and request implementations. Switch models above the input. Credentials stay encrypted on your phone.
- **Keep the conversation going.** Pi manages sessions, restoration and automatic context compaction. Reopen PhoneBot to continue an existing conversation.
- **Operate your phone.** Open apps, read screens, tap, swipe, enter Chinese text, go Back and go Home. Use Chinese/English OCR or screenshots when needed.
- **Choose the execution screen.** Operate the main screen or use a separate virtual display. Each conversation saves its screen and helper choice.
- **Watch it work.** Tap the bubble to view the live virtual display. Tap the image to collapse it. A blue dot marks an active task. Choose light, dark or system appearance.
- **Handle everyday settings.** Read and adjust media volume, set brightness and open system settings. Use system apps for alarms, timers and calendar entries.
- **Follow the process.** Expand the trace to inspect returned thinking, intermediate text, tool calls and results. Final replies support Markdown, tables and code blocks.
- **Stop at any time.** Run one task at a time. Tasks pause when a required permission is missing. Stop the current task or return to its conversation from a result notification.

## Quick start

Use a test APK supplied by the maintainer, or [build from source](docs/build.md). The app currently uses Chinese interface labels. The steps below retain those labels so you can find each control.

### 1. Install and open

Download the APK to your phone. Open it in your file manager. Allow that file manager to install apps when Android asks. Open PhoneBot. The embedded engine starts automatically.

### 2. Connect a model

Open **··· → 设置 → 模型服务** (Settings → Model services). Choose a provider. Enter an API key or use the account login offered by that provider. Select a model and press **验证连接** (Verify connection). Change the theme under **设置 → 外观** (Settings → Appearance).

The model must support tool calling. Screenshot input also needs image support. Pi supplies the catalog. Each provider defines its authentication methods. A successful connection check confirms that the selected model and credentials can make a request. It does not establish success for every phone task.

### 3. Enable permissions

Open **设置 → 权限** (Settings → Permissions). Enable **无障碍服务** (Accessibility service). Main-screen control needs the phone to stay on and unlocked.

Enable **通知权限** (Notifications) and **任务结果横幅** (Task result banners) if you want result notifications. Brightness changes need **修改系统设置** (Modify system settings). Basic phone control does not require Shizuku.

### 4. Choose a screen and helper

Use **主屏幕／虚拟屏幕** (Main screen / Virtual display) above the input to select the screen for this conversation. Open **会话设置** (Conversation settings) to choose **内置助手** (Built-in helper) or **Shizuku**. PhoneBot saves these choices. You do not need to select them for each task. Press **设为新会话默认配置** (Use for new conversations) to set the defaults for future conversations. Existing conversations keep their own choices.

Basic main-screen control does not need a helper. Virtual displays need Android 14 or later and a working helper. All conversations share device connections and authorization. A running task keeps its selected helper and screen.

During a task, tap the **PhoneBot** bubble to view the virtual screen. Tap the image to collapse it. Drag the bubble or image to move it. In main-screen mode, the bubble returns to the conversation. Stop the task from the chat. The bubble closes when execution ends.

Open **设置 → 设备助手** (Settings → Device helper):

- **Built-in helper**: Requires Android 12 or later. Connect to Wi-Fi. Open PhoneBot and Android Wireless debugging in split-screen. Open **Pair device with pairing code**. Keep the dialog open. Enter its port and six-digit code in PhoneBot. PhoneBot saves the pairing identity and finds the connection port for later use. Android 17 also needs local network permission.
- **Shizuku**: Start Shizuku and authorize PhoneBot.

**The pairing port and connection port are different.** The pairing form needs the port shown beside the pairing code. PhoneBot finds the normal connection port. Do not exchange these ports.

A saved pairing does not mean the helper is always running. PhoneBot attempts to reconnect when needed. **重启后自动恢复** (Restore after reboot) requests the system access needed for recovery. Recovery still depends on Wireless debugging, Wi-Fi, authorization and background limits. Follow any connection prompt, then press **继续任务** (Continue task).

### 5. Give it a task

Start with a task that is easy to check:

> Set media volume to 30%.

You can also try:

> Open the system display settings.

> Open the gallery and tell me which controls are on the current page.

The buttons above the input select the model and screen. Sending a task closes the keyboard. Expand the trace to inspect each step. Press the square Stop button to cancel. After a pause, resolve the permission or connection prompt and press Continue.

## Requirements and current status

| Item | Requirement or status |
| --- | --- |
| Android | Android 9 or later; screenshots and OCR need Android 11 or later |
| Architecture | ARM64 phones and x86-64 devices or emulators |
| Model | A working model service and network access; provider fees and limits apply |
| Phone control | Main screen needs the phone on and unlocked; virtual displays need Android 14 or later and a working helper |
| Device helper | Built-in ADB helper needs Android 12 or later; a running, authorized Shizuku connection is also supported |
| Optional system access | Direct Wi-Fi changes and system commands use the helper selected for the conversation |
| Tested devices | Samsung S21 with Android 14 on ARM64; Android 16 x86-64 emulator |

PhoneBot is in early testing. Results depend on the model, Android system and third-party app pages. Providers and models in Pi's catalog have not all been tested on Android. A virtual display is not an app clone. App data, device volume and network settings remain shared. Short screen-off operations and network access passed on the S21. Extended sleep and other devices still need validation. Remote takeover is not available. See the [verification record](docs/verification.md) for the tested scope.

## Data and privacy

- Requests go to your configured model provider. Task-related screen text and screenshots can be included.
- API keys and login credentials are encrypted on the phone with Android Keystore.
- The built-in helper stores its ADB identity encrypted on the phone. Pairing codes stay out of chats and task logs.
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

PhoneBot uses Kotlin and Jetpack Compose. A separate engine process embeds Node and the official Pi session SDK. Pi owns model requests, the agent loop, session restoration and automatic compaction. Kotlin provides Android tools.

- [Build guide](docs/build.md): Windows toolchain, APK output and development commands.
- [Runtime design](docs/runtime.md): processes, Pi integration, tools, permissions and storage.
- [Interface design](docs/interface.md): chat, traces, input and settings.
- [Verification](docs/verification.md): test commands, device records and validation limits.

When reporting a problem, include the PhoneBot version, phone model, Android version, provider, model, original task and failing step. Remove keys, login credentials and personal information before sharing logs.
