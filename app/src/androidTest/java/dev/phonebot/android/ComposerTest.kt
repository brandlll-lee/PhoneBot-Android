package dev.phonebot.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun screenSelectionChangesAndLocksDuringExecution() {
        val current = mutableStateOf("main")
        val enabled = mutableStateOf(true)
        var settings = false
        compose.setContent { PhoneBotTheme {
            ScreenSelector(current.value, enabled.value, { current.value = it }, { settings = true })
        } }
        compose.onNodeWithContentDescription("选择屏幕").performClick()
        compose.onNodeWithText("虚拟屏幕").performClick()
        compose.runOnIdle { assertEquals("virtual", current.value) }
        compose.onNodeWithText("虚拟屏幕").assertExists()
        compose.onNodeWithContentDescription("选择屏幕").performClick()
        compose.onNodeWithText("会话设置").performClick()
        assertTrue(settings)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("screen_selector").assertIsNotEnabled()
    }

    @Test fun composerExpandsCapsAndCollapsesAfterAcceptedSend() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var accepted = false
        var message = ""
        var selected = ""
        var keyboardHeight = 0
        compose.setContent { PhoneBotTheme {
            val height = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardHeight = height }
            Box(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                TaskComposer("very-long-model-name-with-a-readable-ending", listOf(PiModel("second", "另一个模型", false)), false, true,
                    {}, { selected = it }, {}, { message = it; accepted }, {})
            }
        } } }
        compose.onNodeWithContentDescription("选择模型").performClick()
        compose.onNodeWithText("另一个模型").performClick()
        assertEquals("second", selected)
        val input = compose.onNodeWithTag("task_input")
        val compact = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height
        input.performClick().performTextInput("第一行\n第二行")
        compose.waitUntil(5000) { keyboardHeight > 0 }
        val expanded = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height
        assertTrue(expanded > compact)
        input.performTextReplacement((1..12).joinToString("\n") { "第${it}行中文任务" })
        val capped = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height
        input.performTextReplacement((1..30).joinToString("\n") { "第${it}行中文任务" })
        assertEquals(capped, compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height, 2f)
        compose.onNodeWithContentDescription("发送任务").performClick()
        input.assertIsFocused().assertTextContains("第30行中文任务", substring = true)
        compose.runOnIdle { accepted = true }
        input.performImeAction()
        input.assertIsNotFocused().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.waitUntil(5000) { keyboardHeight == 0 }
        assertTrue(message.contains("第30行中文任务"))
        assertEquals(compact, compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height, 2f)
    }
}
