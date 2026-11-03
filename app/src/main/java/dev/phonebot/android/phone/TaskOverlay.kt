package dev.phonebot.android.phone

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.phonebot.android.PhoneBotTheme
import dev.phonebot.android.BotAvatar
import dev.phonebot.android.botInk
import dev.phonebot.android.PhoneUi
import dev.phonebot.android.R
import dev.phonebot.android.engine.TaskNotifications
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class TaskOverlay(private val service: PhoneAccessibilityService) : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val manager = service.getSystemService(WindowManager::class.java)
    private var area by mutableStateOf(screenBounds())
    private var window: FrameLayout? = null
    private var safeArea by mutableStateOf(safeBounds())
    private var task = ""
    val taskId get() = task
    private var expanded by mutableStateOf(false)
    private var large by mutableStateOf(false)
    private var visible by mutableStateOf(true)
    private var display by mutableStateOf<PhoneDisplay?>(null)
    private var start by mutableStateOf(JSONObject())
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (PhoneUi.space.value * service.resources.displayMetrics.density).roundToInt()
        y = (PhoneUi.touch.value * service.resources.displayMetrics.density).roundToInt()
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    }

    init { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }

    fun update(start: JSONObject, target: PhoneDisplay) {
        val id = start.getString("taskId")
        if (id != task) { close(); task = id }
        this.start = start; display = target
        if (window == null) show()
    }

    private fun show() {
        val frame = object : FrameLayout(service) {
            private var downX = 0f; private var downY = 0f
            private var originX = 0; private var originY = 0
            private val slop = ViewConfiguration.get(service).scaledTouchSlop
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y
                }
                return event.actionMasked == MotionEvent.ACTION_MOVE && hypot(event.rawX - downX, event.rawY - downY) > slop
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) move(originX + (event.rawX - downX).roundToInt(), originY + (event.rawY - downY).roundToInt())
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        frame.addView(ComposeView(service).apply { setContent { PhoneBotTheme { this@TaskOverlay.Content() } } },
            FrameLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> move(params.x, params.y) }
        try { manager.addView(frame, params); window = frame }
        catch (error: RuntimeException) {
            (frame.getChildAt(0) as ComposeView).disposeComposition()
            Log.w("PhoneBot", "Task overlay unavailable", error)
        }
    }

    private fun safeBounds(): Rect {
        val windowInsets = if (Build.VERSION.SDK_INT >= 30) WindowInsetsCompat.toWindowInsetsCompat(manager.currentWindowMetrics.windowInsets)
            else window?.let { ViewCompat.getRootWindowInsets(it) }
        val insets = windowInsets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return Rect(insets?.left ?: 0, insets?.top ?: 0, area.width() - (insets?.right ?: 0), area.height() - (insets?.bottom ?: 0))
    }

    private fun move(x: Int, y: Int) {
        val view = window ?: return
        val bounds = safeBounds()
        if (safeArea != bounds) safeArea = bounds
        val nextX = x.coerceIn(bounds.left, (bounds.right - view.width).coerceAtLeast(bounds.left))
        val nextY = y.coerceIn(bounds.top, (bounds.bottom - view.height).coerceAtLeast(bounds.top))
        if (params.x == nextX && params.y == nextY) return
        params.x = nextX; params.y = nextY
        manager.updateViewLayout(view, params)
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
        else service.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    fun resize() { area = screenBounds(); move(params.x, params.y) }

    @Composable private fun Content() {
        if (!visible) return
        val target = display
        val ink = Color(botInk(start.optLong("avatarColor", 0L), MaterialTheme.colorScheme.background == Color.Black))
        if (expanded && target?.virtual == true) {
            val frame by remember(target) { target.previewFrames() }.collectAsState(initial = null)
            frame?.let {
                val ratio = it.width.toFloat() / it.height
                val width = with(LocalDensity.current) {
                    taskPreviewWidth(DpSize(area.width().toDp(), area.height().toDp()), DpSize(safeArea.width().toDp(), safeArea.height().toDp()), ratio)
                }
                TaskPreview(it, width, large, ink, { expanded = false }, { large = !large })
            } ?: Bubble { expanded = false }
        } else Bubble {
            if (target?.virtual == true) { large = false; expanded = true }
            else service.startActivity(TaskNotifications.open(service, start))
        }
    }

    @Composable private fun Bubble(onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val ink = Color(botInk(start.optLong("avatarColor", 0L), colors.background == Color.Black))
        val motion = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
        val interaction = remember { MutableInteractionSource() }
        Box(Modifier.size(PhoneUi.touch).clickable(interaction, indication = null, onClick = onClick).testTag("task-overlay")
            .semantics { contentDescription = if (display?.virtual == true) "查看 Agent 屏幕" else "返回当前会话" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(PhoneUi.bubble), contentAlignment = Alignment.Center) {
                BotAvatar(start.optLong("avatarColor", 0L), Modifier.fillMaxSize().padding(PhoneUi.tiny), working = true, interactionSource = interaction)
            }
            if (motion) CircularProgressIndicator(Modifier.size(PhoneUi.bubble), color = ink,
                strokeWidth = PhoneUi.activityRing, trackColor = ink.copy(alpha = PhoneUi.activityTrackAlpha))
            else Box(Modifier.size(PhoneUi.bubble).border(PhoneUi.activityRing, ink, CircleShape))
        }
    }

    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val view = window ?: return block()
        visible = false
        view.visibility = View.INVISIBLE
        try {
            repeat(2) { suspendCancellableCoroutine { continuation ->
                Choreographer.getInstance().postFrameCallback { if (continuation.isActive) continuation.resume(Unit) }
            } }
            return block()
        } finally { if (window === view) { visible = true; view.visibility = View.VISIBLE } }
    }

    fun close() {
        window?.let { manager.removeViewImmediate(it) }
        window = null; task = ""; display = null; expanded = false; large = false; visible = true
    }

    fun destroy() { close(); registry.currentState = Lifecycle.State.DESTROYED }
}

internal fun taskPreviewWidth(area: DpSize, safeArea: DpSize, ratio: Float): Dp = minOf(
    PhoneUi.previewWidth, area.width - PhoneUi.gap * 2, area.height * PhoneUi.previewHeightFraction * ratio,
    (safeArea.width - PhoneUi.glowPadding * 2).coerceAtLeast(0.dp),
    (safeArea.height - PhoneUi.glowPadding * 2).coerceAtLeast(0.dp) * ratio,
)

@Composable internal fun TaskPreview(frame: Bitmap, fullWidth: Dp, large: Boolean, ink: Color, collapse: () -> Unit, resize: () -> Unit,
    modifier: Modifier = Modifier) {
    val width = if (large) fullWidth else fullWidth * PhoneUi.previewScale
    val ratio = frame.width.toFloat() / frame.height
    val glowRadius = with(LocalDensity.current) { PhoneUi.glowRadius.toPx() }
    val innerGlowRadius = with(LocalDensity.current) { PhoneUi.innerGlowRadius.toPx() }
    val breath = remember { Animatable(PhoneUi.glowBreathMin) }
    val motion = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    LaunchedEffect(motion) {
        breath.snapTo(PhoneUi.glowBreathMin)
        if (motion) breath.animateTo(1f, infiniteRepeatable(tween(PhoneUi.glowBreathMillis), RepeatMode.Reverse))
    }
    val rim = remember(ink) { Brush.sweepGradient(listOf(ink, lerp(ink, Color.White, .65f), ink, ink.copy(alpha = .65f), ink)) }
    Box(modifier.testTag("task-preview-window").padding(PhoneUi.glowPadding)) {
        Box(Modifier.width(width).aspectRatio(ratio)
            .border(PhoneUi.frameLine, rim, PhoneUi.frameShape)
            .clickable(onClick = collapse).testTag("task-preview").semantics { contentDescription = "收起 Agent 屏幕" }) {
            Box(Modifier.matchParentSize().graphicsLayer {
                alpha = breath.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }.dropShadow(PhoneUi.frameShape) {
                radius = glowRadius
                color = ink
                alpha = PhoneUi.glowAlpha
            })
            Image(frame.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(PhoneUi.frameShape))
            Box(Modifier.matchParentSize().graphicsLayer { alpha = breath.value }.innerShadow(PhoneUi.frameShape) {
                radius = innerGlowRadius
                color = ink
                alpha = PhoneUi.glowAlpha
            })
            IconButton(onClick = resize, modifier = Modifier.align(Alignment.TopEnd).size(PhoneUi.touch)) {
                Box(Modifier.size(PhoneUi.previewControl).background(MaterialTheme.colorScheme.background.copy(alpha = PhoneUi.glassAlpha), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (large) R.drawable.ic_preview_shrink else R.drawable.ic_preview_expand),
                        if (large) "缩小 Agent 屏幕" else "放大 Agent 屏幕", Modifier.size(PhoneUi.previewControlIcon),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
