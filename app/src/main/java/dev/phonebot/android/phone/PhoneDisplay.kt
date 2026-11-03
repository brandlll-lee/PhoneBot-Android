package dev.phonebot.android.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Bundle
import android.os.ResultReceiver
import android.view.Display
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class PhoneDisplay(private val context: Context, private val shell: PhoneShell,
    private val resources: PhoneResources = PhoneResources(), private val owner: String = java.util.UUID.randomUUID().toString()) {
    var session = ""; private set
    var helper = "shizuku"; private set
    var mode = "main"; private set
    var pauseReason = ""
    @Volatile private var screen: VirtualScreen? = null
    val displayId get() = if (mode == "main") Display.DEFAULT_DISPLAY else screen?.id ?: Display.INVALID_DISPLAY
    val virtual get() = mode == "virtual"

    fun select(session: String, helper: String, mode: String) {
        require(helper in setOf("builtin", "shizuku") && mode in setOf("main", "virtual")) { "手机执行配置无效" }
        if (this.session != session || this.helper != helper || this.mode != mode) close()
        this.session = session; this.helper = helper; this.mode = mode; pauseReason = ""
    }

    suspend fun prepare() {
        if (!virtual) return
        if (screen?.alive() != true) {
            close()
            check(Build.VERSION.SDK_INT >= 34) { "虚拟屏需要 Android 14 或更高版本" }
            val service = shell.service(helper)
            var created: VirtualScreen? = null
            try {
                withContext(Dispatchers.IO) { created = VirtualScreen(context, service) }
                screen = created
            } catch (error: Exception) { created?.close(); throw error }
        }
        val current = checkNotNull(screen)
        try {
            withContext(Dispatchers.IO) { current.service.setDisplayActive(current.id, true) }
            check(withTimeoutOrNull(5000) { current.awaitReady(); true } == true) { "独立屏尚未恢复，请重试" }
        } catch (error: Exception) { current.rest(); throw error }
    }

    fun alive(): Boolean = !virtual || screen?.alive() == true
    fun ready(): Boolean = !virtual || screen?.ready() == true
    fun rest() { screen?.rest() }
    suspend fun tasks(): List<JSONObject> = if (!virtual) emptyList() else withContext(Dispatchers.IO) {
        val rows = JSONArray(checkNotNull(screen).service.tasks())
        (0 until rows.length()).map(rows::getJSONObject)
    }

    suspend fun reserve(intent: Intent, owner: String = this.owner) {
        val name = intent.component?.packageName ?: intent.`package` ?: intent.resolveActivity(context.packageManager)?.packageName
        require(!name.isNullOrEmpty()) { "找不到可处理此请求的应用" }
        resources.app(owner, name)
    }

    suspend fun launch(intent: Intent, owner: String = this.owner) {
        reserve(intent, owner)
        prepare()
        if (virtual) withContext(Dispatchers.IO) { check(checkNotNull(screen).service.launch(displayId, intent)) { "系统拒绝在虚拟屏启动应用" } }
        else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun touch(x: Float, y: Float, endX: Float, endY: Float, duration: Long) = withContext(Dispatchers.IO) {
        check(checkNotNull(screen).service.touch(displayId, x, y, endX, endY, duration)) { "系统未执行虚拟屏触摸，请检查屏幕与锁屏状态" }
    }
    suspend fun key(code: Int) = withContext(Dispatchers.IO) { check(checkNotNull(screen).service.key(displayId, code)) { "系统未执行虚拟屏按键" } }
    suspend fun paste(text: String, owner: String = this.owner) {
        resources.clipboard.lock(owner)
        try { withContext(Dispatchers.IO) { check(checkNotNull(screen).service.paste(displayId, text)) { "虚拟屏输入失败" } } }
        finally { resources.clipboard.unlock(owner) }
    }
    suspend fun observePackage(name: String, owner: String = this.owner) { if (name.isNotEmpty() && name != "null") resources.app(owner, name) }
    fun release() { close(); resources.release(owner) }
    suspend fun frame(): Bitmap = checkNotNull(screen).frame()
    fun previewFrames() = flow {
        var timestamp = Long.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            screen?.preview(timestamp)?.let { (time, bitmap) -> timestamp = time; emit(bitmap) }
            delay(200)
        }
    }.flowOn(Dispatchers.Default)
    fun close() { screen?.close(); screen = null }
}

private class VirtualScreen(context: Context, val service: ISystemShell) {
    private val closed = AtomicBoolean()
    private val died = IBinder.DeathRecipient { close() }
    private val thread = HandlerThread("phone-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    @Suppress("DEPRECATION")
    private val metrics = android.util.DisplayMetrics().also(display::getRealMetrics)
    private val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
    private val lock = Any()
    private val state = MutableStateFlow(Display.STATE_UNKNOWN)
    private val received = MutableStateFlow(false)
    @Volatile private var stopped = false
    private var latest: Image? = null
    val id: Int

    init {
        reader.setOnImageAvailableListener({
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) {
                if (ready()) { latest?.close(); latest = image; received.value = true } else image.close()
            }
        }, handler)
        val status = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (closed.get()) return
                if (resultCode == Display.STATE_UNKNOWN) stopped = true
                state.value = resultCode
                if (resultCode != Display.STATE_ON) synchronized(lock) { latest?.close(); latest = null; received.value = false }
            }
        }
        try { id = service.createDisplay(reader.surface, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, status) }
        catch (error: Exception) { reader.close(); thread.quitSafely(); throw error }
        try { service.asBinder().linkToDeath(died, 0) }
        catch (error: Exception) { close(); throw error }
    }

    fun alive(): Boolean = !closed.get() && !stopped && service.asBinder().isBinderAlive
    fun ready(): Boolean = alive() && state.value == Display.STATE_ON
    suspend fun awaitReady() {
        state.first { it == Display.STATE_ON || !alive() }
        check(ready()) { "独立屏已停止，请恢复连接" }
    }
    fun rest() { runCatching { service.setDisplayActive(id, false) }.onFailure { android.util.Log.w("PhoneBot", "Display power release failed", it) } }
    suspend fun frame(): Bitmap {
        check(withTimeoutOrNull(5000) { received.first { it }; true } == true) { "独立屏尚未产生新画面，请重试" }
        return synchronized(lock) {
            check(ready()) { "独立屏已暂停，请重试" }
            copyFrame(checkNotNull(latest) { "独立屏尚未产生画面，请重试" })
        }
    }
    fun preview(timestamp: Long): Pair<Long, Bitmap>? = synchronized(lock) {
        latest?.takeIf { it.timestamp != timestamp }?.let { it.timestamp to copyFrame(it, 360) }
    }
    private fun copyFrame(image: Image, maxWidth: Int = image.width): Bitmap {
        val plane = image.planes[0]
        val padded = createBitmap(plane.rowStride / plane.pixelStride, image.height)
        try {
            padded.copyPixelsFromBuffer(plane.buffer.rewind())
            val width = minOf(image.width, maxWidth)
            val result = createBitmap(width, (image.height.toLong() * width / image.width).toInt().coerceAtLeast(1))
            Canvas(result).drawBitmap(padded, Rect(0, 0, image.width, image.height), Rect(0, 0, result.width, result.height), Paint(Paint.FILTER_BITMAP_FLAG))
            return result
        } finally { padded.recycle() }
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        state.value = Display.STATE_UNKNOWN
        runCatching { service.asBinder().unlinkToDeath(died, 0) }
        runCatching { service.releaseDisplay(id) }
        handler.post { reader.setOnImageAvailableListener(null, null); synchronized(lock) { latest?.close(); latest = null }; reader.close() }
        thread.quitSafely()
    }
}
