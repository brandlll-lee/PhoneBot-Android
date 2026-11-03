package dev.phonebot.android

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.ColorUtils
import dev.phonebot.android.phone.TaskPreview
import dev.phonebot.android.phone.taskPreviewWidth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class BotVisualTest {
    private val motion = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(0f) }
    @get:Rule val compose = createComposeRule(effectContext = motion)

    @Test fun actionColorsKeepWhiteReadableAcrossHuesAndDepths() {
        val colors = listOf(0L, 0xFFFFFFFFL, 0xFF555555L, 0xFFD8B4FEL, 0xFF8B5CF6L,
            0xFF3985FFL, 0xFF10B981L, 0xFFFFFF00L, 0xFFCCFF88L)
        for (color in colors) {
            val accent = botActionColor(color).toArgb()
            assertTrue(ColorUtils.calculateContrast(android.graphics.Color.WHITE, accent) >= 4.5)
        }
        assertEquals(botActionColor(0L), botActionColor(0xFFFFFFFFL))
        val light = FloatArray(3).also { ColorUtils.colorToHSL(botActionColor(0xFFD8B4FEL).toArgb(), it) }
        val deep = FloatArray(3).also { ColorUtils.colorToHSL(botActionColor(0xFF8B5CF6L).toArgb(), it) }
        assertEquals(light[1], deep[1], .02f)
        assertEquals(light[2], deep[2], .02f)
    }

    @Test fun headerTracksProviderAndKeepsOneSettingsAction() {
        val bot = mutableStateOf(ChatSession(title = "小 Bot", provider = "openai"))
        var clicks = 0
        compose.setContent { PhoneBotTheme { Surface { Column {
            ChatHeader(true, {}, { clicks++ }, {}, {}, bot.value)
        } } } }
        compose.onNodeWithTag("provider-icon-openai", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_identity").performClick()
        compose.runOnIdle { assertEquals(1, clicks); bot.value = bot.value.copy(provider = "custom") }
        compose.onNodeWithTag("provider-icon-custom", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { bot.value = bot.value.copy(provider = "") }
        compose.onNodeWithTag("provider-icon-custom", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("小 Bot").assertIsDisplayed()
    }

    @Test fun composerUsesTheBotAccentForSendAndStopInBothThemes() {
        val appearance = mutableStateOf(Appearance.LIGHT)
        val color = mutableStateOf(0xFFD8B4FEL)
        val busy = mutableStateOf(false)
        var stopped = false
        compose.setContent { PhoneBotTheme(appearance.value) {
            CompositionLocalProvider(LocalBotActionColor provides botActionColor(color.value)) {
                TaskComposer("任务", {}, busy.value, true, { true }, { stopped = true })
            }
        } }
        for (theme in listOf(Appearance.LIGHT, Appearance.DARK)) {
            compose.runOnIdle { appearance.value = theme }
            for (botColor in listOf(0xFFD8B4FEL, 0xFF3985FFL, 0xFFFFFF00L)) {
                compose.runOnIdle { color.value = botColor }
                for (running in listOf(false, true)) {
                    compose.runOnIdle { busy.value = running }
                    val pixels = compose.onNodeWithTag("task_action", useUnmergedTree = true).captureToImage().toPixelMap()
                    assertEquals(botActionColor(botColor), pixels[pixels.width / 2, pixels.height / 10])
                    assertTrue((0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y] == Color.White } })
                }
            }
        }
        compose.onNodeWithContentDescription("停止任务").performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test fun previewMeasuresHalfSizeAndSeparatesResizeFromCollapse() {
        val large = mutableStateOf(false)
        var collapsed = false
        val frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        compose.setContent { PhoneBotTheme { Surface(Modifier.fillMaxSize()) {
            Column { TaskPreview(frame, 200.dp, large.value, Color(0xFF8B5CF6), { collapsed = true }, { large.value = !large.value }) }
        } } }
        val small = compose.onNodeWithTag("task-preview").getUnclippedBoundsInRoot()
        val window = compose.onNodeWithTag("task-preview-window").getUnclippedBoundsInRoot()
        assertEquals(100f, small.width.value, 1f)
        assertEquals(200f, small.height.value, 1f)
        assertEquals(small.width.value + PhoneUi.glowPadding.value * 2, window.width.value, 1f)
        compose.onNodeWithContentDescription("放大 Agent 屏幕").performClick()
        val full = compose.onNodeWithTag("task-preview").getUnclippedBoundsInRoot()
        assertEquals(small.width.value * 2, full.width.value, 1f)
        assertEquals(small.height.value * 2, full.height.value, 1f)
        compose.runOnIdle { assertFalse(collapsed) }
        compose.onNodeWithContentDescription("缩小 Agent 屏幕").performClick()
        compose.onNodeWithTag("task-preview").performClick()
        compose.runOnIdle { assertTrue(collapsed) }
    }

    @Test fun previewFitsTheSafeAreaAfterRotation() {
        val padding = PhoneUi.glowPadding * 2
        for ((area, safe) in listOf(
            DpSize(720.dp, 320.dp) to DpSize(720.dp, 248.dp),
            DpSize(320.dp, 720.dp) to DpSize(248.dp, 720.dp),
        )) {
            val width = taskPreviewWidth(area, safe, .5f)
            assertTrue(width + padding <= safe.width)
            assertTrue(width / .5f + padding <= safe.height)
        }
        assertEquals(104f, taskPreviewWidth(DpSize(720.dp, 320.dp), DpSize(720.dp, 248.dp), .5f).value, .01f)
    }

    @Test fun glowDrawsInsideAndOutsideAndBreathesWithoutRepeatedLayout() {
        compose.mainClock.autoAdvance = false
        val frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val background = Color(0xFF303030)
        var placements = 0
        compose.setContent { PhoneBotTheme { Box(Modifier.background(background)) {
            TaskPreview(frame, 120.dp, true, Color(0xFF8B5CF6), {}, {}, Modifier.onGloballyPositioned { placements++ })
        } } }
        fun capture() = compose.onNodeWithTag("task-preview-window").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(32)
        val still = capture()
        val density = still.width / (120f + PhoneUi.glowPadding.value * 2)
        fun pixel(bitmap: Bitmap, x: Float, y: Float) = Color(bitmap.getPixel((x * density).toInt(), (y * density).toInt()))
        val edge = PhoneUi.glowPadding.value
        val outside = pixel(still, edge - 2, edge + 120)
        val inside = pixel(still, edge + 2, edge + 120)
        assertNotEquals(background, outside)
        assertTrue("Outer glow lost its hue", outside.blue > outside.green)
        assertNotEquals(Color.White, inside)
        assertTrue("Inner glow lost its hue", inside.blue > inside.green)
        for (y in listOf(60f, 120f, 180f)) {
            val line = pixel(still, edge + .5f, edge + y)
            assertTrue("Rim has a gap at $y", line.blue > line.green)
        }
        compose.mainClock.advanceTimeBy(2000)
        assertTrue("Zero motion scale changed the glow", still.sameAs(capture()))
        compose.runOnIdle { motion.scaleFactor = 1f }
        compose.mainClock.advanceTimeBy(32)
        val start = capture()
        val layoutCount = placements
        compose.mainClock.advanceTimeBy(2000)
        assertFalse("Glow did not breathe", start.sameAs(capture()))
        assertTrue("Breathing repeatedly ran layout", placements <= layoutCount + 1)
        compose.runOnIdle { motion.scaleFactor = 0f }
        compose.mainClock.advanceTimeBy(32)
        val stopped = capture()
        compose.mainClock.advanceTimeBy(2000)
        assertTrue("Glow kept moving at zero scale", stopped.sameAs(capture()))
    }
}
