# Interface

Use this contract when changing the Compose screens.

## Chat

The chat is the first screen. Use a black or white background. `PhoneBotTheme` owns Material 3 color roles, typography and shapes. `PhoneUi` owns shared sizes and glow parameters. Components read these tokens. Settings offers System, Light and Dark. Save the choice and send it to the engine process through host IPC. System bars, Markdown, menus and overlays follow the same appearance.

The header uses two outlined circular controls and a central Logo + My phonebot pill. The left control opens conversations. The right menu opens New conversation and Settings. The central pill opens conversation settings.

## Logo

The logo uses a rounded phone frame, a speaker, two rounded eyes and a gesture bar. `docs/assets/phonebot-black.svg` owns the geometry. Run `node scripts/prepare-brand.mjs` to generate the white SVG, animated SVG, native drawing geometry, notification mark and adaptive launcher layers. `scripts/prepare-runtime.mjs` also runs this step. Both READMEs use the static SVGs through a light/dark picture element.

`PhoneBotLogo` uses native Compose animation. It blinks at rest. During work, its eyes look slightly sideways and the body breathes. Pressing the existing header or floating control triggers a wink and nod. Keep the parent's click action and accessibility semantics. Stop animation below the STARTED lifecycle state. A zero system motion scale restores the static face. Read animated values in drawing, not layout. The launcher and notification icons remain static. The standalone animated SVG follows browser color and reduced-motion preferences.

Use the official [Compose animation APIs](https://developer.android.com/develop/ui/compose/animation/quick-guide), [motion scale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale) and [adaptive-icon safe zone](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). Keep launcher geometry inside the central 66 × 66 dp area of its 108 dp layer.

## Chat controls

Align user messages to the right in subtle rounded bubbles. Render Pi's reply in a subtle full-width bubble as text arrives. If Pi starts a tool call, move that message into the execution trace. Pi's terminal event commits the final reply. Keep intermediate text, provider-returned thinking, tool calls, tool results, screenshots, and errors inside one expandable execution trace. Preserve Pi's message and tool order. Use a thin vertical line, small gray status dots, green completed-tool dots and red error dots. Tool details open an inset input/output panel. Expand the trace during execution and collapse it when execution ends. Keep the composer above the keyboard and system navigation area. Replace Send with Stop during a task. Give each icon an accessible name and a 48 dp touch target.

Put each trace step in its own native lazy-list item. Use stable keys and content types. Expanded records must compose only nearby rows. Trace controls use native interaction state for text-color feedback on press, hover and focus. Keep their backgrounds unchanged. Follow the official [lazy-list guidance](https://developer.android.com/develop/ui/compose/lists) and [interaction APIs](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions).

Render intermediate text and final replies with the same native Markdown component during streaming, after completion, and after history restore. Display a completed run's final text once, outside the trace. Keep that message's thinking blocks inside the trace. Keep the source Markdown in storage. Parse streaming tails off the UI thread. Keep rendered text visible during final parsing. Support headings, emphasis, lists, quotes, links, tables, and selectable text. Code blocks use syntax highlighting, Copy, and horizontal scrolling. Use the active palette and system text scaling.

Use the empty state to show the product name. Keep model selection above the composer at the left. Use an outlined rounded button with the model name and a down arrow. Truncate long names. Reuse the Pi catalog and stored default. Provide a model-service management entry in the dropdown.

Place the screen selector beside the model selector. It shows Main screen or Virtual display. Its menu opens conversation settings. Disable virtual-display selection below Android 14. Disable selection changes during a task.

Use a compact rounded input for short text. For wrapped or multiline text, expand the same panel upward. Put text above the Send control. Keep Send at the bottom right. Show at most five lines and scroll the text area vertically beyond that limit. Keep only Send, or Stop during execution, inside the panel. Put one muted hint below it. When submission succeeds, clear the text, remove focus, hide the keyboard, and restore the compact height. A rejected submission keeps the draft and focus.

Use short state messages for loading, execution ended, failure, permission pauses, and interruption. The final reply explains task outcomes and unresolved goals. Show only thinking blocks returned by the provider. A model that returns none has no thinking entry.

## Drawer

Use a search field, date groups, conversation rows, and a settings footer. Highlight the current conversation with a muted blue surface. Search both prompts and answers. Disable conversation changes during a task.

## Settings

Use one tall sheet with a centered title, back navigation, and Close. Group related rows. Put the icon on the left and the current value on the right. Use inset dividers. Put provider authentication and model selection inside this sheet. Keep credentials out of the conversation.

An arrow indicates navigation. It does not control whether a row can be pressed. Action rows follow their enabled state. Status rows have no click action.

Only add a secondary label when it adds useful context. Permission purposes and Android connection status provide required information. Establish other hierarchy with font size, weight, spacing, and alignment.

Conversation settings choose the helper and screen for the current conversation. New conversations inherit the saved global defaults. Existing conversations retain their choices. Old records use the main screen and Shizuku. Save these fields with the existing chat data. Device authorization stays separate from conversation choices. Basic main-screen tools do not require a helper.

The device helper page shows the actual connection state. It offers built-in ADB pairing, reconnect, forget and opt-in reboot recovery. The pairing form accepts the port from Android's pairing-code dialog and a six-digit code. Pairing and connection ports are different. PhoneBot discovers the connection port. Keep the pairing code in transient UI memory. Clear it on submission and after successful pairing. The built-in helper requires Android 12 or later.

The same page opens Shizuku or requests its authorization. Both helpers share device access across conversations. Reboot recovery depends on granted system access, Wireless debugging, Wi-Fi and background restrictions. Display the actual outcome. Do not show a saved pairing as a running connection.

The permission page includes modify-system-settings access, Shizuku authorization and Android 17 local network access. Recheck on resume and after the Shizuku permission response. Missing optional access must not block unrelated tools.

Permission pauses identify accessibility, unlock, built-in helper, Shizuku or the target screen. Show the engine's reason and a relevant settings entry. Let users repair a connection during a pause. Keep the task's selected helper and screen fixed. Continue only after the user presses Continue. Screen-off availability follows device validation; the UI must not imply universal support.

## Floating task preview

An active user task shows one small logo circle with a blue active dot. In virtual mode, tap it to open the screen preview. The image fills a phone outline with a continuous blue rim and one native soft shadow. Keep the rim separate from the image's clipping. Preserve the display aspect ratio and show the complete frame. Drag the circle or image to move it. Keep the window clear of system bars. Tap the image to restore the circle while the task continues. Terminal events remove the window. Connection checks and journal replay do not open it.

The preview is read-only. Main-screen tasks use the circle to open the current conversation through the result notification's exact-task destination. Use the existing chat Stop control. The window does not take keyboard focus.

The existing accessibility service hosts the window. No separate overlay permission is required. Losing the service removes the window. Main-screen capture and gestures temporarily hide the window so it cannot cover targets or enter screenshots. It remains available during page-loading waits.

## Task notifications

Post one result notification for each user task in both foreground and background. Show execution ended, failed, or stopped, with the task title and a plain-text result preview. Keep the high-importance result channel separate from the quiet engine channel. Notification taps close the drawer or settings, open the recorded session, and scroll to its task. Handle both a new activity and an existing activity. Journal replay and connection checks do not post result notifications.

The permission page shows notification permission and the result channel's banner state. Open the channel's system settings from its row. Permission denial leaves task execution available. Android and the user's channel and Do Not Disturb settings control actual banner delivery.

## Acceptance

Check the chat, drawer, settings, provider list, authentication prompt, and permission pause. Check a compact screen and larger system text. Text must wrap within its row. The keyboard must leave Send and Stop visible. Controls must use real state and real actions.
