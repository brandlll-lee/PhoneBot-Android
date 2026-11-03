# Interface

Use this contract when changing the Compose screens.

## Chat

The chat is the first screen. Use a black or white background. `PhoneBotTheme` owns Material 3 color roles, typography and shapes. `PhoneUi` owns shared sizes and glow parameters. Components read these tokens. Settings offers System, Light and Dark. Save the choice and send it to the engine process through host IPC. System bars, Markdown, menus and overlays follow the same appearance.

The header uses two outlined circular controls and a central Bot avatar/name pill. The Bot sits in front of the partly covered Provider mark. Both follow the theme. The left control opens Bot contacts. The right menu opens Create Bot and Settings. The central pill opens this Bot's settings.

Messages fill the chat page. The header and composer overlay them. Measured control heights set the list's top and bottom content padding. Opening or switching a Bot starts at the latest message's end. A native reverse lazy list keeps the bottom position during streaming. Reading history does not follow new output. Each Bot retains its draft. Haze 2.0.1 captures only the message layer. Its native progressive blur softens the top and bottom edges. Controls stay sharp and use shared translucent surfaces, borders and shadows. See [Haze usage](https://chrisbanes.github.io/haze/2.0.1/blur/usage/) and [performance](https://chrisbanes.github.io/haze/2.0.1/performance/).

Short chats align to the top. Expanding a trace keeps the title and earlier visible messages in place. Apply the anchor after the updated list content is composed, before measurement. Completed intermediate Markdown keeps its SDK parsing state while the trace is collapsed.

Measure floating controls and list padding in one `SubcomposeLayout` pass. Keep completed Markdown SDK results in the existing view model. Prepare the latest reply off the UI thread before its first display. Parse other replies and intermediate text on demand. Retain one content version per message and release the Bot's entries on deletion.

Accepted submissions carry their task ID to the chat. Use one native scroll animation to reveal that request. Streaming follows after that animation. User scrolling or trace expansion stops following. Bot entry uses the reverse list's initial position.

## Logo

The logo uses a rounded phone frame, a speaker, two rounded eyes and a gesture bar. `docs/assets/phonebot-black.svg` owns the geometry. Run `node scripts/prepare-brand.mjs` to generate the white SVG, animated SVG, native drawing geometry, notification mark and adaptive launcher layers. `scripts/prepare-runtime.mjs` also runs this step. Both READMEs use the static SVGs through a light/dark picture element.

`PhoneBotLogo` uses native Compose animation. It blinks at rest. During work, its eyes look slightly sideways and the body breathes. Pressing the existing header or floating control triggers a wink and nod. Keep the parent's click action and accessibility semantics. Stop animation below the STARTED lifecycle state. A zero system motion scale restores the static face. Read animated values in drawing, not layout. The launcher and notification icons remain static. The standalone animated SVG follows browser color and reduced-motion preferences.

Use the official [Compose animation APIs](https://developer.android.com/develop/ui/compose/animation/quick-guide), [motion scale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale) and [adaptive-icon safe zone](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). Keep launcher geometry inside the central 66 × 66 dp area of its 108 dp layer.

## Chat controls

Align user messages to the right. Their background and active Send/Stop controls use a saturated Bot action color with white foreground at 4.5:1 contrast or greater. The action color stays the same in both themes. Render Pi's reply in a subtle full-width bubble as text arrives. If Pi starts a tool call, move that message into the execution trace. Pi's terminal event commits the final reply. Keep intermediate text, provider-returned thinking, tool calls, tool results, screenshots, and errors inside one expandable execution trace. Preserve Pi's message and tool order. Use a thin vertical line, small gray status dots, green completed-tool dots and red error dots. Tool details open an inset input/output panel. Expand the trace during execution and collapse it when execution ends. Keep the composer above the keyboard and system navigation area. Replace Send with Stop during a task. Give each icon an accessible name and a 48 dp touch target.

Replies use standard Markdown images and file links. The official renderer's Coil extension supplies image handling. Coil samples images to measured constraints and caches them. Loading and failure states keep surrounding text visible. Tap an image to open a full-screen view. Streaming, completed replies and restored history use the same component. HTTPS images load over the network. Local image references belong to the current Bot. File links open through Android with temporary read permission.

Put each trace step in its own native lazy-list item. Use stable keys and content types. Expanded records must compose only nearby rows. Keep the trace title's position when folding. Keep completed Markdown parsing state above lazy items. Pin an already visible reply while its trace is expanded. Release the native pin on collapse or when leaving the chat. Trace controls use native interaction state for text-color feedback on press, hover and focus. Keep their backgrounds unchanged. Follow the official [lazy-list guidance](https://developer.android.com/develop/ui/compose/lists), [PinnableContainer API](https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/PinnableContainer) and [interaction APIs](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions).

Render intermediate text and final replies with the same native Markdown component during streaming, after completion, and after history restore. Display a completed run's final text once, outside the trace. Keep that message's thinking blocks inside the trace. Keep the source Markdown in storage. Parse streaming tails off the UI thread. Keep rendered text visible during final parsing. Support headings, emphasis, lists, quotes, links, tables, and selectable text. Code blocks use syntax highlighting, Copy, and horizontal scrolling. Use the active palette and system text scaling.

Use the empty state to show the Bot's name. Model, helper and screen selection belong in Bot settings. Keep the input area free of configuration controls.

Use a compact rounded input for short text. For wrapped or multiline text, expand the same panel upward. Put text above the Send control. Keep Send at the bottom right. Show at most five lines and scroll the text area vertically beyond that limit. Keep only Send, or Stop during execution, inside the panel. Put one muted hint below it. When submission succeeds, clear the text, remove focus, hide the keyboard, and restore the compact height. A rejected submission keeps the draft and focus.

Use short state messages for loading, execution ended, failure, permission pauses, and interruption. The final reply explains task outcomes and unresolved goals. Show only thinking blocks returned by the provider. A model that returns none has no thinking entry.

## Drawer

Use a search field, Bot contacts and a software-settings footer. Each contact has an avatar spanning two rows. Name and last-message time share the first row. A one-line message preview fills the second row. Sort by message time. Long press opens Bot actions. Keep empty Bots. Switching contacts does not stop or retarget a task.

## Settings

Use one fixed-height sheet with a centered title, back navigation, and Close. Disable sheet dragging. Scroll only the body below the header. Group related rows. Put the icon on the left and the current value on the right. Use inset dividers. Software settings contain shared provider authentication, device authorization, appearance and engine controls. Keep credentials out of the conversation. Provider rows use bundled brand marks and a shared fallback. See [asset sources](assets/providers/README.md).

An arrow indicates navigation. It does not control whether a row can be pressed. Action rows follow their enabled state. Status rows have no click action.

Only add a secondary label when it adds useful context. Permission purposes and Android connection status provide required information. Establish other hierarchy with font size, weight, spacing, and alignment.

Bot settings contain the user-defined name, avatar color, provider/model, helper and screen. Apply the selected color to the phone Logo and keep its background transparent, including notification avatars. The default black/white Logo follows the theme. Offer presets, HSV sliders and HEX entry. Each Bot keeps its own model choice; credentials stay shared. New Bots inherit saved screen/helper defaults. Existing records retain their IDs, history and choices. Old records use the main screen and Shizuku. Lock only the running Bot's configuration. Basic main-screen tools do not require a helper.

The device helper page shows the actual connection state. It offers built-in ADB pairing, reconnect, forget and opt-in reboot recovery. The pairing form accepts the port from Android's pairing-code dialog and a six-digit code. Pairing and connection ports are different. PhoneBot discovers the connection port. Keep the pairing code in transient UI memory. Clear it on submission and after successful pairing. The built-in helper requires Android 12 or later.

The same page opens Shizuku or requests its authorization. Both helpers share device access across conversations. Reboot recovery depends on granted system access, Wireless debugging, Wi-Fi and background restrictions. Display the actual outcome. Do not show a saved pairing as a running connection.

The permission page includes modify-system-settings access, Shizuku authorization and Android 17 local network access. Recheck on resume and after the Shizuku permission response. Missing optional access must not block unrelated tools.

Permission pauses identify accessibility, unlock, built-in helper, Shizuku or the target screen. Show the engine's reason and a relevant settings entry. Let users repair a connection during a pause. Keep the task's selected helper and screen fixed. Continue only after the user presses Continue. Screen-off availability follows device validation; the UI must not imply universal support.

## Floating task preview

An active user task shows a transparent Logo with a native loading ring in the Bot's color. In virtual mode, tap it to open a preview at half the full width and height. A 48 dp resize control toggles the full size. The image fills a continuous phone outline with native inner and outer shadows in the Bot's color. Keep shadow geometry and painter opacity fixed. Animate native layer opacity so shadow painters stay cached. A zero system motion scale keeps it static. Keep the rim separate from the image's clipping. Preserve the display aspect ratio and show the complete frame. Drag the circle or image to move it. Keep the window and glow clear of system bars. Tap the image to restore the circle while the task continues. Terminal events remove the window. Connection checks and journal replay do not open it.

The preview is read-only. Main-screen tasks use the circle to open the current conversation through the result notification's exact-task destination. Use the existing chat Stop control. The window does not take keyboard focus. On Android 14 and later, disable native window-move animation so drag positions and touch bounds stay aligned.

The existing accessibility service hosts the window. No separate overlay permission is required. Losing the service removes the window. Main-screen capture and gestures temporarily hide the window so it cannot cover targets or enter screenshots. It remains available during page-loading waits.

## Task notifications

Post one result notification for each user task in both foreground and background. Show execution ended, failed, or stopped, with the task title and a plain-text result preview. Keep the high-importance result channel separate from the quiet engine channel. Notification taps close the drawer or settings, open the recorded session, and scroll to its task. Handle both a new activity and an existing activity. Journal replay and connection checks do not post result notifications.

The permission page shows notification permission and the result channel's banner state. Open the channel's system settings from its row. Permission denial leaves task execution available. Android and the user's channel and Do Not Disturb settings control actual banner delivery.

## Acceptance

Check the chat, drawer, settings, provider list, authentication prompt, and permission pause. Check a compact screen and larger system text. Text must wrap within its row. The keyboard must leave Send and Stop visible. Controls must use real state and real actions.
