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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.phonebot.android.PhoneBotTheme
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
    private var title by mutableStateOf("")
    private var status by mutableStateOf("正在执行")
    private var expanded by mutableStateOf(false)
    private var zoomed by mutableStateOf(false)
    private var display by mutableStateOf<PhoneDisplay?>(null)
    private var start = JSONObject()
    private var stop: () -> Unit = {}
    private var stopping by mutableStateOf(false)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START; x = 16; y = 160
        if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    }

    init { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }

    fun update(start: JSONObject, target: PhoneDisplay, event: JSONObject?, onStop: () -> Unit) {
        val id = start.getString("taskId")
        if (id != task) { close(); task = id; status = "正在执行" }
        this.start = start; title = start.getString("prompt"); display = target; stop = onStop
        when (event?.optString("type")) {
            "model_request", "thinking_delta", "text_delta" -> if (!stopping) status = "正在思考"
            "step" -> status = event.optString("label").ifBlank { event.optString("name") }
            "permission" -> status = event.optString("message").ifBlank { "等待授权，请返回会话处理" }
        }
        if (window == null) show()
    }

    private fun show() {
        val frame = object : FrameLayout(service) {
            private var downX = 0f; private var downY = 0f
            private var originX = 0; private var originY = 0
            private var handle = false
            private val slop = ViewConfiguration.get(service).scaledTouchSlop
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y
                    handle = event.y < 56 * resources.displayMetrics.density
                }
                return handle && event.actionMasked == MotionEvent.ACTION_MOVE && hypot(event.rawX - downX, event.rawY - downY) > slop
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
        val width = with(LocalDensity.current) { area.width().toDp() - 32.dp }
        Surface(shape = RoundedCornerShape(if (expanded) 24.dp else 28.dp), tonalElevation = 6.dp, shadowElevation = 8.dp,
            modifier = Modifier.testTag("task-overlay")) {
            if (!expanded) {
                Text("◉ PhoneBot", fontSize = 14.sp, modifier = Modifier.clickable { expanded = true }.padding(horizontal = 18.dp, vertical = 16.dp))
            } else Column(Modifier.width(minOf(340.dp, width)).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    IconButton(onClick = { expanded = false; zoomed = false }) { Icon(Icons.Default.Close, "收起预览") }
                }
                Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                if (target?.virtual == true) {
                    val frame by remember(target) { target.previewFrames() }.collectAsState(initial = null)
                    Preview(frame, zoomed) { zoomed = !zoomed }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { service.startActivity(TaskNotifications.open(service, start)); expanded = false; zoomed = false }) {
                        Text("返回会话")
                    }
                    TextButton(enabled = !stopping, onClick = { stopping = true; status = "正在停止"; stop() }) {
                        Text(if (stopping) "正在停止" else "停止任务")
                    }
                }
            }
        }
    }

    @Composable private fun Preview(frame: Bitmap?, zoomed: Boolean, onClick: () -> Unit) {
        val height = with(LocalDensity.current) { area.height().toDp() * if (zoomed) 0.60f else 0.30f }
        if (frame == null) Text("等待虚拟屏画面", Modifier.height(80.dp).padding(12.dp), fontSize = 13.sp)
        else Image(frame.asImageBitmap(), "Agent 虚拟屏画面", Modifier.fillMaxWidth().height(height).clickable(onClick = onClick).testTag("task-preview"))
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
        window = null; task = ""; display = null; expanded = false; zoomed = false; stopping = false; stop = {}
    }

    fun destroy() { close(); registry.currentState = Lifecycle.State.DESTROYED }
}
