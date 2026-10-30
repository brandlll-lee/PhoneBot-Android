# Runtime

## Process ownership

`MainActivity` uses `EngineClient` to send commands through Android `Messenger`. `EngineService` is private to the app and runs in `:engine`. The service starts from a foreground user action. It owns the foreground notification and uses `START_NOT_STICKY`.

The service starts one Node instance on a worker thread. JNI passes two pipe file descriptors to the bundled JavaScript entry point. Node reads JSON lines from one pipe. Kotlin reads events from the other pipe. Each channel is private to the app process pair. The bridge does not open a command server.

`runtime_info` returns the engine PID, Android API level, and manufacturer. This is the only Kotlin tool in this implementation.

## Lifecycle

The phases are `STOPPED`, `STARTING`, `READY`, `RUNNING`, `STOPPING`, and `FAILED`. A model failure returns the engine to `READY` with an error message. A native startup failure or an unexpected process exit reports `FAILED`.

Startup has a 20-second deadline. A verification has a 30-second deadline and at most three assistant turns. Each verification uses a fresh Pi `Agent` and accepts one task at a time.

Stop aborts the active Pi request and pending tool work. Kotlin allows three seconds for shutdown. It then ends the engine process. Node supports one instance per process, so each restart uses a new process. The client waits for process disconnection before it enables Start.

JNI sets `HOME`, `TMPDIR`, and the working directory before `node::Start`. Node receives a 256 MiB V8 old-space limit. This limit does not cap total native memory. TLS uses Node's bundled trust roots. The app keeps certificate verification enabled.

## Bundling

`scripts/prepare-runtime.mjs` prepares the native libraries and calls `runtime/build.mjs`. esbuild creates one ESM bundle. Pi supplies the agent loop, schema validation, OpenAI Chat Completions transport, streaming parser, and cancellation. Kotlin supplies service control and the host tool.

The build pins Pi packages through `runtime/package-lock.json`. Node comes from the `gmaclennan/nodejs-mobile` maintainer's `v24.18.0-0` release. This release is marked **prerelease**. It is an Android build of Node, not a desktop Node binary. The archive contains matching headers and native libraries. Its SHA-256 is checked before extraction.

The selected Node build supplies `fetch`, TLS, and small ICU data. Full locale data is outside this runtime package. Pi's package metadata requires Node 22.19 or later. The older Node 18 mobile build does not meet that requirement.

The APK contains npm license notices and Node's full license notice. Native artifacts are generated locally and stay outside Git.

## Verification modes

Self-check starts an HTTP fixture on a random loopback port. Pi's real OpenAI transport makes the first request. The fixture returns a streamed tool call. Kotlin returns runtime information. Pi sends a second request with that tool result. The fixture returns the final answer. The check fails if either request or the tool result is missing.

The fixture provides a deterministic model response. It tests runtime compatibility and the request/tool bridge. It does not establish cloud provider compatibility.

Model verification accepts an HTTPS endpoint and transient credentials. It runs the same Pi agent and Kotlin tool against that endpoint. It does not implement a saved model catalog or provider login flow.

## Primary sources

Checked on 2026-10-30. Read the linked source again when changing the related dependency or API.

- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes): Gradle 9.4.1, built-in Kotlin, NDK, and supported API level.
- [Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler): Compose compiler plugin and Kotlin version alignment.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `specialUse`, its permission, and its manifest subtype. Store submission requires a separate use-case review.
- [Android 17 target behavior](https://developer.android.com/about/versions/17/behavior-changes-17): current target API behavior changes.
- [Bound services](https://developer.android.com/develop/background-work/services/bound-services): Messenger IPC and service connection callbacks.
- [Service manifest](https://developer.android.com/guide/topics/manifest/service-element): private process names and exported service control.
- [Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes): ELF and APK alignment requirements.
- [Pi Agent Core 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/agent): agent lifecycle and tool execution.
- [Pi AI 1.4.0](https://github.com/earendil-works/pi/tree/v1.4.0/packages/ai): model providers, authentication, and request APIs.
- [Node Android release](https://github.com/gmaclennan/nodejs-mobile/releases/tag/v24.18.0-0): binary provenance and release status.
- [Node mobile embedding](https://github.com/gmaclennan/nodejs-mobile/blob/main/docs/EMBEDDING.md): Android environment setup and one-instance restriction.
- [Node embedding API](https://nodejs.org/api/embedding.html): native entry points and version-specific API compatibility.
