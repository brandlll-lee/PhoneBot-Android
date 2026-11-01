package dev.phonebot.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable internal fun PhoneBotLogo(modifier: Modifier = Modifier, working: Boolean = false, interactionSource: InteractionSource? = null) {
    val ink = MaterialTheme.colorScheme.onBackground
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val blink = remember { Animatable(1f) }
    val activity = remember { Animatable(0f) }
    val wink = remember { Animatable(0f) }
    var reaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(interactionSource) {
        interactionSource?.interactions?.collect { if (it is PressInteraction.Press) reaction++ }
    }
    LaunchedEffect(lifecycle, working, reaction) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val duration = coroutineContext[MotionDurationScale]
            snapshotFlow { duration?.scaleFactor ?: 1f }.collectLatest { factor ->
                blink.snapTo(1f); activity.snapTo(0f); wink.snapTo(0f)
                if (factor <= 0f) return@collectLatest
                if (reaction > 0) {
                    wink.animateTo(1f, tween(120)); delay(70)
                    wink.animateTo(0f, tween(170))
                }
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(4200)
                            blink.animateTo(.08f, tween(100)); blink.animateTo(1f, tween(140))
                        }
                    }
                    if (working) launch {
                        while (isActive) {
                            activity.animateTo(1f, tween(1800, easing = FastOutSlowInEasing))
                            activity.animateTo(-1f, tween(3600, easing = FastOutSlowInEasing))
                        }
                    }
                }
            }
        }
    }
    Canvas(modifier.size(PhoneUi.logo)) {
        scale(size.minDimension / 256f, pivot = Offset.Zero) {
            val breathe = 1f + .012f * activity.value
            scale(breathe, pivot = Offset(128f, 128f)) {
                translate(top = 3f * wink.value) {
                    drawPhoneBot(ink, blink.value, 4f * activity.value, wink.value)
                }
            }
        }
    }
}
