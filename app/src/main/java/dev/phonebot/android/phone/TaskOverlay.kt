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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.phonebot.android.PhoneBotTheme
import dev.phonebot.android.PhoneBotLogo
import dev.phonebot.android.BotAvatar
import dev.phonebot.android.PhoneUi
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
    private var task = ""
    val taskId get() = task
    private var expanded by mutableStateOf(false)
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

    private fun move(x: Int, y: Int) {
        val view = window ?: return
        val windowInsets = if (Build.VERSION.SDK_INT >= 30) WindowInsetsCompat.toWindowInsetsCompat(manager.currentWindowMetrics.windowInsets)
            else ViewCompat.getRootWindowInsets(view)
        val insets = windowInsets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val left = insets?.left ?: 0; val top = insets?.top ?: 0
        val nextX = x.coerceIn(left, (area.width() - view.width - (insets?.right ?: 0)).coerceAtLeast(left))
        val nextY = y.coerceIn(top, (area.height() - view.height - (insets?.bottom ?: 0)).coerceAtLeast(top))
        if (params.x == nextX && params.y == nextY) return
        params.x = nextX; params.y = nextY
        manager.updateViewLayout(view, params)
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
        else service.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    fun resize() { area = screenBounds(); move(params.x, params.y) }

    @Composable private fun Content() {
        val target = display
        if (expanded && target?.virtual == true) {
            val frame by remember(target) { target.previewFrames() }.collectAsState(initial = null)
            frame?.let { Preview(it) } ?: Bubble { expanded = false }
        } else Bubble {
            if (target?.virtual == true) expanded = true
            else service.startActivity(TaskNotifications.open(service, start))
        }
    }

    @Composable private fun Bubble(onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        Box(Modifier.size(PhoneUi.touch).clickable(interaction, indication = null, onClick = onClick).testTag("task-overlay")
            .semantics { contentDescription = if (display?.virtual == true) "查看 Agent 屏幕" else "返回当前会话" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(PhoneUi.bubble).clip(CircleShape).background(colors.background)
                .border(PhoneUi.hairline, colors.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                BotAvatar(start.optLong("avatarColor", 0L), Modifier.fillMaxSize(), working = true, interactionSource = interaction)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(PhoneUi.tiny).size(PhoneUi.activityDot)
                .background(colors.tertiary, CircleShape).border(PhoneUi.frameLine, colors.background, CircleShape))
        }
    }

    @Composable private fun Preview(frame: Bitmap) {
        val ratio = frame.width.toFloat() / frame.height
        val width = with(LocalDensity.current) {
            minOf(PhoneUi.previewWidth, area.width().toDp() - PhoneUi.gap * 2, area.height().toDp() * PhoneUi.previewHeightFraction * ratio)
        }
        val blue = MaterialTheme.colorScheme.tertiary
        Box(Modifier.padding(PhoneUi.space)) {
            Box(Modifier.dropShadow(PhoneUi.frameShape, Shadow(radius = PhoneUi.glowRadius, color = blue.copy(alpha = PhoneUi.glowAlpha)))
                .clip(PhoneUi.frameShape).background(blue).clickable { expanded = false }
                .testTag("task-preview").semantics { contentDescription = "收起 Agent 屏幕" }.padding(PhoneUi.frameLine)) {
                Image(frame.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.width(width).aspectRatio(ratio).clip(PhoneUi.frameContentShape))
            }
        }
    }

    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val view = window ?: return block()
        view.visibility = View.INVISIBLE
        try {
            repeat(2) { suspendCancellableCoroutine { continuation ->
                Choreographer.getInstance().postFrameCallback { if (continuation.isActive) continuation.resume(Unit) }
            } }
            return block()
        } finally { if (window === view) view.visibility = View.VISIBLE }
    }

    fun close() {
        window?.let { manager.removeViewImmediate(it) }
        window = null; task = ""; display = null; expanded = false
    }

    fun destroy() { close(); registry.currentState = Lifecycle.State.DESTROYED }
}
