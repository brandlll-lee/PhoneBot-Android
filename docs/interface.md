# Interface

Use this contract when changing the Compose screens.

## Chat

The chat is the first screen. Use a near-black background, white body text, gray secondary text, and a restrained blue accent. The top bar contains the drawer, session title, new conversation, and settings controls.

Align user messages to the right. Put assistant text directly on the page. Show tool execution in an expandable timeline. Show tool details only after a tap. Keep the composer above the keyboard and system navigation area. Replace Send with Stop during a task. Give each icon an accessible name and a 48 dp touch target.

Use the empty state to show the product name and model selection. Use short state messages for loading, failure, permission pauses, and interruption. Show actual task events. Keep model reasoning private.

## Drawer

Use a search field, date groups, conversation rows, and a settings footer. Highlight the current conversation with a muted blue surface. Search both prompts and answers. Disable conversation changes during a task.

## Settings

Use one tall sheet with a centered title, back navigation, and Close. Group related rows. Put the icon on the left and the current value on the right. Use inset dividers. Put provider authentication and model selection inside this sheet. Keep credentials out of the conversation.

Only add a secondary label when it adds useful context. Permission purposes and Android connection status provide required information. Establish other hierarchy with font size, weight, spacing, and alignment.

## Acceptance

Check the chat, drawer, settings, provider list, authentication prompt, and permission pause. Check a compact screen and larger system text. Text must wrap within its row. The keyboard must leave Send and Stop visible. Controls must use real state and real actions.
