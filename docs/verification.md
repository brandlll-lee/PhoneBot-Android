# Verification

## Required checks

Run from the repository root:

```powershell
npm --prefix runtime run build
npm --prefix runtime test
./scripts/gradle.ps1 assembleDebug testDebugUnitTest lintDebug
./scripts/gradle.ps1 connectedDebugAndroidTest
```

The runtime tests must pass for both source and bundled entry points. They use real Pi sessions, Pi's provider transport and local HTTP fixtures. Temporary homes stay on the project drive. Checks cover original user prompts, all 21 phone tools, ordered thinking/text/tool events, provider login, argument validation, cancellation and one terminal event per task. Session tests import JSON transcripts, trigger official automatic compaction, inspect the summary request, retain original session entries and restore the compacted session after process restart. Cancelled tasks and process loss during a tool call must restore through Pi and accept the next user request. Image tests cover original-byte archives, historical screen references, user images, recall, shared encoding and dimension rejection. Screen and batch transport tests preserve parent IDs, labels, interaction states, changes, conditions and stop reasons.

The JVM tests check available controls for each engine phase, ADB framing and checksums, stream closure, pairing packets, SPAKE2 vectors, and HKDF derivation. Lint treats warnings as errors.
Dependency update advice is disabled because the build uses a pinned, compatible toolchain.

Android tests cover process isolation, a Pi-to-Kotlin round trip, stop, restart, process failure, and recovery. They also cover Pi catalog/authentication, saved model selection, encrypted credential updates/deletion, Chinese input with stable IDs and read-back, ambiguous target rejection, full-screen bottom coordinates, gestures, app navigation, JPEG dimensions/mapping, and Chinese/English OCR. Batch tests check successful execution and early stop for a missing target or dialog. Journal tests cover credential redaction, torn writes, interruption recovery, and terminal states. A real Pi authentication failure must preserve its task journal and transcript.

The controlled page delays a result while its tree stays quiet. Both single and batch actions must wait for their result condition. A matching input value must not satisfy a non-editable result condition. Timeout must stop remaining actions while preserving successful dispatch status. A dense page checks more than 100 visible elements, container labels, and parent IDs. A label present before a no-op click must return `condition_unchanged` and stop batch continuation. A delayed selected-state condition must wait for the actual selection and a stable observation. Markdown tests cover Chinese streaming, unfinished emphasis, completed history rendering, long code, links, and wide tables. Trace tests check Pi content order, returned thinking, live deltas, journal replay, errors, tool input/output, automatic collapse, and a final reply displayed once outside the trace. Notification tests cover terminal labels, permission checks, deduplication, and exact-task navigation on cold and warm activities. Wait for Android's asynchronous notification publication before inspecting its active list.

Empty-tree tests check a valid display geometry, empty observations, and absence conditions. Navigation tests separate successful dispatch from permission to continue a batch. System tests check media-volume read-back, brightness authorization and argument validation, intent units, output limits, and system-command authorization. A test installation with running, authorized Shizuku also checks shell UID, consecutive commands, the ten-second timeout, and Wi-Fi changes. Tests restore volume, brightness, and Wi-Fi values after use. Clock/calendar contract tests capture intents and do not create entries.

Composer tests use an edge-to-edge activity with resize behavior, as the main activity does. They check model selection, expanded input, the five-line height cap, rejected submission, accepted submission, focus removal, and actual IME insets before and after Send.

Session settings tests use separate storage. They check old-session defaults, saved helper/screen choices, new-session defaults and restored choices. Composer tests also check screen selection and disabled controls during execution.

`PiSessionTest` runs the packaged engine through the native Node bridge in isolated test storage. A model-stream fixture triggers official compaction. It checks JSON import, persisted summary and tool result, continuation, all 21 tools and clean shutdown. It uses fixture credentials and does not read or change the configured model accounts. Run this class once per instrumentation process because Node permits one embedded instance.

`ConversationSettingsTest` opens the actual app and its conversation settings. It presses both helper choices and both screen choices, checks the selected labels and model values, and restores the original session configuration. It also checks that engine/version status rows have no click action. It preserves provider credentials and global defaults.

`PhoneDisplayTest` needs Android 14 and connected helpers. Run its four `shizukuMain`, `shizukuVirtual`, `builtinMain`, and `builtinVirtual` methods after authorization. A disconnected helper causes a skip, not a pass. Check instrumentation status codes. Each combination checks the actual helper UID, display geometry, Chinese input, actions, screenshots and OCR. Virtual runs also check the main task and release the previous session display. Other methods check built-in helper death and reconnection, rejected app-UID handoff, cancelled display creation, and replacement through the virtual clipboard input path. `pairBuiltin` accepts temporary `pairPort` and `pairCode` instrumentation arguments. Keep these out of saved command output and reports.

`screenOffVirtual` turns off the physical screen. It checks virtual input, taps, swipes, screenshots and OCR, then wakes the phone. An optional HTTPS `networkUrl` argument compares the response status before and after screen-off. This short test does not establish deep-Doze or long-running cloud reliability. Reboot recovery and Android 17 local-network permission require separate device acceptance.

`PhoneDisplayTest#floatingVirtualPreview` checks preview aspect ratio, drag gestures, system-bar clearance, main focus, Chinese input, screenshots, collapse and removal. `floatingMainBubble` checks the main-screen circle, tree isolation, screenshot/OCR isolation and actions while the window is present. `floatingThemeFollowsClientAndReconnect` checks appearance IPC and reconnection. These tests use controlled pages and preserve model credentials. Run the floating-window tests at normal font scale and at 1.3. Restore the phone's font scale after the run.

`ThemeTest` renders chat, Markdown, menus and the composer in both palettes. It checks the actual background pixels and system appearance changes. It saves local PNGs for visual inspection. `ConversationSettingsTest#appearanceChangesPersistAndUpdateSystemBars` checks the real settings page, saved selection and status-bar icons. It restores the original appearance.

`TraceTest` opens 200 tool steps and checks that only nearby rows are composed. It scrolls to the last result and collapses the trace. Its press test waits for native interaction feedback, checks blue text and verifies that the background stays unchanged. `PresentationTest#replyStreamsAndMovesToolTextIntoTheTrace` checks streamed reply text, tool-call transitions, finalization and cancellation through the same event projection used by journal replay.

Run the full suite on a test installation. The authentication test changes provider credentials and the default model. For the user's configured phone, select only the controlled page test:

```powershell
adb -s DEVICE shell am instrument -w -e class dev.phonebot.android.PhoneToolsTest#accessibilityReadsActsVerifiesChineseAndRecognizesPixels dev.phonebot.android.test/androidx.test.runner.AndroidJUnitRunner
```

This test opens the real main activity and starts the engine before it opens the debug fixture. Instrumentation restarts the target process, so the fixture refreshes only PhoneBot's accessibility binding. It preserves other enabled services and restores the prior setting. It does not call a cloud model. `PiSessionTest`, `ComposerTest`, `SystemToolsTest`, `PresentationTest`, `TaskNavigationTest`, and `EngineIntegrationTest#runtimeRoundTripAndProcessLifecycle` can also run on a configured phone. They preserve provider credentials and model selection. Keep the phone awake and unlocked during UI tests. A locked-screen failure does not establish an application defect.

## APK inspection

Use SDK Build Tools `zipalign -c -P 16 -v 4` on the APK. Inspect native `LOAD` segments with the NDK's `llvm-readelf -l`. Each bundled native library must have at least `0x4000` segment alignment.

The APK must contain `libnode.so` and `libphonebot.so` for both configured ABIs. It must contain the runtime bundle, its version hash, and license notices.

## Device acceptance

Start the app from its launcher. Open Settings and run self-check. Check chat, drawer, settings, provider authentication, and permissions. Check a compact display with 1.3 font scale. Open the keyboard and confirm that the composer stays visible. Keep the app in the background and confirm that the foreground notification remains. Use its Stop action and confirm that the engine process ends. Start it again and run self-check.

Use real provider credentials to validate cloud requests. Keep keys out of command output, test reports, and committed files. Report cloud verification separately from local fixture verification.

The lifecycle test deliberately kills the engine process. Accessibility runs in that process. On the S21, Android can retain that service in its crashed-service set while its enabled setting remains present. Before another phone-tool run, switch only PhoneBot's accessibility service off and on in system settings. The validation run restored that binding after fault injection. Normal task cancellation aborts Pi and tool work; it does not kill the process. Process ownership and automatic service recovery need a separate lifecycle change.

## Validation record

Validated on 2026-11-01 for themes and live task UI:

- Runtime: 22 tests passed for source and bundled Pi entry points, including forwarded text and tool-call events. JVM: 7 tests passed. APK builds, lint, ZIP alignment and bundled-runtime hash checks passed.
- Samsung S21, Android 14, ARM64: 16 selected tests passed. They covered saved appearance, system bars, chat palettes, streamed reply bubbles, trace transitions, Markdown, composer behavior, exact-task navigation, floating windows and appearance IPC. The 200-step trace composed 9 tool rows after expansion; the previous layout composed all 200. The press test checked blue text with an unchanged background.
- Eight selected tests also passed at 1.3 font scale. Screenshots of both palettes, the execution trace and virtual preview before and after Chinese input were inspected. The preview retained a continuous blue outline. The tests restored font scale. The APK was installed as an update and model credentials were preserved.

These checks used controlled pages and local model fixtures. A cloud-backed third-party app task and release-build frame-rate profiling were not run.

Validated on 2026-11-01 for floating task previews:

- Runtime: 22 tests passed. JVM: 7 tests passed. `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed. APK ZIP alignment passed.
- Samsung S21, Android 14, ARM64: 11 selected tests passed across grouped runs. They covered both assistants on both screen modes, draggable controls, virtual preview sizing and enlargement, main-screen progress, Chinese input, focus preservation, screenshot and gesture isolation, Stop, conversation navigation, Markdown and execution traces.
- The two floating-window tests also passed at 1.3 font scale. Actual screenshots were inspected. The enlarged card stayed above the system navigation bar. The tests restored font scale and used controlled pages. The APK was installed as an update and existing model credentials were preserved.

A cloud-backed third-party app task was not rerun for this feature.

Validated on 2026-11-01 for the official Pi session SDK:

- Runtime: 22 tests passed. Source and bundled engines imported existing JSON messages, triggered Pi's automatic compaction, retained original entries, restored summaries after process restart and called phone tools after compaction. Cancellation and process loss during a tool call accepted the next user request through Pi. Actual provider payloads retained the system message and all 21 tools. Restored screen images became archive references while original bytes remained available.
- JVM: 7 tests passed. `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed. APK ZIP alignment passed. The APK runtime hash matched the generated bundle.
- Samsung S21, Android 14, ARM64: 7 selected tests passed on the updated APK. They covered packaged SDK migration, automatic compaction, continuation, native tool results, engine restart and process failure, Chinese input, OCR, screenshots, ordered execution traces, Markdown and notifications. SDK session tests used isolated storage and fixture credentials. Existing model accounts were preserved.

These checks use local model fixtures and controlled pages. A cloud-backed third-party app task was not rerun for this migration.

Validated on 2026-11-01 for conversation settings:

- The actual-page regression test reproduced disabled helper rows before the fix. On the updated S21 APK, all four helper and screen choices accepted clicks and showed the selected value. The test checked session values and restored the saved configuration. Read-only engine and version rows exposed no click action.
- Samsung S21: 4 selected tests passed. They covered conversation settings, screen selection during idle and execution, composer behavior, and session persistence. Provider credentials and global defaults remained intact.
- JVM: 7 tests passed. `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` passed. The APK was installed as an update.

Validated on 2026-11-01 for session screens and device helpers:

- Runtime: 23 tests passed. Four real-Pi task entries retained all 21 tools. The fourth used virtual-screen instructions in the same restored session. Context-pressure checks retained Pi's system message and tool declarations.
- JVM: 7 tests passed. APK builds and lint passed.
- Samsung S21, Android 14, ARM64: built-in ADB TLS pairing succeeded on the phone. Both assistants ran the controlled page on the main screen and on a virtual screen. The tests checked Chinese input, screen coordinates, clicks, swipes, JPEG capture, OCR, navigation, main-task preservation and session-display release.
- S21 recovery and permissions: rejected an app-UID helper handoff, killed the built-in helper, confirmed its display release, and reconnected to a new helper and display. Cancelling display creation left no allocated PhoneBot displays.
- S21 screen-off: virtual Chinese input, taps, swipes, screenshots, OCR and an HTTPS request passed while the physical screen stayed off. The fixture held a bounded partial wake lock, as virtual tasks do. The HTTPS probe used the public DeepSeek base URL without credentials.
- S21 regression: 24 selected tests passed across grouped runs: 12 UI/system tests, one engine process-lifecycle test, one controlled phone-tool test, and ten helper/display tests. Pairing passed in an additional run. The focus check compared the actual main-display window before and after virtual input, confirmed top focus on display 0, and rejected moving its active app. Tests preserved model credentials. The APK was installed as an update. ZIP alignment passed.

These checks use controlled pages and local model fixtures. They do not establish success for every third-party app. Deep Doze, actual reboot recovery, and an Android 17 local-network permission prompt were not tested on this Android 14 phone.

Validated on 2026-11-01:

- Runtime tests: 23 passed for source and bundled entry points. Three tasks saved and restored the same session through the actual engine entry. Their HTTP requests retained the system prompt and all 21 tools. A real Pi loop made 12 tool calls and 13 HTTP requests under context pressure. Compression retained the system message, tool declarations, current goal, live tool pairs, and a full diff baseline. Historical replies were marked, old targets left the request view, and original transcripts remained intact. Screen and batch results retained hierarchy, labels, interaction states, changes, conditions and stop reasons.
- JVM tests: 1 passed. Android instrumentation: 16 passed on the API 36 x86-64 emulator.
- Samsung S21: the controlled phone-tool test passed on Android 14, API 34, ARM64. It checked more than 100 visible elements, parent IDs, container labels, unchanged conditions, delayed selection, dispatch status after a condition timeout, Chinese input, OCR, screenshots, and navigation. It preserved provider credentials and model selection. The final APK was installed as an update. Its extracted runtime hash matched the built bundle.
- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` passed. Lint reported no issues. APK ZIP alignment passed.

These checks use local fixtures. The cloud-backed music task was not rerun in this validation.

Validated on 2026-10-31:

- Runtime tests: 21 passed. Source and bundled entry points completed the two-request tool loop with one terminal event per task. Real task commands preserved the user's message and ended on Pi's normal final reply. Returned thinking and text retained their request and content index. Provider authentication used test credentials and the host store. Two actions used one batch dispatch. Context tests covered image and token budgets, retained prefixes, reconstructed baselines with image notes, archived references, recall, and interrupted tool results.
- JVM tests: 1 passed.
- Android instrumentation: 16 passed on Android 16, API 36, x86-64, with 4 KB memory pages. The controlled page test covered stable IDs, diffs, bottom coordinates, image mapping, OCR, delayed conditions, standard settings intents, unknown navigation, and batch early stop. System tests covered empty trees, volume, brightness, clock/calendar intent contracts, output limits, and authorization. Composer tests covered layout and keyboard dismissal. Presentation tests covered Pi message ordering, trace collapse, journal replay, Markdown, historical PNG encoding, and three terminal notification labels. Navigation tests covered cold and warm task opening. A failed Pi task retained both its journal and transcript.
- Authorized system commands: the emulator used Shizuku 13.6.0 with shell UID 2000. Engine-process and main-process commands passed. Consecutive commands, timeout, and actual Wi-Fi changes passed. The test restored the prior Wi-Fi and brightness state. The S21 covered the unavailable-connection path; privileged commands were not run on that phone.
- Notification denial: 2 selected emulator tests passed with notification permission revoked. Terminal state recording and normal engine shutdown remained available. Three terminal labels and deduplication passed with permission granted. Android's active notification list was checked after asynchronous publication.
- Large text: the Markdown streaming test passed at 1.3 font scale with unfinished Chinese emphasis, a long highlighted code line, and a wide table.
- Samsung S21: 11 selected instrumentation tests passed on Android 14, API 34, ARM64. They covered model selection, input growth and height limits, accepted-send keyboard dismissal, empty-tree observations, system-tool contracts and permission checks, volume read-back, delayed result conditions, input/result separation, bottom-screen taps, Chinese input, OCR, JPEG mapping, navigation, Markdown, result notifications, and exact-task opening. The APK was installed as an update. Existing credentials and model selection were preserved. These tests use local fixtures.
- Samsung S21 presentation update: all four presentation tests passed with the current APK. They covered the ordered Pi trace, separate final reply, journal replay, automatic collapse, tool input/output, Markdown, image encoding, and result notifications. Installation updated the existing app. The tests kept provider credentials and model selection intact.
- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug`: passed through the project wrapper. Instrumentation ran through ADB with explicit device selection.
- Lint: no issues.
- APK ZIP alignment: passed `zipalign -c -P 16 4`.
- Native ELF alignment: all packaged library `LOAD` segments use `0x4000` alignment.
- APK inspection: both configured ABIs contain Node and the JNI host. The runtime bundle and notices are present.
- Manual UI: chat, drawer, settings, provider list, and 1.3 font scale were inspected. The composer remained visible above the keyboard on a 960 × 1800 display. Seeded chat messages checked layout only. They are not cloud-task evidence.
- Composer UI: the current dark chat was inspected with compact input and a long draft. The model selector stayed above the panel. Five text lines scrolled above the bottom-right Send control. The keyboard left both controls visible.
- Markdown UI: a seeded completed reply was inspected in the actual dark chat. Headings, bold Chinese, lists, quotes, inline code, a highlighted code block with Copy, and a table rendered in the shared answer layout. The fixture was removed after inspection.
- Execution trace UI: the shared chat-turn view was inspected with Pi-shaped thinking, text, and tool events. The expanded view kept the vertical timeline and tool input/output inside the trace. The completed view collapsed the trace and displayed the final Markdown once outside it. Images used local test data.

Real cloud task latency and model batching choices, Android API 28/37 devices, and a device with 16 KB memory pages were not part of this run. Request-count checks use a local model fixture. Node's Android distribution remains a pinned prerelease.
