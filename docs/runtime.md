# Runtime

## Process ownership

`MainActivity` uses `EngineClient` to send commands through Android `Messenger`. `EngineService` is private to the app and runs in `:engine`. The service starts from a foreground user action. It owns the foreground notification and uses `START_NOT_STICKY`.

The service starts one Node instance on a worker thread. JNI passes two pipe file descriptors to the bundled JavaScript entry point. Node reads JSON lines from one pipe. Kotlin reads events from the other pipe. Each channel is private to the app process pair. The bridge does not open a command server.

`PhoneAccessibilityService` also runs in `:engine`. The system binds it after user authorization. The engine uses this service for screen and action tools. The main process holds the chat UI. Messenger forwards non-secret task events and interactive authentication prompts to it.

## Lifecycle

The phases are `STOPPED`, `STARTING`, `READY`, `RUNNING`, `STOPPING`, and `FAILED`. A model failure returns the engine to `READY` with an error message. A native startup failure or an unexpected process exit reports `FAILED`.

Startup has a 20-second deadline. A connection check and each single host tool have 30-second deadlines. `do_steps` has a 90-second deadline and accepts at most 12 actions. Pi owns argument schema validation, request streaming, and the sequential agent loop.

Task cancellation aborts the active Pi request and pending host waits. Kotlin cancels tool work and rejects later calls for that task. Each tool checks cancellation before an Android action. A dispatched system gesture can finish; cancellation cannot undo it.

Engine shutdown allows three seconds before ending the private process. Node supports one instance per process, so each restart uses a new process. The client completes disconnection before it enables Start. Binding uses `BIND_AUTO_CREATE` so service creation also covers a restart during process removal. The engine replays the current task's journal for UI reconnection.

JNI sets `HOME`, `TMPDIR`, and the working directory before `node::Start`. Node receives a 256 MiB V8 old-space limit. This limit does not cap total native memory. TLS uses Node's bundled trust roots. The app keeps certificate verification enabled.

## Bundling

`scripts/prepare-runtime.mjs` prepares the native libraries and calls `runtime/build.mjs`. esbuild creates one ESM bundle. `builtinModels()` registers all Pi providers. Pi's static OAuth and Bedrock loaders include implementations that use opaque dynamic imports. All provider SDK code stays in the generated bundle.

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

`ScreenState` owns display geometry, element identity, snapshots, and diffs. Coordinates use the rotated main display's logical pixels, including system bars. Display size comes from `Display.getRealSize`, not application resource metrics. This use concerns gesture coordinates, not UI layout. The snapshot separately reports full display, active window, and root content bounds. Rotation or a window change resets the element registry. Screenshot metadata maps the encoded image back to screen pixels. OCR boxes use the same mapping.

`screen_read` returns a complete snapshot. Each element has a monotonic ID, resource ID, label, bounds, and interaction state. The registry matches refreshed nodes and semantic features. Editable text is excluded from identity. Unrelated element changes preserve the target's ID. Actions refresh and validate the target before dispatch. Ambiguous or missing targets require a new read. Password nodes hide their content.

Actions return a diff against the last presented snapshot. Diffs name `baseSnapshot` and contain added, removed, and updated elements. A window change or major replacement returns a full snapshot. Explicit reads return full snapshots. The model must request a full read if its baseline is absent.

`screenshot` uses Android's accessibility screenshot API. Bundled ML Kit recognizers supply Chinese and Latin OCR. The engine initializes ML Kit explicitly. OCR removes text already present in the tree. Bitmap ownership lasts until each ML Kit task completes. OCR uses the source frame. Model images use JPEG quality 80 with a longest edge of at most 1280 pixels. Rotation changes during capture require a new read. Images stay in private files and reach Pi as image blocks for vision models. The default prompt prefers the tree, then OCR, then images when visual evidence is needed.

`PhoneActions` executes single actions and `do_steps` through the same action path. App launch uses a launch intent. Tap prefers a node action, with a gesture fallback. Input uses `ACTION_SET_TEXT` or the Android 13+ accessibility input connection and checks read-back. Back and Home use global actions. Stability requires repeated matching trees and a quiet event interval. Waiting ends after 2500 ms. An unstable result stops a batch.

`do_steps` accepts text/resource-ID targets, directional swipes, and optional expected text/package conditions. It rejects future element IDs and coordinate guesses. Each step checks cancellation and current permission state. Failure, a detected standard dialog, an unexpected app switch, or an unmet condition stops remaining steps. Custom in-app overlays without dialog semantics can require model review. Each substep produces its own timeline entry.

## Task records and model context

`files/task-events/<taskId>.jsonl` is the task journal. Kotlin appends and syncs each event. Runtime stream events are saved on the pipe reader thread before UI delivery. Records include task/session identity, model request start, HTTP response status, first stream event, response duration, token/cache usage, payload size, image budget, tool arguments, complete native results, and substep outcomes. Authentication traffic stays outside this journal. Known credential values and sensitive fields are redacted before persistence. The journal contains page data and remains app-private.

On engine restart, unfinished journals receive an `interrupted` event. Recovery ignores a torn final line and truncates it before appending. The UI rebuilds task turns from these records. Conversation deletion also removes associated journals.

`files/transcripts/<session>.json` preserves Pi messages independently. Each completed message and task exit writes an atomic snapshot, including error and cancellation paths. Loading repairs interrupted tool-call pairs with explicit failure results. This does not execute the missing actions.

`context.mjs` projects history through Pi's `transformContext`. Normal requests preserve their existing prefix and use a stable session ID. Estimated input above 60% of the context window triggers summaries of old tool outcomes and target arguments. Compression keeps four, two, or one recent complete tool rounds as needed. The materialized full baseline for retained diffs survives. The original Pi history is preserved. Input estimates are calibrated from provider usage. Output space is reserved. An input that still exceeds the estimated capacity fails with a clear error.

Images follow model catalog limits and local budgets of eight images and 4 MiB of encoded image data per request. The projection checks per-message counts and decoded image bytes. Old tool images become placeholders when needed. User input is retained. User images that exceed these limits produce an error. Pi's payload hook checks the serialized request against the catalog's byte limit before transport. Unknown provider limits remain unknown. The provider still owns its final acceptance limits.

## Verification

Self-check starts an HTTP fixture on a random loopback port. Pi's real OpenAI transport makes the first request. The fixture returns a streamed tool call. Kotlin returns runtime information. Pi sends a second request with that tool result. The fixture returns the final answer. The check fails if either request or the tool result is missing.

The fixture provides a deterministic model response. It tests runtime compatibility and the request/tool bridge. It does not establish cloud provider compatibility.

Connection verification uses the selected Pi model and saved credentials. It sends a short text request. Real tasks use the same provider collection and a fresh Pi Agent with saved session context.

## Primary sources

Checked on 2026-10-31. Read the linked source again when changing the related dependency or API.

- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes): Gradle 9.4.1, built-in Kotlin, NDK, and supported API level.
- [Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler): Compose compiler plugin and Kotlin version alignment.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `specialUse`, its permission, and its manifest subtype. Store submission requires a separate use-case review.
- [Android 17 target behavior](https://developer.android.com/about/versions/17/behavior-changes-17): current target API behavior changes.
- [Bound services](https://developer.android.com/develop/background-work/services/bound-services): Messenger IPC and service connection callbacks.
- [Service manifest](https://developer.android.com/guide/topics/manifest/service-element): private process names and exported service control.
- [Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes): ELF and APK alignment requirements.
- [Pi Agent Core 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/agent): agent lifecycle and tool execution.
- [Pi AI 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/ai): model providers, authentication, and request APIs.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): encryption-key generation and storage.
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService): window access, screenshots, gestures, and global actions.
- [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo): node text, bounds, actions, and refresh.
- [Display geometry](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point)): rotated logical display bounds and the distinction from UI window metrics.
- [Accessibility input method](https://developer.android.com/reference/android/accessibilityservice/InputMethod): Android 13+ input connection.
- [Package visibility](https://developer.android.com/training/package-visibility/declaring): launcher intent queries.
- [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android): bundled Chinese and Latin recognizers and bitmap lifetime.
- [ML Kit initialization](https://developers.google.com/android/reference/com/google/mlkit/common/MlKit): explicit SDK initialization.
- [Compose drawer](https://developer.android.com/develop/ui/compose/components/drawer) and [bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets): native navigation surfaces.
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets-ui): consume Scaffold padding before applying keyboard insets.
- [Node Android release](https://github.com/gmaclennan/nodejs-mobile/releases/tag/v24.18.0-0): binary provenance and release status.
- [Node mobile embedding](https://github.com/gmaclennan/nodejs-mobile/blob/main/docs/EMBEDDING.md): Android environment setup and one-instance restriction.
- [Node embedding API](https://nodejs.org/api/embedding.html): native entry points and version-specific API compatibility.
