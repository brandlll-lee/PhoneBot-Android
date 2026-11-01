package dev.phonebot.android

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.engine.ModelImages
import dev.phonebot.android.engine.TaskJournal
import dev.phonebot.android.engine.TaskNotifications
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun replyStreamsAndMovesToolTextIntoTheTrace() {
        val turn = mutableStateOf(ChatTurn("stream", "打开相册"))
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatMessages(listOf(turn.value), if (turn.value.status == "running") turn.value.id else "", Modifier.fillMaxSize(), focusedTask = turn.value.id)
        } } }
        fun event(value: String) { compose.runOnIdle { turn.value = turn.value.event(JSONObject(value)) } }
        event("""{"type":"text_delta","request":1,"contentIndex":0,"delta":"先读取页面。"}""")
        compose.onNodeWithTag("assistant_message").assertIsDisplayed()
        event("""{"type":"toolcall_start","request":1,"contentIndex":1}""")
        compose.onNodeWithTag("assistant_message").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("先读取页面。")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("先读取页面。").assertIsDisplayed()
        event("""{"type":"model_request","request":2}""")
        event("""{"type":"text_delta","request":2,"contentIndex":0,"delta":"**相册"}""")
        compose.onNodeWithTag("assistant_message").assertIsDisplayed()
        event("""{"type":"text_delta","request":2,"contentIndex":0,"delta":"已打开**"}""")
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("相册已打开")).fetchSemanticsNodes().size == 1 }
        event("""{"type":"model_response","request":2,"stopReason":"stop","content":[{"type":"text","text":"**相册已打开**"}]}""")
        compose.onNodeWithTag("assistant_message").assertIsDisplayed()
        event("""{"type":"result","request":2,"text":"**相册已打开**"}""")
        compose.onNodeWithTag("assistant_message").assertIsDisplayed()
        assertEquals("", turn.value.streamText)
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("相册已打开")).fetchSemanticsNodes().size == 1 }
        assertEquals("", ChatTurn("cancel", "任务").event(JSONObject("""{"type":"text_delta","request":1,"contentIndex":0,"delta":"未完"}"""))
            .event(JSONObject("""{"type":"cancelled"}""")).streamText)
    }

    @Test fun piMessagesStayOrderedInTheTraceAndFinalReplyStaysOutside() {
        val events = listOf(
            """{"type":"thinking_delta","request":1,"contentIndex":0,"delta":"先确认当前页面。"}""",
            """{"type":"text_delta","request":1,"contentIndex":1,"delta":"我先读取页面。"}""",
            """{"type":"model_response","request":1,"content":[{"type":"thinking","thinking":"先确认当前页面。"},{"type":"text","text":"我先读取页面。"},{"type":"toolCall","id":"read","name":"screen_read"}]}""",
            """{"type":"step","id":"read","name":"screen_read","label":"读取页面","args":{"ocr":true}}""",
            """{"type":"step_result","id":"read","error":false,"text":"已找到相册页面"}""",
            """{"type":"thinking_delta","request":2,"contentIndex":0,"delta":"页面显示相册。"}""",
            """{"type":"text_delta","request":2,"contentIndex":1,"delta":"**相册已打开**"}""",
            """{"type":"model_response","request":2,"content":[{"type":"thinking","thinking":"页面显示相册。"},{"type":"text","text":"**相册已打开**"}]}""",
            """{"type":"result","request":2,"text":"**相册已打开**"}""",
        ).map { JSONObject(it).put("taskId", "presentation-trace") }
        val current = events.dropLast(1).fold(ChatTurn("presentation-trace", "打开相册")) { turn, event -> turn.event(event) }
        assertEquals("", current.answer)
        val finished = current.event(events.last())
        assertEquals(listOf("thinking", "text", "tool", "thinking"), finished.steps.map { it.kind })
        assertEquals("**相册已打开**", finished.answer)
        assertEquals(1, finished.steps.count { it.kind == "text" })
        assertEquals("读取页面", finished.steps.single { it.kind == "tool" }.name)

        val journal = TaskJournal(File(context.cacheDir, "trace-events"))
        val file = File(context.cacheDir, "trace-events/presentation-trace.jsonl")
        try {
            file.delete()
            events.forEach(journal::append)
            assertEquals(finished, journal.records(file).fold(ChatTurn("presentation-trace", "打开相册")) { turn, event -> turn.event(event) })
        } finally { file.delete() }
        val failed = current.event(JSONObject("""{"type":"error","message":"模型请求中断"}"""))
        assertEquals("", failed.answer)
        assertTrue(failed.steps.last().error)
        assertEquals("模型请求中断", failed.steps.last().detail)

        val turn = mutableStateOf(current)
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatMessages(listOf(turn.value), if (turn.value.status == "running") turn.value.id else "", Modifier.fillMaxSize(), focusedTask = turn.value.id)
        } } }
        compose.onNodeWithTag("execution_trace").assertIsDisplayed()
        compose.onNodeWithText("先确认当前页面。").assertIsDisplayed()
        compose.runOnIdle { turn.value = finished }
        compose.onNodeWithTag("execution_trace").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("相册已打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("相册已打开").assertIsDisplayed()
        File(context.cacheDir, "trace-collapsed.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("execution_toggle").performClick()
        compose.onNodeWithTag("tool-read").performClick()
        compose.onNodeWithText("输入").assertIsDisplayed()
        compose.onNodeWithText("输出").assertIsDisplayed()
        File(context.cacheDir, "trace-expanded.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("execution_toggle").performClick()
        compose.onNodeWithText("先确认当前页面。").assertDoesNotExist()
        compose.onNodeWithText("相册已打开").assertIsDisplayed()
    }

    @Test fun markdownStreamsChineseAndKeepsCodeAndTables() {
        val source = mutableStateOf("**中文")
        val streaming = mutableStateOf(true)
        compose.setContent { MaterialTheme { MarkdownAnswer.Content(source.value, streaming.value) } }
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("中文", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { source.value = "**中文加粗**\n\n[链接](https://example.com)\n\n```kotlin\nval answer = 42 // ${"long line ".repeat(40)}\n```\n\n| 名称 | 值 |\n| --- | --- |\n| 结果 | ${"宽表格内容".repeat(20)} |" }
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("中文加粗", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { streaming.value = false }
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText("val answer = 42", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("中文加粗").assertIsDisplayed()
        assertEquals("中文加粗 链接", MarkdownAnswer.plain("**中文加粗**\n[链接](https://example.com)"))
    }

    @Test fun imagesNormalizeHistoricalPng() {
        val directory = File(context.cacheDir, "presentation-images").apply { mkdirs() }
        val reference = "a".repeat(64)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        try {
            File(directory, "$reference.source").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val encoded = ModelImages.prepare(directory, reference)
            assertEquals("image/jpeg", encoded.getString("mimeType"))
            val decoded = BitmapFactory.decodeFile(encoded.getString("image"))
            assertEquals(1280, decoded.height)
            assertEquals(576, decoded.width)
            decoded.recycle()
        } finally { bitmap.recycle(); directory.deleteRecursively() }
    }

    @Test fun notificationsRespectPermissionAndDeduplicateTerminalStates() {
        val journal = TaskJournal(File(context.filesDir, "task-events"))
        val manager = context.getSystemService(NotificationManager::class.java)
        TaskNotifications.channel(context)
        val allowed = manager.areNotificationsEnabled() && manager.getNotificationChannel(TaskNotifications.CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        for ((type, title) in listOf("result" to "执行结束", "error" to "执行失败", "cancelled" to "已停止")) {
            val id = "presentation-notification-$type"
            val file = File(context.filesDir, "task-events/$id.jsonl")
            val start = JSONObject().put("type", "task_open").put("taskId", id).put("session", "presentation-session").put("prompt", "测试任务")
                .put("botName", "音乐搭档").put("avatarColor", 0xFF8B5CF6)
            val end = JSONObject().put("type", type).put("taskId", id).put("text", "**已完成**")
            try {
                file.delete()
                journal.append(start); journal.append(end)
                assertEquals(allowed, TaskNotifications.post(context, journal, start, end))
                assertFalse(TaskNotifications.post(context, journal, start, end))
                assertEquals(1, journal.records(file).count { it.optString("type") == "notification" })
                if (allowed) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 3000
                    while (manager.activeNotifications.none { it.tag == id } && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
                    val notification = manager.activeNotifications.single { it.tag == id }.notification
                    assertEquals("音乐搭档 · $title", notification.extras.getCharSequence("android.title").toString())
                    assertNotNull(notification.getLargeIcon())
                    assertEquals("已完成", notification.extras.getCharSequence("android.text").toString())
                    assertNotNull(notification.contentIntent)
                }
            } finally { manager.cancel(id, 2); file.delete() }
        }
    }
}
