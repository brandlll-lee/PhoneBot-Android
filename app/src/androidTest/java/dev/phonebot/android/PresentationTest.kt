package dev.phonebot.android

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
                    assertEquals("$title · 测试任务", notification.extras.getCharSequence("android.title").toString())
                    assertEquals("已完成", notification.extras.getCharSequence("android.text").toString())
                    assertNotNull(notification.contentIntent)
                }
            } finally { manager.cancel(id, 2); file.delete() }
        }
    }
}
