package dev.phonebot.android.phone

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class SystemShellService(private val allowedUid: Int? = null) : ISystemShell.Stub() {
    private val processes = java.util.concurrent.ConcurrentHashMap<String, java.lang.Process>()
    private val generation = AtomicLong()
    private class Screen(val display: VirtualDisplay, val wake: PowerManager.WakeLock)
    private val displays = mutableMapOf<Int, Screen>()
    private val context by lazy { ShellContext.create() }
    private val manager by lazy { DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context) }
    private val taskManager by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }
    private val power by lazy {
        val registry = Class.forName("android.os.ServiceManager")
        fun service(name: String, type: String) = Class.forName("android.os.$type\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, registry.getMethod("getService", String::class.java).invoke(null, name))
        PowerManager::class.java.getConstructor(Context::class.java, Class.forName("android.os.IPowerManager"),
            Class.forName("android.os.IThermalService"), Handler::class.java).newInstance(context,
            service("power", "IPowerManager"), service("thermalservice", "IThermalService"), Handler(Looper.getMainLooper()))
    }

    private fun authorize() { check(allowedUid == null || Binder.getCallingUid() == allowedUid || Binder.getCallingPid() == Process.myPid()) { "助手调用方无效" } }
    private fun owned(id: Int) { authorize(); check(synchronized(displays) { id in displays }) { "虚拟屏已释放" } }
    override fun identity(): IntArray { authorize(); return intArrayOf(Process.myUid(), Process.myPid()) }

    @SuppressLint("WrongConstant")
    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int, status: ResultReceiver): Int {
        authorize()
        require(android.os.Build.VERSION.SDK_INT >= 34 && width in 1..4096 && height in 1..4096 && dpi in 72..1000)
        // Trusted displays need their own focus and IME to keep input on the agent screen.
        val flags = 1 or 8 or 64 or 128 or 256 or 1024 or 2048 or 4096 or 8192 or 16384 or 65536
        val display = manager.createVirtualDisplay("PhoneBot", width, height, dpi, surface, flags, object : VirtualDisplay.Callback() {
            override fun onPaused() = status.send(Display.STATE_OFF, null)
            override fun onResumed() = status.send(Display.STATE_ON, null)
            override fun onStopped() = status.send(Display.STATE_UNKNOWN, null)
        }, Handler(Looper.getMainLooper()))
        val id = display.display.displayId
        try {
            // The platform's display-bound lock wakes only the target display group.
            @Suppress("DEPRECATION")
            val wake = PowerManager::class.java.getMethod("newWakeLock", Int::class.java, String::class.java, Int::class.java)
                .invoke(power, PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "PhoneBot:display", id) as PowerManager.WakeLock
            wake.setReferenceCounted(false)
            synchronized(displays) { displays[id] = Screen(display, wake) }
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, id, 0)
        } catch (error: Exception) { releaseDisplay(id); display.release(); throw error }
        return id
    }

    @SuppressLint("Wakelock") // Task completion releases the lock through this Binder method.
    override fun setDisplayActive(displayId: Int, active: Boolean) {
        authorize()
        synchronized(displays) {
            val screen = checkNotNull(displays[displayId]) { "虚拟屏已释放" }
            if (active) screen.wake.acquire(30 * 60 * 1000L)
            else if (screen.wake.isHeld) screen.wake.release()
        }
    }

    override fun releaseDisplay(displayId: Int) {
        authorize()
        synchronized(displays) { displays.remove(displayId) }?.let {
            try { if (it.wake.isHeld) it.wake.release() } finally { it.display.release() }
        }
    }

    private fun taskRows(): List<ActivityManager.RunningTaskInfo> {
        val method = taskManager.javaClass.methods.first { it.name == "getTasks" && it.parameterTypes.size == 4 }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(taskManager, 100, false, false, Display.INVALID_DISPLAY) as List<ActivityManager.RunningTaskInfo>
    }

    override fun tasks(): String {
        authorize()
        if (android.os.Build.VERSION.SDK_INT < 34) error("虚拟屏需要 Android 14 或更高版本")
        val type = Class.forName("android.app.TaskInfo")
        return JSONArray(taskRows().map { task -> JSONObject().put("taskId", task.taskId)
            .put("displayId", type.getField("displayId").getInt(task)).put("package", task.topActivity?.packageName.orEmpty())
            .put("visible", type.getField("isVisible").getBoolean(task)) }).toString()
    }

    override fun launch(displayId: Int, intent: Intent): Boolean {
        owned(displayId)
        val target = intent.component?.packageName ?: intent.`package` ?: intent.resolveActivity(context.packageManager)?.packageName
        require(!target.isNullOrBlank()) { "找不到可处理此请求的应用" }
        check(JSONArray(tasks()).let { rows -> (0 until rows.length()).none { i -> rows.getJSONObject(i).let {
            it.getInt("displayId") == 0 && it.getBoolean("visible") && it.getString("package") == target
        } } }) { "此应用正在主屏使用，请先离开该应用" }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
        val result = start.invoke(taskManager, null, "com.android.shell", null, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), intent.type,
            null, null, 0, 0, null, options, allowedUid?.div(100000) ?: 0) as Int
        return result >= 0
    }

    private fun inject(id: Int, event: InputEvent): Boolean {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, id)
        val input = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        return input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java).invoke(input, event, 2) as Boolean
    }

    override fun touch(displayId: Int, x: Float, y: Float, endX: Float, endY: Float, duration: Long): Boolean {
        owned(displayId)
        val size = android.graphics.Point().also { synchronized(displays) { displays.getValue(displayId) }.display.display.getRealSize(it) }
        require(listOf(x, endX).all { it.isFinite() && it >= 0 && it < size.x } && listOf(y, endY).all { it.isFinite() && it >= 0 && it < size.y } && duration in 1..2000)
        val token = generation.get()
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, progress: Float): Boolean {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + (endX - x) * progress, y + (endY - y) * progress, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { inject(displayId, event) } finally { event.recycle() }
        }
        if (!send(MotionEvent.ACTION_DOWN, 0f)) return false
        var accepted = true
        try {
            while (SystemClock.uptimeMillis() - start < duration && generation.get() == token) {
                SystemClock.sleep(16)
                if (x != endX || y != endY) accepted = send(MotionEvent.ACTION_MOVE, ((SystemClock.uptimeMillis() - start).toFloat() / duration).coerceAtMost(1f)) && accepted
            }
        } finally { accepted = send(if (generation.get() == token) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, 1f) && accepted }
        return accepted && generation.get() == token
    }

    override fun key(displayId: Int, keyCode: Int): Boolean {
        owned(displayId)
        require(keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_PASTE))
        val now = SystemClock.uptimeMillis()
        val down = inject(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        return inject(displayId, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)) && down
    }

    override fun paste(displayId: Int, text: String): Boolean {
        owned(displayId)
        require(text.length in 1..4000)
        val clipboard = ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper()))
        val previous = clipboard.primaryClip
        val clip = ClipData.newPlainText("PhoneBot", text)
        clipboard.setPrimaryClip(clip)
        return try { key(displayId, KeyEvent.KEYCODE_PASTE).also { SystemClock.sleep(120) } }
        finally {
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (previous == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)
            }
        }
    }

    override fun execute(requestId: String, command: String): String {
        authorize()
        require(requestId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(command.isNotBlank() && command.length <= 4000 && '\u0000' !in command)
        val child = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        check(processes.putIfAbsent(requestId, child) == null) { "命令编号重复" }
        val output = StringBuilder()
        var truncated = false
        var outputError: IOException? = null
        val reader = Thread({
            try { child.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val keep = minOf(count, 32768 - output.length)
                        output.append(buffer, 0, keep)
                        if (keep < count) truncated = true
                    }
                }
            } } catch (error: IOException) { synchronized(output) { outputError = error } }
        }, "shell-output").apply { start() }
        try {
            val ended = child.waitFor(10, TimeUnit.SECONDS)
            if (!ended) child.destroyForcibly()
            reader.join(1000)
            return synchronized(output) { JSONObject().put("ok", ended && child.exitValue() == 0 && outputError == null)
                .put("exitCode", if (ended) child.exitValue() else -1).put("timedOut", !ended).put("uid", Process.myUid())
                .put("output", output.toString()).put("outputError", outputError?.message).put("truncated", truncated).toString() }
        } finally { child.destroy(); processes.remove(requestId, child) }
    }
    override fun cancel(requestId: String) { authorize(); processes[requestId]?.destroyForcibly() }
    override fun destroy() { authorize(); generation.incrementAndGet(); processes.values.forEach { it.destroyForcibly() }; synchronized(displays) { displays.keys.toList().forEach(::releaseDisplay) }; exitProcess(0) }
}
