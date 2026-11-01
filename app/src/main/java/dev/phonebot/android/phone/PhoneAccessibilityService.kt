package dev.phonebot.android.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.util.size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        var instance: PhoneAccessibilityService? = null; private set
        fun listApps(context: Context): JSONObject {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val manager = context.packageManager
            val apps = manager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(manager).toString() }
            return JSONObject().put("apps", JSONArray(apps.take(300).map { JSONObject().put("package", it.activityInfo.packageName).put("name", it.loadLabel(manager).toString().take(120)) })).put("truncated", apps.size > 300)
        }
    }
    private val chineseHolder = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val latinHolder = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    internal var overlay: TaskOverlay? = null; private set
    private val screenEvents = mutableMapOf<Int, Long>()
    internal fun lastScreenEvent(displayId: Int) = screenEvents[displayId] ?: 0L

    override fun onServiceConnected() {
        instance = this
        overlay?.destroy()
        overlay = TaskOverlay(this)
        if (Build.VERSION.SDK_INT >= 33) serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val displays = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays else android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>().apply { put(Display.DEFAULT_DISPLAY, windows) }
        for (i in 0 until displays.size) {
            if (displays.valueAt(i).any { it.id == event.windowId && it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY })
                screenEvents[displays.keyAt(i)] = SystemClock.uptimeMillis()
        }
    }
    override fun onInterrupt() = Unit
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.resize()
    }
    override fun onDestroy() {
        instance = null
        overlay?.destroy(); overlay = null
        if (chineseHolder.isInitialized()) chineseHolder.value.close()
        if (latinHolder.isInitialized()) latinHolder.value.close()
        super.onDestroy()
    }

    internal fun available(target: PhoneDisplay): Boolean = if (target.virtual) target.alive() else
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(PowerManager::class.java).isInteractive

    internal suspend fun gesture(path: Path, duration: Long) {
        val preview = overlay
        if (preview != null) preview.hiddenWhile { dispatch(path, duration) } else dispatch(path, duration)
    }

    private suspend fun dispatch(path: Path, duration: Long): Unit = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手势被系统取消")) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统拒绝执行手势"))
    }

    private suspend fun capture(target: PhoneDisplay): Bitmap {
        if (target.virtual) return target.frame()
        val preview = overlay
        return if (preview != null) preview.hiddenWhile { captureMain() } else captureMain()
    }

    private suspend fun captureMain(): Bitmap {
        if (Build.VERSION.SDK_INT < 30) error("截图和 OCR 需要 Android 11 或更高版本")
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            ?: error("截图转换失败")
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    finally { result.hardwareBuffer.close() }
                }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("截图失败（$errorCode）。安全页面可能禁止截图。")) }
            })
        }
    }

    internal suspend fun read(screen: ScreenState, target: PhoneDisplay, ocr: Boolean, image: Boolean): JSONObject {
        screen.scan()
        if (!ocr && !image) return screen.present(true)
        val geometry = screen.geometry
        val bitmap = capture(target)
        try {
            screen.scan()
            check(screen.geometry.rotation == geometry.rotation && screen.geometry.width == geometry.width && screen.geometry.height == geometry.height) { "屏幕方向已变化，请重新读屏" }
            val result = screen.present(true)
            val scaleX = geometry.width.toDouble() / bitmap.width
            val scaleY = geometry.height.toDouble() / bitmap.height
            if (ocr) {
                val additions = JSONArray()
                val existing = screen.text().toMutableSet()
                for (recognizer in listOf(chineseHolder.value, latinHolder.value)) {
                    coroutineContext.ensureActive()
                    // ML Kit owns the bitmap until its asynchronous task completes.
                    val recognized = withContext(NonCancellable) {
                        suspendCancellableCoroutine { continuation ->
                            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                        }
                    }
                    for (line in recognized.textBlocks.flatMap { it.lines }) {
                        if (additions.length() >= 60) break
                        val text = line.text.trim()
                        if (text.isEmpty() || existing.any { it.contains(text) }) continue
                        existing += text
                        val bounds = line.boundingBox ?: continue
                        additions.put(JSONObject().put("text", text.take(120)).put("bounds", JSONArray(listOf(
                            (bounds.left * scaleX).toInt(), (bounds.top * scaleY).toInt(), (bounds.right * scaleX).toInt(), (bounds.bottom * scaleY).toInt()))))
                    }
                }
                result.put("ocr", additions)
            }
            if (image) {
                coroutineContext.ensureActive()
                val directory = File(cacheDir, "screens").apply { mkdirs() }
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
                val file = File(directory, "${System.nanoTime()}.jpg")
                val encoded = dev.phonebot.android.engine.ModelImages.encode(bitmap, file)
                result.put("image", file.path).put("imageInfo", JSONObject().put("mimeType", "image/jpeg")
                    .put("width", encoded.getInt("width")).put("height", encoded.getInt("height")).put("sourceWidth", bitmap.width).put("sourceHeight", bitmap.height)
                    .put("screenPerImageX", geometry.width.toDouble() / encoded.getInt("width")).put("screenPerImageY", geometry.height.toDouble() / encoded.getInt("height")))
            }
            return result
        } finally { bitmap.recycle() }
    }

}

internal class PhoneExecution(private val service: PhoneAccessibilityService, val target: PhoneDisplay, id: String) {
    private val screen = ScreenState(service).apply { taskId = id }
    private val actions = PhoneActions(service, screen, target)

    suspend fun execute(name: String, params: JSONObject, report: (JSONObject) -> Unit = {}): JSONObject {
        coroutineContext.ensureActive()
        if (name == "app_list") return PhoneAccessibilityService.listApps(service)
        check(service.available(target)) { if (target.virtual) "虚拟屏连接已断开" else "请亮屏并解锁手机" }
        if (screen.displayId != target.displayId) { screen.reset(); screen.displayId = target.displayId }
        screen.scan()
        if (name !in setOf("app_launch", "app_send_intent", "phone_open_link")) target.observePackage(screen.latest.optString("package"))
        if (name == "screen_read") return withTimeout(30000) { service.read(screen, target, params.optBoolean("ocr"), params.optBoolean("image")) }
        if (name == "screenshot") return withTimeout(30000) { service.read(screen, target, false, true) }
        return actions.execute(name, params, report)
    }
}
