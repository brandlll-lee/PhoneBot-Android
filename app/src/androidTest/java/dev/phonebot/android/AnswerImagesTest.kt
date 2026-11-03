package dev.phonebot.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mikepenz.markdown.model.parseMarkdown
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AnswerImagesTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bot = "answer-images-fixture"
    private val hash = "c".repeat(64)
    private val reference = "phonebot://images/$bot/$hash.source"

    @Test fun imagesKeepTheirOrderThroughStreamingCompletionAndHistory() {
        val directory = File(context.filesDir, "images/$bot").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(70, 90, 180))
            android.graphics.Canvas(this).drawCircle(800f, 400f, 200f,
                android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        }
        File(directory, "$hash.source").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val text = mutableStateOf("## 开始\n\n![第一张](")
        val streaming = mutableStateOf(true)
        val restored = mutableStateOf(false)
        val complete = "## 开始\n\n![第一张]($reference)\n\n两图之间\n\n![第二张]($reference)\n\n[资料]($reference)\n\n结束"
        val history = parseMarkdown(complete)
        try {
            compose.setContent { PhoneBotTheme {
                Surface {
                    key(restored.value) {
                        MarkdownAnswer.Content(text.value, streaming.value, state = if (restored.value) history else null, botId = bot)
                    }
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("开始").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { text.value = complete }
            awaitImages(2)
            checkOrder()
            compose.onNodeWithContentDescription("第一张").performClick()
            compose.onNodeWithTag("answer_image_viewer").assertIsDisplayed()
            compose.onNodeWithText("关闭图片").performClick()
            compose.onNodeWithTag("answer_image_viewer").assertDoesNotExist()
            compose.runOnIdle { streaming.value = false }
            awaitImages(2)
            checkOrder()
            File(context.cacheDir, "answer-inline.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.runOnIdle { restored.value = true }
            awaitImages(2)
            checkOrder()
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidAndMissingImagesKeepTheSurroundingText() {
        val text = "前文\n\n![失效图片](phonebot://images/$bot/${"b".repeat(64)}.source)\n\n" +
            "![受限图片](file:///data/data/dev.phonebot.android/files/provider-auth.json)\n\n后文"
        compose.setContent { PhoneBotTheme { Surface { MarkdownAnswer.Content(text, botId = bot) } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("answer_image_error").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("前文").assertIsDisplayed()
        compose.onNodeWithText("后文").assertIsDisplayed()
        compose.onAllNodesWithTag("answer_image").assertCountEquals(0)
    }

    @Test fun switchingBotUsesItsResourceBoundary() {
        val directory = File(context.filesDir, "images/$bot").apply { mkdirs() }
        val name = "${"d".repeat(64)}.source"
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val current = mutableStateOf(bot)
        try {
            compose.setContent { PhoneBotTheme { Surface {
                Column(Modifier.fillMaxWidth()) { MarkdownAnswer.Content("![私有图片](phonebot://images/$bot/$name)", botId = current.value) }
            } } }
            awaitImages(1)
            compose.runOnIdle { current.value = "other-answer-fixture" }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("answer_image_error").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("answer_image").assertCountEquals(0)
            compose.runOnIdle { current.value = bot }
            awaitImages(1)
        } finally { directory.deleteRecursively() }
    }

    private fun awaitImages(count: Int) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("answer_image").fetchSemanticsNodes().size == count }
    }

    private fun checkOrder() {
        val first = compose.onNodeWithContentDescription("第一张").fetchSemanticsNode().boundsInRoot
        val middle = compose.onNodeWithText("两图之间").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("第二张").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < middle.top && middle.top < second.top)
        assertEquals(2f, first.width / first.height, 0.05f)
        assertTrue("Image does not fill the answer", first.width >= compose.onRoot().fetchSemanticsNode().boundsInRoot.width * .9f)
        val pixels = compose.onNodeWithContentDescription("第一张").captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color.White, pixels[pixels.width / 2, pixels.height / 2])
        compose.onNodeWithText("资料").assertExists()
        compose.onNodeWithText("结束").assertExists()
    }
}
