package dev.phonebot.android

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TraceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expansionKeepsItsAnchorThroughoutLayout() {
        val fixture = mutableIntStateOf(0)
        val positions = mutableListOf<Int>()
        var list = androidx.compose.foundation.lazy.LazyListState()
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize()) {
            key(fixture.intValue) {
                list = remember { androidx.compose.foundation.lazy.LazyListState() }
                val turn = ChatTurn("layout", "保持这条消息的位置", status = "done", answer = "任务已结束。".repeat(1 + fixture.intValue * 8),
                    steps = (1..60).map { TaskStep("layout-$it", "读取页面 $it", done = true,
                        kind = if (it == 1) "text" else "tool", detail = "**当前页面**：正在检查内容与可用操作。\n\n".repeat(12)) })
                LaunchedEffect(list) {
                    snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "layout/trace/" }?.offset }
                        .collect { if (it != null) positions.add(it) }
                }
                ChatMessages(listOf(turn), "", Modifier.fillMaxSize(), list,
                    contentPadding = PaddingValues(top = 72.dp, bottom = 112.dp, start = 16.dp, end = 16.dp))
            }
        } } }
        repeat(3) { trial ->
            compose.runOnIdle { fixture.intValue = trial }
            val toggle = compose.onNodeWithTag("execution_toggle")
            toggle.assertIsDisplayed()
            val top = toggle.getUnclippedBoundsInRoot().top
            compose.runOnIdle { positions.clear() }
            val offset = list.layoutInfo.visibleItemsInfo.first { it.key == "layout/trace/" }.offset
            toggle.performTouchInput { click(center) }
            repeat(20) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals("trial=$trial frame=$it offset=$offset changes=$positions first=${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", top.value, toggle.getUnclippedBoundsInRoot().top.value, 1f)
            }
            compose.runOnIdle { assertTrue("trial=$trial offsets=$positions", positions.all { kotlin.math.abs(it - offset) <= 1 }) }
        }
    }

    @Test fun latestReplyStartsAtItsEndAndHistoryStaysInPlace() {
        val turns = mutableStateOf((1..20).map { index -> ChatTurn("latest-$index", "任务 $index", status = "done",
            answer = (1..40).joinToString("\n\n") { "第 $index 项，第 $it 段内容。" } + "\n\n末段 $index") })
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize()) {
            ChatMessages(turns.value, "", Modifier.fillMaxSize())
        } } }
        compose.onNodeWithText("末段 20", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("任务 10"))
        compose.runOnIdle { turns.value = turns.value + ChatTurn("latest-21", "任务 21", status = "done", answer = "新结果") }
        compose.onNodeWithText("任务 10").assertIsDisplayed()
    }

    @Test fun foldingKeepsTheTitleInPlace() {
        val turn = ChatTurn("timing", "检查执行过程", status = "done",
            answer = (1..30).joinToString("\n\n") { "**结果 $it**：保留当前页面内容与中文输出。" },
            steps = (1..200).map { TaskStep("timing-$it", "读取页面 $it", detail = "页面内容 $it", done = true) })
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize()) {
            ChatMessages(listOf(turn), "", Modifier.fillMaxSize(), focusedTask = turn.id,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 80.dp))
        } } }
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("execution_toggle"))
        repeat(3) {
            val top = compose.onNodeWithTag("execution_toggle").getUnclippedBoundsInRoot().top
            compose.onNodeWithTag("execution_toggle").performClick()
            compose.onNodeWithTag("execution_trace").assertIsDisplayed()
            assertEquals(top.value, compose.onNodeWithTag("execution_toggle").getUnclippedBoundsInRoot().top.value, 1f)
            compose.onNodeWithTag("execution_toggle").performClick()
            compose.onNodeWithTag("tool-timing-1").assertDoesNotExist()
            assertEquals(top.value, compose.onNodeWithTag("execution_toggle").getUnclippedBoundsInRoot().top.value, 1f)
        }
    }

    @Test fun expandingTheLatestTraceKeepsEarlierMessagesInPlace() {
        val list = androidx.compose.foundation.lazy.LazyListState()
        val turns = (1..8).map { index -> ChatTurn("anchor-$index", "第 $index 个请求", status = "done",
            answer = if (index == 8) "" else "第 $index 个结果。".repeat(10),
            steps = (1..40).map { TaskStep("anchor-$index-$it", "工具 $index/$it", done = true) }) }
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize()) {
            ChatMessages(turns, "", Modifier.fillMaxSize(), list)
        } } }
        compose.runOnIdle { list.requestScrollToItem(0, -list.layoutInfo.viewportEndOffset / 2) }
        val toggle = compose.onAllNodesWithTag("execution_toggle").onFirst()
        val prompt = compose.onNodeWithText("第 8 个请求")
        val titleTop = toggle.getUnclippedBoundsInRoot().top
        val promptTop = prompt.getUnclippedBoundsInRoot().top
        repeat(3) {
            toggle.performClick()
            assertEquals(titleTop.value, toggle.getUnclippedBoundsInRoot().top.value, 1f)
            assertEquals(promptTop.value, prompt.getUnclippedBoundsInRoot().top.value, 1f)
        }
    }

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
