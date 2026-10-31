# Interface

Use this contract when changing the Compose screens.

## Chat

The chat is the first screen. Use a near-black background, white body text, gray secondary text, and a restrained blue accent. The top bar contains the drawer, session title, new conversation, and settings controls.

Align user messages to the right. Put assistant text directly on the page. Show tool execution in an expandable timeline. Show tool details only after a tap. Keep the composer above the keyboard and system navigation area. Replace Send with Stop during a task. Give each icon an accessible name and a 48 dp touch target.

Render assistant replies with the same native Markdown component during streaming, after completion, and after history restore. Keep the source Markdown in storage. Parse streaming tails off the UI thread. Keep rendered text visible during final parsing. Support headings, emphasis, lists, quotes, links, tables, and selectable text. Code blocks use syntax highlighting, Copy, and horizontal scrolling. Use the chat's dark palette and system text scaling.

Use the empty state to show the product name. Keep model selection above the composer at the left. Use an outlined rounded button with the model name and a down arrow. Truncate long names. Reuse the Pi catalog and stored default. Provide a model-service management entry in the dropdown.

Use a compact rounded input for short text. For wrapped or multiline text, expand the same panel upward. Put text above the Send control. Keep Send at the bottom right. Show at most five lines and scroll the text area vertically beyond that limit. Keep only Send, or Stop during execution, inside the panel. Put one muted hint below it. When submission succeeds, clear the text, remove focus, hide the keyboard, and restore the compact height. A rejected submission keeps the draft and focus.

Use short state messages for loading, failure, permission pauses, and interruption. Distinguish completed, partial, blocked, failed, and stopped results. Show actual task events. Keep model reasoning private.

## Drawer

Use a search field, date groups, conversation rows, and a settings footer. Highlight the current conversation with a muted blue surface. Search both prompts and answers. Disable conversation changes during a task.

## Settings

Use one tall sheet with a centered title, back navigation, and Close. Group related rows. Put the icon on the left and the current value on the right. Use inset dividers. Put provider authentication and model selection inside this sheet. Keep credentials out of the conversation.

Only add a secondary label when it adds useful context. Permission purposes and Android connection status provide required information. Establish other hierarchy with font size, weight, spacing, and alignment.

The permission page includes modify-system-settings access and Shizuku authorization. Recheck on resume and after the Shizuku permission response. Missing optional access must not block unrelated tools.

## Task notifications

Post one result notification for each user task in both foreground and background. Show execution ended, failed, or stopped, with the task title and a plain-text result preview. Keep the high-importance result channel separate from the quiet engine channel. Notification taps close the drawer or settings, open the recorded session, and scroll to its task. Handle both a new activity and an existing activity. Journal replay and connection checks do not post result notifications.

The permission page shows notification permission and the result channel's banner state. Open the channel's system settings from its row. Permission denial leaves task execution available. Android and the user's channel and Do Not Disturb settings control actual banner delivery.

## Acceptance

Check the chat, drawer, settings, provider list, authentication prompt, and permission pause. Check a compact screen and larger system text. Text must wrap within its row. The keyboard must leave Send and Stop visible. Controls must use real state and real actions.
