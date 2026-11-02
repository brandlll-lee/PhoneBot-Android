# Runtime

## Process ownership

`MainActivity` uses `EngineClient` to send commands through Android `Messenger`. `EngineService` is private to the app and runs in `:engine`. The service starts from a foreground user action. It owns the foreground notification and uses `START_NOT_STICKY`.

The service starts one Node instance on a worker thread. JNI passes two pipe file descriptors to the bundled JavaScript entry point. Node reads JSON lines from one pipe. Kotlin reads events from the other pipe. Each channel is private to the app process pair. The bridge does not open a command server.

`PhoneAccessibilityService` also runs in `:engine`. The system binds it after user authorization. The engine uses this service for screen and action tools. The main process holds the chat UI. Messenger forwards non-secret task events and interactive authentication prompts to it.

## Lifecycle

The phases are `STOPPED`, `STARTING`, `READY`, `RUNNING`, `STOPPING`, and `FAILED`. A model failure returns the engine to `READY` with an error message. A native startup failure or an unexpected process exit reports `FAILED`.

Startup has a 20-second deadline. A connection check and each single host tool have 30-second deadlines. `do_steps` has a 90-second deadline and accepts at most 12 actions. Pi owns argument schema validation, request streaming, and the sequential agent loop.

Task cancellation aborts the active Pi request and pending host waits. Kotlin cancels tool work and rejects later calls for that task. Each tool carries a task ID and checks that task's cancellation before an Android action. A dispatched system gesture can finish; cancellation cannot undo it.

Engine shutdown allows three seconds before ending the private process. Node supports one instance per process, so each restart uses a new process. The client completes disconnection before it enables Start. Binding uses `BIND_AUTO_CREATE` so service creation also covers a restart during process removal. The engine replays each active task's journal for UI reconnection.

JNI sets `HOME`, `TMPDIR`, and the working directory before `node::Start`. Node receives a 256 MiB V8 old-space limit. This limit does not cap total native memory. TLS uses Node's bundled trust roots. The app keeps certificate verification enabled.

## Bundling

`scripts/prepare-runtime.mjs` prepares the native libraries and calls `runtime/build.mjs`. esbuild creates one ESM bundle. Pi's `ModelRuntime` supplies the provider collection. Pi's static OAuth and Bedrock loaders include implementations that use opaque dynamic imports. All provider SDK code stays in the generated bundle.

The build pins Pi packages through `runtime/package-lock.json`. Node comes from the `gmaclennan/nodejs-mobile` maintainer's `v24.18.0-0` release. This release is marked **prerelease**. It is an Android build of Node, not a desktop Node binary. The archive contains matching headers and native libraries. Its SHA-256 is checked before extraction.

The selected Node build supplies `fetch`, TLS, and small ICU data. Full locale data is outside this runtime package. Pi's package metadata requires Node 22.19 or later. The older Node 18 mobile build does not meet that requirement.

The APK contains npm license notices and Node's full license notice. Native artifacts are generated locally and stay outside Git.

## Providers and storage

Pi owns provider names, model IDs, API protocols, login prompts, browser URLs, token exchange, and token refresh. Android renders Pi's text, secret, select, and manual-code prompts. OAuth uses the browser and Pi's own callback or device-code flow. PhoneBot does not replace these flows with an OpenAI-compatible adapter.

The `CredentialStore` bridges reads and writes to Kotlin. Pi serializes credential updates per provider. Kotlin stores AES-256-GCM ciphertext in `noBackupFilesDir`. Each write uses a fresh IV and `AtomicFile`. The encryption key remains in Android Keystore. Credentials travel through private pipes and Binder messages. They stay out of chat events and preferences.

Provider/model defaults and connection-check timestamps use private preferences. Connection status means a successful request for that exact provider/model with the current saved credentials. Changing or deleting credentials clears its verification timestamps. A catalog entry alone does not establish Android compatibility. Desktop credential-file and ambient IAM flows need installation-specific setup and separate validation.

Pi's dynamic catalogs use an app-private `ModelsStore`. The UI reads catalog pages and searches through Pi's current model collection. Saved catalogs restore without network access. Model refresh is explicit.

## Phone tools

The host entry point validates task identity, cancellation, permission state, and the execution deadline. Missing accessibility or a locked display pauses the pending tool. The UI rechecks permissions on resume. The user resumes the task explicitly.

The system prompt directs Pi to check the user's target, scope, hard requirements and exclusions. Follow-up requests retain applicable conditions. Each item's values and qualifiers stay attached to that item. Pi chooses entry points from the current page and uses parent/child labels and interactive states to interpret controls. It scrolls relevant lists when the target is outside the visible items. Insufficient tree data calls for OCR or a screenshot. An action with no progress calls for a different target or method. Final replies stay within the checked scope and distinguish a target not found from confirmed absence. Pi owns the decisions and the execution loop.

`ScreenState` owns display geometry, element identity, snapshots, and diffs. Coordinates use the rotated main display's logical pixels, including system bars. Display size comes from `Display.getRealSize`, not application resource metrics. This use concerns gesture coordinates, not UI layout. The snapshot separately reports full display, active window, and root content bounds. Rotation or a window change resets the element registry. Screenshot metadata maps the encoded image back to screen pixels. OCR boxes use the same mapping.

`screen_read` returns all visible elements exposed by the active accessibility tree. Each element has a monotonic ID, parent ID, resource ID, label, bounds, and interaction state. Empty container labels collect child labels. Elements include scrollable, selected, and checked state. The same complete collection supplies target resolution and conditions. Current IDs remain usable even when the retired-ID cache rotates. The registry matches refreshed nodes and semantic features. Editable text is excluded from identity. Unrelated element changes preserve the target's ID. Actions refresh and validate the target before dispatch. Ambiguous or missing targets require a new read. Password nodes hide their content.

Actions return a diff against the last presented snapshot. Diffs name `baseSnapshot` and contain added, removed, and updated elements. A window change or major replacement returns a full snapshot. Explicit reads return full snapshots. The model must request a full read if its baseline is absent.

`screenshot` captures the selected display. Main-screen capture uses Android's accessibility API. Virtual-screen capture uses an `ImageReader`. Both sources use the same bundled Chinese/Latin OCR, JPEG quality 80, 1280-pixel limit, coordinate mapping and image budget. The engine initializes ML Kit explicitly. OCR removes text already present in the tree. Bitmap ownership lasts until each ML Kit task completes. Rotation changes during capture require a new read. Images stay in private files and reach Pi as image blocks for vision models.

`PhoneActions` executes single actions and `do_steps` through the same action, wait, and check path. Tap prefers a node action. Main-screen gestures use accessibility. Virtual-screen gestures and Back/Home keys carry their display ID through the helper. Input prefers `ACTION_SET_TEXT`. The main-screen fallback uses Android 13+ accessibility input. The virtual fallback focuses the target node, selects existing text, and pastes through a display-targeted key. It restores the prior clipboard if it still owns the inserted value. Both paths check read-back.

`ScreenState` reads application windows from the selected display. Its geometry, element identity, diffs and quiet-event checks use that display. Only main-screen reads can fall back to `rootInActiveWindow`. A missing root returns `treeReadable:false`, an empty element list, display geometry, and window metadata. Screenshot capture, navigation, app launch and coordinate gestures do not need a root node. Node-targeted actions still need a unique live target. Every observation carries `taskId`, `observedAt` and `geometry.displayId`.

Actions accept an optional `expect` condition and `timeoutMs`. Conditions match package, element ID, exact text, resource ID, editable/enabled/selected/checked state, ancestor resource ID (`withinResourceId`), or absence. Text conditions default to non-editable nodes. Set `editable:true` for an input check. Select destination content, a selected tab, or a result region for navigation. A label that exists on both pages cannot identify the destination. Conditions describe the current action's direct result.

`treeStable` requires repeated matching trees and a quiet event interval. A newly satisfied condition can end the wait after two consecutive matching observations, even when unrelated content changes. A condition that matched before dispatch still requires a quiet tree. `conditionBefore` and `conditionMet` report the selector before and after dispatch. Both are null when no condition was supplied. `changed` compares the observed UI with the pre-action tree. If the condition already matched and the tree remains unchanged, `stopReason` is `condition_unchanged`. A quiet tree or a matching selector alone does not establish business readiness. Results include wait duration and stop reason. Default limits are 2500 ms for tree stability and 8000 ms with a condition; explicit bounds are 250–15000 ms.

`do_steps` reuses each single action's schema. It accepts current element IDs and semantic selectors for subsequent pages. Each step checks cancellation and display availability. `ok` and `executed` report successful dispatch. Condition status and `changed` report observations. `canContinue` controls remaining batch steps. An unmet or unchanged condition stops the remaining steps and returns the observed page to Pi. It does not turn a dispatched action into a tool error. Actual dispatch errors still report failure. Navigation is `none`, `expected`, or `unplanned`. An unplanned destination or a new standard dialog stops the batch for review. Clicks, swipes, and generic intents need a confirmed postcondition before another batch step can run. Input read-back and global navigation can continue after a stable observation. Each substep produces its own timeline entry. Custom in-app overlays without dialog semantics can require model review.

## System capabilities

`PhoneSystem` runs separately from accessibility-based actions. `device_status` returns actual media volume and mute state, brightness mode and value, Wi-Fi state, local time zone, observation time, and shell availability. `volume_set` uses `AudioManager`. `brightness_set` validates its arguments before changing `Settings.System` and requires `canWrite`. Both return actual values after the write.

`wifi_toggle` uses `svc wifi` through the session's selected helper and waits for the actual Wi-Fi state. Without that connection, it opens the Android Wi-Fi panel, or Wi-Fi settings on API 28. An opened panel returns `verified:false`. Device volume, brightness, network, app data and raw shell commands remain device-wide.

`phone_set_alarm`, `phone_set_timer`, and `phone_add_calendar_event` use standard clock and calendar intents. They return `opened` and `verified:false`. The agent must inspect and confirm the resulting state. Android can require user confirmation or have no app for the requested action.

`PhoneIntents` validates actions, URI schemes, target packages, and scalar extras before dispatch. `app_send_intent` accepts an Android action. `phone_open_link` accepts supported links or a listed standard settings action. App-detail settings require a `package:` URI. No activity is started for invalid arguments.

`PhoneShell` returns one `ISystemShell` interface for built-in ADB and Shizuku. `SystemShellService` owns commands, virtual displays and input dispatch. Shell commands have a ten-second limit and a 32768-character output cap. Cancellation terminates the child. Destruction releases displays and ends the helper process. Command success does not establish a setting's state.

## Session screens and helpers

`ChatSession` stores each Bot's user-defined name, avatar color, provider/model, helper and screen in the existing chat file. Existing IDs and history are retained. Empty Bots are saved. New Bots inherit saved screen/helper defaults. Task start fixes the Bot's selection. Task events include its identity, helper, mode and actual display ID. Pi receives the current screen mode in its system prompt.

Chat saves capture an immutable list after the existing debounce. JSON encoding runs on `Dispatchers.Default`. UTF-8 conversion and atomic writes run on IO under the existing mutex. Cancelling an older save prevents its encoded snapshot from replacing a newer save.

`PhoneDisplay` owns one lazy virtual display per running Bot. It needs Android 14 or later. The shell creates a trusted display with independent focus, no top-focus stealing, local IME and its own display group. Launch intents carry `ActivityOptions.setLaunchDisplayId`. A target app already visible on the main screen is rejected. If a virtual task moves to the main screen, later phone actions pause for user review. The virtual path does not fall back to main. Task completion, cancellation and engine shutdown release that task's display. Viewing another Bot does not change an execution target. Helper death invalidates its Binder and the display. Recovery creates a fresh display; old element IDs are not reused.

The display's Binder death handler closes its ImageReader and frame thread. Cancellation during creation closes the new display before returning. ML Kit initializes once in the engine application's startup. Accessibility reconnection creates recognizers as needed and closes them on service destruction.

Main-screen tools need an unlocked, interactive phone. Virtual-screen tools check the target display and helper instead. A virtual task holds a bounded partial CPU wake lock. Permission pauses, cancellation and terminal events release it. This lock does not keep the physical panel on. It does not exempt the app from Doze or vendor network restrictions.

### Built-in ADB

Android 12+ supplies the platform TLS exporter. Pairing uses TLS 1.3, SPAKE2 and AES-GCM on a loopback socket. Pairing and connection ports are separate. NSD resolves this device's service; a saved connection port can be retried. Pairing codes stay in memory. The RSA identity is encrypted with an Android Keystore AES key in `noBackupFilesDir`.

ADB uses self-signed certificates rather than web PKI. Pairing authenticates the code with the TLS exporter and SPAKE2. Subsequent ADB connections authenticate the saved client public key. The implementation only accepts loopback addresses and checks the self-signed certificate's signature. It does not use the app's cloud TLS policy.

Bootstrap passes a fresh nonce over stdin. The helper checks the installed APK signature. Its exported provider requires `INTERACT_ACROSS_USERS_FULL`. Handoff verifies shell UID/PID, current APK path, one-time nonce and Binder identity. The owner Binder's death ends the helper. App updates must start a helper from the new APK.

State distinguishes unpaired, permission required, wireless debugging off, connecting, ready and disconnected/error. Binder death, network availability and wireless-debugging changes trigger bounded recovery. No recurring polling runs. Android 17 requires local-network runtime permission. Optional boot recovery needs saved pairing, Wi-Fi, wireless debugging and user-approved `WRITE_SECURE_SETTINGS`. It attempts recovery within a bounded broadcast window. Android can still reclaim an idle app process.

### Shizuku

Shizuku API/provider 13.1.5 supplies the other transport. The application sets each process's provider role before provider creation. The engine requests the main process's Binder. The user starts Shizuku and grants access. A non-daemon user service stays bound across commands and virtual-screen operations. It ends with its owner or explicit engine shutdown.

## Local task preview

`PhoneAccessibilityService` owns one `TaskOverlay`. `EngineService` supplies the viewed running Bot's identity and display. With other Bots still running, a terminal event selects another active preview. Engine shutdown removes the window. The overlay shows a logo circle or virtual screen. Main-screen taps open the recorded conversation. Appearance travels through the existing Messenger connection and reconnect handshake. It does not enter Pi's command stream.

`PhoneDisplay.previewFrames` reads the existing virtual display's `ImageReader`. It emits changed frames at most five times per second with a width of at most 360 pixels. Bitmap copying and scaling run on a worker dispatcher under the existing image lock. Full screenshots and OCR retain their original dimensions. The Compose subscription exists only while the card is expanded. Preview frames stay in the engine process and are not encoded, saved or sent to the model.

The accessibility overlay uses `FLAG_NOT_FOCUSABLE` and a window sized to its content. Compose receives lifecycle and saved-state owners from its service host. Detaching the window disposes its composition. Main-screen capture and gestures hide the overlay and wait two display frames before dispatch. The window remains available during postcondition waits. Accessibility overlay events do not reset screen-stability timing.

Platform references: [accessibility overlay windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY), [ComposeView owners and disposal](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ComposeView), and [ImageReader frame ownership](https://developer.android.com/reference/android/media/ImageReader).

## Task completion

The runtime passes the user's original message to `session.prompt`. Pi runs tools and returns their results to the model. Its final reply ends the loop. Pi saves session entries during execution. PhoneBot publishes the final reply after the SDK settles. Model errors and cancellation use their own terminal events. A result marks the end of execution, not independent certification of the user's goal.

Input, system settings, and action postconditions are checked by their tools. The model reads these results and explains the outcome in its final reply. Task IDs identify IPC calls, journal records, and cancellation. Conversation history remains context for the next user message.

Pi's `turn_start` supplies the request sequence. `message_update` forwards `text_delta`, `thinking_delta` and `toolcall_start` with Pi's content index and tool-call presence. `message_end` supplies the settled content blocks. Tool execution events retain Pi's call IDs and registered labels. Android streams reply text in a bubble and moves it into the trace when Pi starts a tool call. The terminal result identifies and commits the final reply. Thinking and earlier text stay in the trace. Live events and journal replay use the same projection. This display state does not control Pi's loop or completion. The runtime keeps `thinkingLevel` at `off`; providers that still return thinking blocks can display them.

## Task records and model context

`files/task-events/<taskId>.jsonl` is the task journal. Kotlin appends and syncs each event. Runtime stream events are saved on the pipe reader thread before UI delivery. Records include task/session identity, model request start, HTTP response status, first stream event, response duration, token/cache usage, payload size, tool arguments, complete native results and substep outcomes. Authentication traffic stays outside this journal. Known credential values and sensitive fields are redacted before persistence. The journal contains page data and remains app-private.

On engine restart, unfinished journals receive an `interrupted` event. Recovery ignores a torn final line and truncates it before appending. The UI rebuilds task turns from these records. Conversation deletion also removes associated journals.

`files/sessions/<session>.jsonl` is the official Pi session. `SessionManager` owns persisted messages and the model context. `createAgentSession` restores it for each task. Existing JSON transcripts are imported in order through `SessionManager`. Import uses a temporary file and publishes the new session before removing the old file. The chat UI and task journal keep their existing formats. Conversation deletion removes the Pi session, image archive and task journals.

Each active Bot has an independent official `AgentSession`. Node's `AsyncLocalStorage` binds model credential callbacks to the correct task. Tool request IDs include the task ID. Cancellation and terminal events affect that task only. One Bot runs one task at a time.

`AgentSession` owns the execution loop, cancellation, provider retries and automatic compaction. PhoneBot registers its phone tools as custom tools and uses sequential execution. The SDK's built-in desktop tools and resource discovery are disabled. The current screen instructions come from the Android session. Model authentication uses Pi's `ModelRuntime` with the Android encrypted credential store.

Pi uses its default compaction settings. It summarizes earlier context and retains recent messages when the model context approaches its limit. The session file retains original entries and the compaction entry. Session recovery and subsequent compaction use Pi's canonical projection. System messages and tool declarations stay under Pi's control. Context summaries come from the configured model. A summary may need an additional model request.

`images.mjs` uses the SDK's `context` extension event for image preparation. It archives original bytes under `files/images/<session>/<sha256>.source`. Previous tasks' tool images become text references. User reference images remain visible. `image_recall` restores a referenced image when needed. Current tool images, recalled images and user images pass through the same Android JPEG encoder and dimension checks. Encoded images have a longest edge of 1280 and quality 80. Per-task caches preserve the request prefix. Text history and compaction stay with Pi. The provider owns its request limits.

The runtime publishes one terminal result after `session.prompt()` settles. Compaction and automatic retry events enter the task journal. Compaction events contain status and token counts; the full summary stays in the Pi session file. The service records notification attempts by task ID after the terminal event. Live user-task terminal events can post a result notification; event replay does not. Shutdown and interruption use the same notification path.


## Parallel phone resources

Each task owns its display, `ScreenState`, element registry and `PhoneActions`. The accessibility service supplies platform APIs and per-display event times. Independent virtual displays run concurrently. A main-screen task holds the main-screen mutex for its full lifetime. Another main-screen task waits before Pi starts.

App UI ownership uses the resolved package name. Switching App releases the previous package before waiting for the next. Queue waits stay outside action deadlines. Single actions retain a 30-second execution deadline; batches share 90 seconds of execution time. A shared clipboard mutex covers display-targeted paste across both helper transports. Shell execution and cancellation use a unique request ID. Finishing one task releases only its own resources. App data and accounts remain shared by Android.

## Verification

Self-check starts an HTTP fixture on a random loopback port. Pi's real OpenAI transport makes the first request. The fixture returns a streamed tool call. Kotlin returns runtime information. Pi sends a second request with that tool result. The fixture returns the final answer. The check fails if either request or the tool result is missing.

The fixture provides a deterministic model response. It tests runtime compatibility and the request/tool bridge. It does not establish cloud provider compatibility.

Connection verification uses the selected Pi model and saved credentials. It sends a short text request. Real tasks use the same model runtime and an official Pi session restored from its JSONL file.

## Primary sources

Checked on 2026-10-31. Read the linked source again when changing the related dependency or API.

Rechecked on 2026-11-01: Pi's session SDK, model runtime, automatic compaction and session persistence. Android node parent/child, selected, checked, scrollable and action-result APIs remain unchanged.

Pi 1.4.0's [Agent initialization](https://github.com/earendil-works/pi/blob/v1.4.0/packages/agent/src/agent.ts) inserts the leading system message. Its [transcript functions](https://github.com/earendil-works/pi/blob/v1.4.0/packages/ai/src/utils/transcript.ts) carry tool declarations in system messages and resolve them in transcript order.

- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes): Gradle 9.4.1, built-in Kotlin, NDK, and supported API level.
- [Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler): Compose compiler plugin and Kotlin version alignment.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `specialUse`, its permission, and its manifest subtype. Store submission requires a separate use-case review.
- [Android 17 target behavior](https://developer.android.com/about/versions/17/behavior-changes-17): current target API behavior changes.
- [Bound services](https://developer.android.com/develop/background-work/services/bound-services): Messenger IPC and service connection callbacks.
- [Service manifest](https://developer.android.com/guide/topics/manifest/service-element): private process names and exported service control.
- [Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes): ELF and APK alignment requirements.
- [Pi Agent Core 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/agent): agent lifecycle and tool execution.
- [Pi session SDK](https://github.com/earendil-works/pi/blob/v1.4.0/packages/coding-agent/docs/sdk.md): session factory, custom tools, resource loading and model runtime.
- [Pi compaction](https://github.com/earendil-works/pi/blob/v1.4.0/packages/coding-agent/docs/compaction.md): automatic thresholds, summaries, retained messages and overflow recovery.
- [Pi SessionManager](https://github.com/earendil-works/pi/blob/v1.4.0/packages/coding-agent/src/core/session-manager.ts): JSONL persistence, canonical projection and session restoration.
- [Pi AI 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/ai): model providers, authentication, and request APIs.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): encryption-key generation and storage.
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService): window access, screenshots, gestures, and global actions.
- [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo): node text, bounds, actions, and refresh.
- [Common intents](https://developer.android.com/guide/components/intents-common): clock, timer, calendar editor, and standard settings intents.
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager): media stream volume and mute state.
- [Settings.System](https://developer.android.com/reference/android/provider/Settings.System): brightness values, modes, and write authorization.
- [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager): Wi-Fi state and restrictions on direct toggling.
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API): binder permissions, multiprocess support, user-service lifecycle, and destroy transaction.
- [ADB Wi-Fi protocol](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md): pairing, TLS identity, discovery and separate ports.
- [Android TLS exporter](https://developer.android.com/reference/android/net/ssl/SSLSockets): platform keying-material export from API 31.
- [Multi-display launch](https://source.android.com/docs/core/display/multi_display/activity-launch): launch options and task placement.
- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission): Android 17 runtime authorization.
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby): power and network limits after screen-off.
- [Compose text input](https://developer.android.com/develop/ui/compose/text/user-input): text field line limits, IME actions, and input behavior.
- [Display geometry](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point)): rotated logical display bounds and the distinction from UI window metrics.
- [Accessibility input method](https://developer.android.com/reference/android/accessibilityservice/InputMethod): Android 13+ input connection.
- [Package visibility](https://developer.android.com/training/package-visibility/declaring): launcher intent queries.
- [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android): bundled Chinese and Latin recognizers and bitmap lifetime.
- [ML Kit initialization](https://developers.google.com/android/reference/com/google/mlkit/common/MlKit): explicit SDK initialization.
- [Compose drawer](https://developer.android.com/develop/ui/compose/components/drawer) and [bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets): native navigation surfaces.
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets-ui): consume Scaffold padding before applying keyboard insets.
- [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels) and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): banner importance, user control, and runtime authorization.
- [Markdown renderer 0.46.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.46.0/README.md): Material 3 rendering, streaming state, retained parsing state, and code highlighting.
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin 2.4.21 matches the Markdown dependencies and the pinned AGP and Gradle versions.
- [Node Android release](https://github.com/gmaclennan/nodejs-mobile/releases/tag/v24.18.0-0): binary provenance and release status.
- [Node mobile embedding](https://github.com/gmaclennan/nodejs-mobile/blob/main/docs/EMBEDDING.md): Android environment setup and one-instance restriction.
- [Node embedding API](https://nodejs.org/api/embedding.html): native entry points and version-specific API compatibility.
