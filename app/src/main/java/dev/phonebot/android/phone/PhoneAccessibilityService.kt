package dev.phonebot.android.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.TextAttribute
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
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
    private val chinese by chineseHolder
    private val latin by latinHolder
    private var nodes = listOf<AccessibilityNodeInfo>()
    private var page = ""

    override fun onServiceConnected() {
        instance = this
        // The SDK content provider runs in the main process. OCR runs in :engine.
        MlKit.initialize(this)
        if (Build.VERSION.SDK_INT >= 33) serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        instance = null
        if (chineseHolder.isInitialized()) chinese.close()
        if (latinHolder.isInitialized()) latin.close()
        super.onDestroy()
    }

    fun available(): Boolean = !getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(PowerManager::class.java).isInteractive

    private fun tree(): JSONObject {
        val root = rootInActiveWindow ?: error("当前页面不可读取。请切换页面后重试。")
        val collected = mutableListOf<AccessibilityNodeInfo>()
        var scanned = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (++scanned > 1500) return
            if (node.isVisibleToUser && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true || node.isClickable || node.isEditable || node.isScrollable)) collected += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        nodes = collected.take(80)
        val elements = nodes.map { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            JSONObject().put("text", if (node.isPassword) "[受保护输入]" else node.text?.toString()?.take(120).orEmpty())
                .put("description", if (node.isPassword) "" else node.contentDescription?.toString()?.take(120).orEmpty())
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
                .put("focused", node.isFocused).put("selected", node.isSelected).put("class", node.className?.toString())
        }
        val signature = "${root.windowId}:${root.packageName}:${elements.joinToString()}"
        page = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        elements.forEachIndexed { index, element -> element.put("id", "$page:$index") }
        val metrics = resources.displayMetrics
        return JSONObject().put("page", page).put("package", root.packageName).put("width", metrics.widthPixels).put("height", metrics.heightPixels)
            .put("elements", JSONArray(elements)).put("truncated", collected.size > nodes.size || scanned > 1500)
    }

    private fun element(id: String): AccessibilityNodeInfo {
        val parts = id.split(':')
        require(parts.size == 2 && parts[0] == page) { "元素编号已过期。请重新读取页面。" }
        return nodes.getOrNull(parts[1].toIntOrNull() ?: -1) ?: error("元素不存在")
    }
    private fun coordinate(params: JSONObject, key: String, limit: Int): Float {
        val value = params.get(key)
        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 0 until limit) { "$key 坐标超出屏幕" }
        return value.toInt().toFloat()
    }
    private suspend fun gesture(path: Path, duration: Long) = suspendCancellableCoroutine { continuation ->
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
        val result = tree()
        if (!ocr && !image) return result
        val bitmap = capture()
        try {
            if (ocr) {
                val additions = JSONArray()
                val existing = nodes.filter { !it.isPassword }.mapNotNull { it.text?.toString() }.toMutableSet()
                for (recognizer in listOf(chinese, latin)) {
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
                        if (text.isEmpty() || existing.any { it.contains(text) || text == it }) continue
                        existing += text
                        val bounds = line.boundingBox ?: continue
                        additions.put(JSONObject().put("text", text.take(120)).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                    }
                }
                result.put("ocr", additions)
            }
            if (image) {
                coroutineContext.ensureActive()
                val directory = File(cacheDir, "screens").apply { mkdirs() }
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
                val file = File(directory, "${System.nanoTime()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                result.put("image", file.path)
            }
        } finally { bitmap.recycle() }
        return result
    }

    suspend fun execute(name: String, params: JSONObject): JSONObject {
        coroutineContext.ensureActive()
        check(available()) { "请亮屏并解锁手机" }
        if (name == "screen_read") return read(params.optBoolean("ocr"), params.optBoolean("image"))
        if (name == "screenshot") return read(false, true)
        if (name == "app_list") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
            return JSONObject().put("apps", JSONArray(apps.take(300).map { JSONObject().put("package", it.activityInfo.packageName).put("name", it.loadLabel(packageManager).toString().take(120)) })).put("truncated", apps.size > 300)
        }
        val before = tree()
        var launchedPackage: String? = null
        coroutineContext.ensureActive()
        when (name) {
            "app_launch" -> {
                val target = params.getString("package")
                require(target.matches(Regex("[a-zA-Z0-9_.]+"))) { "应用包名无效" }
                val intent = packageManager.getLaunchIntentForPackage(target) ?: error("找不到可启动的应用")
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                launchedPackage = target
            }
            "tap" -> {
                val node = when {
                    params.has("element") -> element(params.getString("element"))
                    params.has("text") -> nodes.filter { !it.isPassword && (it.text?.toString() == params.getString("text") || it.contentDescription?.toString() == params.getString("text")) }
                        .let { require(it.size == 1) { "文字匹配结果不唯一。请使用元素编号。" }; it.single() }
                    else -> null
                }
                if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
                    val x = bounds?.exactCenterX() ?: coordinate(params, "x", resources.displayMetrics.widthPixels)
                    val y = bounds?.exactCenterY() ?: coordinate(params, "y", resources.displayMetrics.heightPixels)
                    gesture(Path().apply { moveTo(x, y) }, 80)
                }
            }
            "swipe" -> {
                val width = resources.displayMetrics.widthPixels; val height = resources.displayMetrics.heightPixels
                val duration = params.optLong("duration", 400)
                require(duration in 100..2000) { "滑动时间无效" }
                gesture(Path().apply { moveTo(coordinate(params, "x", width), coordinate(params, "y", height)); lineTo(coordinate(params, "endX", width), coordinate(params, "endY", height)) }, duration)
            }
            "input_text" -> {
                val node = if (params.has("element")) element(params.getString("element")) else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                check(node != null && node.isEditable && !node.isPassword) { "请选择可编辑的普通输入框" }
                val text = params.getString("text")
                require(text.length in 1..4000) { "输入长度无效" }
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                if (!accepted && Build.VERSION.SDK_INT >= 33) {
                    check(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "无法聚焦输入框" }
                    delay(100)
                    val connection = inputMethod?.currentInputConnection ?: error("此输入框不支持文字输入")
                    connection.setSelection(0, node.text?.length ?: 0)
                    connection.commitText(text, 1, TextAttribute.Builder().build())
                } else check(accepted) { "输入框拒绝写入文字" }
                delay(180)
                check(node.refresh() && node.text?.toString() == text) { "输入回读不一致。请检查页面。" }
            }
            "back" -> check(performGlobalAction(GLOBAL_ACTION_BACK)) { "系统拒绝返回操作" }
            "home" -> check(performGlobalAction(GLOBAL_ACTION_HOME)) { "系统拒绝主页操作" }
            else -> error("工具不存在")
        }
        delay(400)
        if (launchedPackage != null) {
            var attempts = 0
            while (rootInActiveWindow?.packageName?.toString() != launchedPackage && attempts++ < 40) delay(100)
            check(rootInActiveWindow?.packageName?.toString() == launchedPackage) { "应用启动后尚未进入目标页面。请重新读屏。" }
        }
        coroutineContext.ensureActive()
        return read(false, false).put("changed", before.getString("page") != page).put("inputVerified", name == "input_text")
    }
}
