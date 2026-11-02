package dev.phonebot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.State
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstContentFrameHasItsInsetsAndBotSwitchReusesParsing() {
        val bot = mutableStateOf("one")
        val markdown = mutableStateMapOf<String, Pair<String, State>>()
        val frames = mutableListOf<Triple<String, Int, Int>>()
        val preparation = mutableListOf<Pair<String, Long>>()
        compose.setContent { PhoneBotTheme {
            ChatLayout(Modifier.fillMaxSize(), header = { Box(Modifier.fillMaxWidth().height(64.dp)) },
                composer = { Box(Modifier.fillMaxWidth().height(72.dp)) }) { padding ->
                key(bot.value) {
                    val id = bot.value
                    val began = remember { android.os.SystemClock.uptimeMillis() }
                    val first = remember { booleanArrayOf(true) }
                    val list = remember { LazyListState() }
                    ChatMessages(listOf(ChatTurn(id, "请求 $id", status = "done",
                        answer = (1..40).joinToString("\n\n") { "**段落 $it**：$id" } + "\n\n最新末段 $id")), "",
                        Modifier.fillMaxSize().onGloballyPositioned {
                            if (list.layoutInfo.visibleItemsInfo.isNotEmpty()) frames.add(Triple(id, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset))
                            if (first[0] && list.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                                preparation.add(id to (android.os.SystemClock.uptimeMillis() - began)); first[0] = false
                            }
                        }, list, contentPadding = padding, markdown = markdown, botId = id)
                    assertEquals(64.dp + PhoneUi.space, padding.calculateTopPadding())
                    assertEquals(72.dp + PhoneUi.space, padding.calculateBottomPadding())
                }
            }
        } }
        compose.waitUntil(5000) { markdown.containsKey("one/one/reply/") }
        compose.onNodeWithText("最新末段 one", substring = true).assertIsDisplayed()
        val parsed = markdown.getValue("one/one/reply/").second
        compose.runOnIdle { bot.value = "two" }
        compose.waitUntil(5000) { markdown.containsKey("two/two/reply/") }
        compose.onNodeWithText("最新末段 two", substring = true).assertIsDisplayed()
        compose.runOnIdle { bot.value = "one" }
        compose.onNodeWithText("最新末段 one", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertSame(parsed, markdown.getValue("one/one/reply/").second)
            assertTrue(frames.toString(), frames.isNotEmpty() && frames.all { it.second == 0 && it.third == 0 })
            assertEquals(2, markdown.size)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2,
                android.os.Bundle().apply { putString("firstContentMs", preparation.toString()) })
        }
    }

    @Test fun sendingFromHistoryReturnsToTheRequestOnce() {
        val turns = mutableStateOf((1..20).map { ChatTurn("old-$it", "旧请求 $it", status = "done", answer = "历史回复 $it。".repeat(30)) })
        val submission = mutableStateOf("")
        val draft = mutableStateOf("")
        val list = LazyListState()
        var handled = 0
        compose.setContent { PhoneBotTheme {
            ChatLayout(Modifier.fillMaxSize(), header = { Box(Modifier.height(64.dp)) }, composer = {
                TaskComposer(draft.value, { draft.value = it }, false, true, { prompt ->
                    turns.value = turns.value + ChatTurn("sent", prompt, status = "done")
                    submission.value = "sent"
                    true
                }, {})
            }) { padding ->
                ChatMessages(turns.value, "", Modifier.fillMaxSize(), list, contentPadding = padding, submittedTask = submission.value,
                    onSubmissionHandled = { handled++; submission.value = "" })
            }
        } }
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("旧请求 3"))
        compose.onNodeWithText("旧请求 3").assertIsDisplayed()
        compose.onNodeWithTag("task_input").performTextInput("主动发送的新请求")
        compose.onNodeWithContentDescription("发送任务").performClick()
        compose.onNodeWithText("主动发送的新请求").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, handled) }
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("旧请求 3"))
        compose.runOnIdle { turns.value = turns.value.map { if (it.id == "sent") it.copy(answer = "后台更新") else it } }
        compose.onNodeWithText("旧请求 3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, handled) }
    }

    @Test fun streamingDuringSendKeepsFollowingUntilUserScrolls() {
        val turns = mutableStateOf((1..30).map { ChatTurn("history-$it", "历史请求 $it", status = "done") })
        val active = mutableStateOf("")
        val submission = mutableStateOf("")
        val list = LazyListState()
        var handled = 0
        compose.setContent { PhoneBotTheme {
            ChatMessages(turns.value, active.value, Modifier.fillMaxSize(), list, submittedTask = submission.value,
                onSubmissionHandled = { handled++; submission.value = "" })
        } }
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("历史请求 3"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            active.value = "streamed"
            turns.value = turns.value + ChatTurn("streamed", "本次请求")
            submission.value = "streamed"
        }
        repeat(5) { part ->
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { turns.value = turns.value.dropLast(1) + turns.value.last().copy(streamText = "流式回复。".repeat((part + 1) * 40)) }
        }
        compose.mainClock.advanceTimeBy(5000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, handled)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("历史请求 3"))
        compose.runOnIdle { turns.value = turns.value.dropLast(1) + turns.value.last().copy(streamText = "更多流式回复。".repeat(100)) }
        compose.onNodeWithText("历史请求 3").assertIsDisplayed()
    }
}
