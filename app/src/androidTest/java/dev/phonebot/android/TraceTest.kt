package dev.phonebot.android

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TraceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expansionComposesVisibleSteps() {
        val turn = ChatTurn("trace-load", "检查执行过程", status = "done", answer = "执行结束。",
            steps = (1..200).map { TaskStep("load-$it", "工具 $it", detail = "结果 $it", done = true) })
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatMessages(listOf(turn), "", Modifier.fillMaxSize(), focusedTask = turn.id)
        } } }
        compose.onNodeWithTag("execution_toggle").performClick()
        compose.waitForIdle()
        val visible = compose.onAllNodes(SemanticsMatcher("tool row") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("tool-load-")
        }).fetchSemanticsNodes().size
        Log.i("PhoneBotUiTest", "traceSteps=200 composedRows=$visible")
        assertTrue("Expanded trace composed $visible tool rows", visible in 1..30)
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("tool-load-200"))
        compose.onNodeWithTag("tool-load-200").assertIsDisplayed().performClick()
        compose.onNodeWithText("结果 200").assertExists()
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("execution_toggle"))
        compose.onNodeWithTag("execution_toggle").performClick()
        compose.onNodeWithTag("tool-load-1").assertDoesNotExist()
    }

    @Test fun tracePressChangesTextAndKeepsTheBackground() {
        val turn = ChatTurn("press", "查看流程", status = "done", steps = listOf(TaskStep("read", "读取页面", done = true)))
        compose.setContent { PhoneBotTheme(Appearance.DARK) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatMessages(listOf(turn), "", Modifier.fillMaxSize(), focusedTask = turn.id)
        } } }
        val toggle = compose.onNodeWithTag("execution_toggle")
        val before = toggle.captureToImage().toPixelMap()
        toggle.performTouchInput { down(center) }
        try {
            compose.waitUntil(3000) {
                val pixels = toggle.captureToImage().toPixelMap()
                (0 until pixels.width).any { x -> (0 until pixels.height).any { y ->
                    val pixel = pixels[x, y]
                    pixel.blue > pixel.red + .2f && pixel.blue > pixel.green + .2f
                } }
            }
            val after = toggle.captureToImage().toPixelMap()
            assertEquals(before[before.width / 2, 1], after[after.width / 2, 1])
        } finally { toggle.performTouchInput { up() } }
        compose.onNodeWithTag("tool-read").assertIsDisplayed()
    }
}
