package dev.phonebot.android

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatRendersBothPalettesAndFollowsSystem() {
        val mode = mutableStateOf(Appearance.DARK)
        val night = mutableStateOf(false)
        val draft = mutableStateOf("")
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PhoneBotTheme(mode.value) {
                    Surface(Modifier.fillMaxSize().testTag("chat"), color = MaterialTheme.colorScheme.background) {
                        Column {
                            ChatHeader(true, {}, {}, {}, {})
                            ChatMessages(listOf(ChatTurn("theme", "打开相册", answer = "**已打开相册。**\n\n```kotlin\nval ready = true\n```", status = "done")), "", Modifier.weight(1f))
                            TaskComposer(draft.value, { draft.value = it }, false, true, { true }, {})
                        }
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (appearance in listOf(Appearance.DARK, Appearance.LIGHT)) {
            compose.runOnIdle { mode.value = appearance }
            compose.waitUntil(5000) { compose.onAllNodes(hasText("已打开相册。")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("PhoneBot").assertIsDisplayed()
            compose.onNodeWithContentDescription("更多选项").performClick()
            compose.onNodeWithText("设置").assertIsDisplayed()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithTag("task_input").assertIsDisplayed()
            val image = compose.onNodeWithTag("chat").captureToImage()
            assertEquals(if (appearance == Appearance.DARK) Color.Black else Color.White, image.toPixelMap()[0, 0])
            File(context.cacheDir, "chat-${appearance.name.lowercase()}.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { mode.value = Appearance.SYSTEM }
        for (dark in listOf(true, false)) {
            compose.runOnIdle { night.value = dark }
            assertEquals(if (dark) Color.Black else Color.White, compose.onNodeWithTag("chat").captureToImage().toPixelMap()[0, 0])
        }
    }
}
