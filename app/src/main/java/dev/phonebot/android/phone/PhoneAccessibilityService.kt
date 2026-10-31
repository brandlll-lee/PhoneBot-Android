package dev.phonebot.android.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

class PhoneAccessibilityService : AccessibilityService() {
    companion object { var instance: PhoneAccessibilityService? = null; private set }
    private val chineseHolder = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val latinHolder = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val screen = ScreenState(this)
    private val actions = PhoneActions(this, screen)
    internal var lastScreenEvent = 0L; private set

    override fun onServiceConnected() {
        instance = this
        // The SDK content provider runs in the main process. OCR runs in :engine.
        MlKit.initialize(this)
        if (Build.VERSION.SDK_INT >= 33) serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastScreenEvent = SystemClock.uptimeMillis() }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        instance = null
        if (chineseHolder.isInitialized()) chineseHolder.value.close()
        if (latinHolder.isInitialized()) latinHolder.value.close()
        super.onDestroy()
    }

    fun beginTask() = screen.reset()
    fun available(): Boolean = !getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(PowerManager::class.java).isInteractive

    internal suspend fun gesture(path: Path, duration: Long) = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手势被系统取消")) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统拒绝执行手势"))
    }

    private suspend fun capture(): Bitmap {
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

    private suspend fun read(ocr: Boolean, image: Boolean): JSONObject {
        screen.scan()
        val result = screen.present(true)
        if (!ocr && !image) return result
        val geometry = screen.geometry
        val bitmap = capture()
        try {
            screen.scan()
            check(screen.geometry.rotation == geometry.rotation && screen.geometry.width == geometry.width && screen.geometry.height == geometry.height) { "屏幕方向已变化，请重新读屏" }
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
        } finally { bitmap.recycle() }
        return result
    }

    suspend fun execute(name: String, params: JSONObject, report: (JSONObject) -> Unit = {}): JSONObject {
        coroutineContext.ensureActive()
        check(available()) { "请亮屏并解锁手机" }
        if (name == "screen_read") return read(params.optBoolean("ocr"), params.optBoolean("image"))
        if (name == "screenshot") return read(false, true)
        if (name == "app_list") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
            return JSONObject().put("apps", JSONArray(apps.take(300).map { JSONObject().put("package", it.activityInfo.packageName).put("name", it.loadLabel(packageManager).toString().take(120)) })).put("truncated", apps.size > 300)
        }
        return actions.execute(name, params, report)
    }
}
