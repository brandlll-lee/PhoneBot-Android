package dev.phonebot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.engine.TaskNotifications
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleWrapsContentAndFitsLongNames() {
        val name = mutableStateOf("小 Bot")
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize().testTag("screen")) {
            Column { ChatHeader(true, {}, {}, {}, {}, ChatSession(title = name.value)) }
        } } }
        val short = compose.onNodeWithTag("chat_identity").getUnclippedBoundsInRoot()
        val screen = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        assertTrue(short.width < screen.width * .6f)
        assertEquals((screen.left + screen.right).value, (short.left + short.right).value, 2f)
        compose.runOnIdle { name.value = "这是一位名字很长的 PhoneBot 手机搭档".repeat(4) }
        val long = compose.onNodeWithTag("chat_identity").getUnclippedBoundsInRoot()
        assertTrue(long.width > short.width)
        assertTrue(long.width < screen.width)
        assertEquals((screen.left + screen.right).value, (long.left + long.right).value, 2f)
        compose.onNodeWithContentDescription("打开对话列表").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多选项").assertIsDisplayed()
    }

    @Test fun avatarInkAndNotificationFollowTheSelectedColor() {
        val mode = mutableStateOf(Appearance.LIGHT)
        val color = mutableStateOf(0xFFAA2255L)
        compose.setContent { PhoneBotTheme(mode.value) {
            BotAvatar(color.value, Modifier.size(100.dp).testTag("avatar"))
        } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = PhoneAppearance.mode
        try { for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            compose.runOnIdle { mode.value = appearance; PhoneAppearance.mode = appearance }
            for (selected in listOf(0xFFAA2255L, 0xFFFFFFFFL, 0L)) {
                compose.runOnIdle { color.value = selected }
                val ink = botInk(selected, appearance == Appearance.DARK)
                val pixels = compose.onNodeWithTag("avatar").captureToImage().toPixelMap()
                assertTrue((0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y] == Color(ink) } })
                assertEquals(Color(botPaper(ink)), pixels[pixels.width / 2, pixels.height / 2])
                val notification = TaskNotifications.avatar(context, selected)
                assertEquals(ink, notification.getPixel(41, 47))
                assertEquals(botPaper(ink), notification.getPixel(48, 48))
            }
        } } finally { compose.runOnIdle { PhoneAppearance.mode = original } }
    }

    @Test fun bundledProviderMarksRenderInBothThemes() {
        val mode = mutableStateOf(Appearance.LIGHT)
        val ids = listOf("openai", "anthropic", "google", "deepseek", "openai-codex", "kimi-coding", "custom")
        compose.setContent { PhoneBotTheme(mode.value) { Surface {
            Column { ids.forEach { ProviderIcon(it) } }
        } } }
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            compose.runOnIdle { mode.value = appearance }
            ids.forEach { compose.onNodeWithTag("provider-icon-$it", useUnmergedTree = true).assertIsDisplayed().captureToImage() }
        }
        assertNotEquals(providerIconResource("openai"), providerIconResource("anthropic"))
        assertNull(providerIconResource("custom"))
    }
}
