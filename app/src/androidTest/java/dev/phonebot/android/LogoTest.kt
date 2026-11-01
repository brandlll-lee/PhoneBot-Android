package dev.phonebot.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LogoTest {
    private val motion = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(0f) }
    @get:Rule val compose = createComposeRule(effectContext = motion)

    private fun frame() = compose.onNodeWithTag("logo").captureToImage().asAndroidBitmap()
    private fun different(a: Bitmap, b: Bitmap): Boolean {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        return (0 until a.width step 2).any { x -> (0 until a.height step 2).any { y -> a.getPixel(x, y) != b.getPixel(x, y) } }
    }

    @Test fun palettesAndLauncherUseThePhoneFace() {
        val dark = mutableStateOf(false)
        var ink = 0
        compose.setContent { PhoneBotTheme(if (dark.value) Appearance.DARK else Appearance.LIGHT) {
            ink = MaterialTheme.colorScheme.onBackground.toArgb()
            Box(Modifier.background(if (dark.value) Color.Black else Color.White)) { PhoneBotLogo(Modifier.size(200.dp).testTag("logo")) }
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            val bitmap = frame()
            fun pixel(x: Int, y: Int) = bitmap.getPixel(x * bitmap.width / 256, y * bitmap.height / 256)
            val paper = if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            assertEquals(ink, pixel(52, 128)); assertEquals(paper, pixel(128, 128))
            assertEquals(ink, pixel(102, 124)); assertEquals(ink, pixel(154, 124))
            assertEquals(ink, pixel(128, 53)); assertEquals(ink, pixel(128, 202))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(110, 126))
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(146, 126))
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(128, 126))
    }

    @Test fun motionFollowsWorkPressLifecycleAndSystemScale() {
        compose.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val working = mutableStateOf(true)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { PhoneBotTheme(Appearance.LIGHT) {
            Box(Modifier.background(Color.White)) { PhoneBotLogo(Modifier.size(200.dp).testTag("logo"), working.value, source) }
        } } }
        compose.mainClock.advanceTimeBy(32)
        val still = frame()
        compose.mainClock.advanceTimeBy(1000)
        assertFalse(different(still, frame()))
        compose.runOnIdle { motion.scaleFactor = 1f }
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Working face did not move", different(still, frame()))
        compose.runOnIdle { working.value = false }
        compose.mainClock.advanceTimeBy(32)
        val resting = frame()
        compose.runOnIdle { assertTrue(source.tryEmit(PressInteraction.Press(Offset.Zero))) }
        compose.mainClock.advanceTimeBy(144)
        assertTrue("Press did not animate the face", different(resting, frame()))
        compose.mainClock.advanceTimeBy(400)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(32)
        val stopped = frame()
        compose.mainClock.advanceTimeBy(5000)
        assertFalse("Hidden face kept moving", different(stopped, frame()))
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { motion.scaleFactor = 0f }
        compose.mainClock.advanceTimeBy(32)
        assertFalse("System animation setting was ignored", different(resting, frame()))
    }
}
