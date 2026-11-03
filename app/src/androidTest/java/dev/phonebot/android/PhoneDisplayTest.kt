package dev.phonebot.android

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import dev.phonebot.android.phone.PhoneShell
import dev.phonebot.android.phone.PhoneDisplay

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class PhoneDisplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private val images = mutableListOf<File>()

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    private fun call(tool: String, params: JSONObject = JSONObject()): JSONObject {
        val latch = CountDownLatch(1)
        var code = -1
        var result = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                code = resultCode; result = resultData; latch.countDown()
            }
        }
        val parcel = Parcel.obtain()
        receiver.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val reply = ResultReceiver.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        context.sendBroadcast(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestReceiver"))
            .putExtra("reply", reply).putExtra("tool", tool).putExtra("params", params.toString()))
        assertTrue("$tool timed out", latch.await(50, TimeUnit.SECONDS))
        assertEquals("$tool: ${result.getString("error")}", 0, code)
        return JSONObject(result.getString("result")!!)
    }

    private fun target(helper: String, mode: String, session: String = "test-session"): JSONObject =
        call("test_target", JSONObject().put("helper", helper).put("screen", mode).put("session", session))

    private fun ready(helper: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (true) {
            val state = call("helper_status", JSONObject().put("helper", helper))
            if (state.optString("state") == "ready") return true
            if (helper != "builtin" || !state.optBoolean("paired") || SystemClock.uptimeMillis() >= deadline) return false
            SystemClock.sleep(250)
        }
    }

    private fun withPhone(block: () -> Unit) {
        val services = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val enabled = shell("settings get secure accessibility_enabled").trim()
        val component = "${context.packageName}/.phone.PhoneAccessibilityService"
        val others = services.split(':').filter {
            it.isNotEmpty() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(component)
        }.joinToString(":")
        try {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("am start -W -f 0x14000000 -n ${context.packageName}/.MainActivity")
            shell("input keyevent 3")
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            shell("settings put secure enabled_accessibility_services ${if (others.isEmpty()) component else "$others:$component"}")
            shell("settings put secure accessibility_enabled 1")
            SystemClock.sleep(1500)
            block()
        } finally {
            shell("input keyevent 224")
            runCatching { call("test_release") }
            shell("input keyevent 3")
            images.forEach { it.delete() }
            shell(if (services.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $services")
            shell(if (enabled == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $enabled")
        }
    }

    private fun screen(displayId: Int): JSONObject = call("screen_read").also {
        assertEquals(it.toString(), displayId, it.getJSONObject("geometry").getInt("displayId"))
        assertEquals(it.toString(), context.packageName, it.getString("package"))
    }

    private fun visibleMain(target: JSONObject): Set<Int> = target.getJSONArray("tasks").objects()
        .filter { it.getInt("displayId") == 0 && it.getBoolean("visible") }.map { it.getInt("taskId") }.toSet()

    private fun image(displayId: Int) {
        val result = call("screen_read", JSONObject().put("image", true).put("ocr", true))
        assertEquals(result.toString(), displayId, result.getJSONObject("geometry").getInt("displayId"))
        val file = File(result.getString("image")).also(images::add)
        val info = result.getJSONObject("imageInfo")
        assertEquals("image/jpeg", info.getString("mimeType"))
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertTrue(maxOf(bitmap.width, bitmap.height) <= 1280)
            assertEquals(info.getInt("width"), bitmap.width)
            assertEquals(result.getInt("width").toDouble(), bitmap.width * info.getDouble("screenPerImageX"), 0.01)
        } finally { bitmap.recycle() }
        val ocr = result.getJSONArray("ocr").objects().joinToString { it.getString("text") }
        assertTrue(ocr, ocr.contains("English 123"))
        assertTrue(ocr, ocr.contains("识别") || ocr.contains("屏幕"))
    }

    private fun exercise(helper: String, mode: String) = withPhone {
        assumeTrue("$helper is not connected", ready(helper))
        val initial = target(helper, mode)
        assertTrue(initial.toString(), initial.getJSONArray("identity").getInt(0) in setOf(0, 2000))
        val displayId = initial.getInt("displayId")
        if (mode == "main") assertEquals(0, displayId) else assertTrue(displayId > 0)
        val mainTasks = visibleMain(initial)
        call("test_launch")
        val initialScreen = screen(displayId)
        val input = initialScreen.getJSONArray("elements").objects().single { it.optBoolean("editable") }.getString("id")
        val typed = call("input_text", JSONObject().put("element", input).put("text", "独立屏幕中文输入"))
        assertTrue(typed.toString(), typed.getBoolean("inputVerified"))
        assertTrue(screen(displayId).getJSONArray("elements").objects().any { it.optString("text") == "独立屏幕中文输入" })
        val clicked = call("tap", JSONObject().put("text", "确认点击").put("expect", JSONObject().put("text", "点击成功")))
        assertTrue(clicked.toString(), clicked.getBoolean("conditionMet"))
        val current = screen(displayId)
        val button = current.getJSONArray("elements").objects().single { it.optString("text") == "底部按钮" }.getJSONArray("bounds")
        call("tap", JSONObject().put("x", (button.getInt(0) + button.getInt(2)) / 2)
            .put("y", (button.getInt(1) + button.getInt(3)) / 2))
        assertTrue(screen(displayId).getJSONArray("elements").objects().any { it.optString("text") == "底部点击成功" })
        call("swipe", JSONObject().put("direction", "up"))
        image(displayId)
        if (mode == "virtual") assertEquals("Virtual input changed the main task", mainTasks, visibleMain(target(helper, mode)))
        call("back")
        if (mode == "virtual") {
            val next = target(helper, mode, "test-session-next")
            assertNotEquals(displayId, next.getInt("displayId"))
            val manager = context.getSystemService(DisplayManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 3000
            while (manager.getDisplay(displayId) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertNull("Previous session display remains allocated", manager.getDisplay(displayId))
            call("test_launch")
            screen(next.getInt("displayId"))
        }
    }

    @Test fun shizukuMain() = exercise("shizuku", "main")
    @Test fun shizukuVirtual() = exercise("shizuku", "virtual")
    @Test fun builtinMain() = exercise("builtin", "main")
    @Test fun builtinVirtual() = exercise("builtin", "virtual")

    @Test fun conditionsAndInputEvidenceUseTheSelectedDisplay() = withPhone {
        assumeTrue("Built-in helper is not connected", ready("builtin"))
        for (mode in listOf("main", "virtual")) {
            target("builtin", mode)
            call("test_launch", JSONObject().put("conditionFixture", true))
            val expect = JSONObject().put("textContains", "第2张").put("resourceId", "android:id/text1").put("withinResourceId", "android:id/list")
            val changed = call("tap", JSONObject().put("text", "下一张").put("expect", expect))
            assertTrue(changed.toString(), changed.getBoolean("conditionMet"))
            assertTrue(changed.toString(), changed.getLong("waitMs") < 2000)
            for (condition in listOf(JSONObject().put("text", "第2张"),
                JSONObject().put("textContains", "第2张").put("resourceId", "android:id/edit"),
                JSONObject().put("textContains", "第2张").put("selected", true))) {
                val result = call("tap", JSONObject().put("text", "下一张").put("expect", condition).put("timeoutMs", 250))
                assertFalse(result.toString(), result.getBoolean("conditionMet"))
            }
            val exact = call("tap", JSONObject().put("text", "下一张").put("expect", JSONObject().put("text", "图片,第2张,共3张")))
            assertTrue(exact.toString(), exact.getBoolean("conditionMet"))
            for ((inputMode, expectedValue) in listOf("" to true, "decorated" to null, "retained" to null, "spacing" to null,
                "truncated" to false, "hint" to false, "replaced" to null)) {
                call("test_launch", JSONObject().put("inputMode", inputMode))
                instrumentation.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .single { it.componentName.className == "dev.phonebot.android.testing.PhoneTestActivity" }
                    assertEquals("$mode fixture", inputMode, activity.intent.getStringExtra("inputMode"))
                    assertEquals("$mode fixture input", "", activity.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    if (inputMode == "spacing") activity.findViewById<android.widget.EditText>(android.R.id.edit).doAfterTextChanged {
                        if (it?.contains(' ') == true) it.replace(0, it.length, it.toString().replace(" ", ""))
                    }
                }
                val requested = "粤语 歌单"
                val result = call("do_steps", JSONObject().put("steps", org.json.JSONArray()
                    .put(JSONObject().put("tool", "input_text").put("args", JSONObject().put("resourceId", "android:id/edit").put("text", requested)))
                    .put(JSONObject().put("tool", "tap").put("args", JSONObject().put("text", "确认点击")))))
                val input = result.getJSONArray("steps").getJSONObject(0)
                assertTrue(input.toString(), input.getBoolean("executed"))
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals("$mode/$inputMode: $input", expectedValue, input.opt("inputVerified").takeUnless { it == JSONObject.NULL })
                assertEquals(result.toString(), if (expectedValue == true) 0 else 1, result.getInt("remaining"))
                if (inputMode == "decorated") assertEquals("搜索, $requested", input.getString("actualText"))
                if (inputMode == "retained") assertEquals("旧内容$requested", input.getString("actualText"))
                if (inputMode == "spacing") assertEquals("粤语歌单", input.getString("actualText"))
                if (inputMode == "hint") {
                    assertEquals("", input.getString("actualText"))
                    assertTrue(input.getBoolean("showingHintText"))
                }
                if (expectedValue != true) assertEquals(if (expectedValue == false) "input_mismatch" else "input_unknown", input.getString("stopReason"))
            }
            call("test_launch")
            val hintValue = call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "输入文字"))
            assertTrue(hintValue.toString(), hintValue.getBoolean("inputVerified"))
            assertFalse(hintValue.getBoolean("showingHintText"))
            call("home")
        }
    }

    @Test fun virtualPasteReplacesChineseText() = withPhone {
        assumeTrue("Shizuku is not connected", ready("shizuku"))
        val displayId = target("shizuku", "virtual").getInt("displayId")
        call("test_launch", JSONObject().put("pasteInput", true))
        val result = call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "替换中文内容"))
        assertTrue(result.toString(), result.getBoolean("inputVerified"))
        assertTrue(screen(displayId).getJSONArray("elements").objects().any { it.optString("text") == "替换中文内容" })
        val repeated = call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "再次替换中文内容"))
        assertTrue(repeated.toString(), repeated.getBoolean("inputVerified"))
        assertTrue(screen(displayId).getJSONArray("elements").objects().any { it.optString("text") == "再次替换中文内容" })
    }

    @Test fun virtualPreservesMainActivityAndRejectsItsApp() = withPhone {
        assumeTrue("Shizuku is not connected", ready("shizuku"))
        target("shizuku", "virtual")
        call("test_launch")
        shell("am start -W -a android.settings.SETTINGS")
        fun mainFocus() = shell("dumpsys window displays").substringAfter("Display: mDisplayId=0 ")
            .lineSequence().first { it.contains("mCurrentFocus=") }.trim()
        val before = mainFocus()
        assertTrue(before, before.contains("com.android.settings"))
        call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "主屏共存测试"))
        assertEquals(before, mainFocus())
        assertTrue(shell("dumpsys window").contains("mTopFocusedDisplayId=0"))
        val failure = assertThrows(AssertionError::class.java) {
            call("app_send_intent", JSONObject().put("action", "android.settings.SETTINGS"))
        }
        assertTrue(failure.message, failure.message.orEmpty().contains("主屏"))
        assertEquals(before, mainFocus())
    }

    @Test fun pairBuiltin() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pairing dialog required", args.containsKey("pairPort"))
        val result = call("helper_pair", JSONObject().put("port", args.getString("pairPort")!!.toInt()).put("code", args.getString("pairCode")))
        assertEquals(result.toString(), "ready", result.getString("state"))
        assertTrue(result.getBoolean("paired"))
    }

    @Test fun builtinReconnect() = withPhone {
        assumeTrue("Built-in helper is not paired", ready("builtin"))
        val initial = target("builtin", "virtual")
        val id = initial.getInt("displayId")
        val pid = initial.getJSONArray("identity").getInt(1)
        call("test_launch")
        shell("kill $pid")
        val manager = context.getSystemService(DisplayManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 5000
        while (manager.getDisplay(id) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertNull("Helper death retained its virtual display", manager.getDisplay(id))
        assertTrue("Built-in helper did not reconnect", ready("builtin"))
        val restored = target("builtin", "virtual")
        assertNotEquals(pid, restored.getJSONArray("identity").getInt(1))
        assertNotEquals(id, restored.getInt("displayId"))
        call("test_launch")
        screen(restored.getInt("displayId"))
    }

    @Test fun helperHandoffRejectsAppCaller() {
        assertThrows(IllegalArgumentException::class.java) {
            context.contentResolver.call(android.net.Uri.parse("content://${context.packageName}.helper"), "attach", null, Bundle())
        }
    }

    @Test fun cancelledCreationReleasesDisplays() = runBlocking {
        val helper = PhoneShell(context)
        assumeTrue("Shizuku is not connected", helper.ready())
        val manager = context.getSystemService(DisplayManager::class.java)
        fun ids() = manager.displays.filter { it.name == "PhoneBot" }.map { it.displayId }.toSet()
        val before = ids()
        val display = PhoneDisplay(context, helper)
        try {
            helper.service()
            display.select("cancel-test", "shizuku", "virtual")
            for (wait in listOf(0L, 1L, 5L, 15L, 30L)) {
                val job = launch { display.prepare() }
                delay(wait)
                job.cancelAndJoin()
                display.close()
            }
            val deadline = SystemClock.uptimeMillis() + 3000
            while (ids() != before && SystemClock.uptimeMillis() < deadline) delay(50)
            assertEquals("Cancelled creation retained a display", before, ids())
        } finally { display.close(); helper.close() }
    }

    @Test fun screenOffVirtual() = withPhone {
        val helper = listOf("shizuku", "builtin").firstOrNull(::ready)
        assumeTrue("No connected helper", helper != null)
        val displayId = target(checkNotNull(helper), "virtual").getInt("displayId")
        call("test_launch")
        screen(displayId)
        val power = context.getSystemService(PowerManager::class.java)
        val networkUrl = InstrumentationRegistry.getArguments().getString("networkUrl")
        fun networkStatus(url: String): Int {
            require(java.net.URI(url).scheme == "https")
            val connection = java.net.URI(url).toURL().openConnection() as javax.net.ssl.HttpsURLConnection
            return try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.responseCode
            } finally { connection.disconnect() }
        }
        val networkBefore = networkUrl?.let(::networkStatus)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBot:test").apply { acquire(60000) }
        try {
            shell("input keyevent 223")
            val deadline = SystemClock.uptimeMillis() + 3000
            while (power.isInteractive && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertFalse("Physical screen remained interactive", power.isInteractive)
            val typed = call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "熄屏中文测试"))
            assertTrue(typed.toString(), typed.getBoolean("inputVerified"))
            val clicked = call("tap", JSONObject().put("text", "确认点击").put("expect", JSONObject().put("text", "点击成功")))
            assertTrue(clicked.toString(), clicked.getBoolean("conditionMet"))
            val current = screen(displayId)
            assertTrue(current.getJSONArray("elements").objects().any { it.optString("text") == "熄屏中文测试" })
            val button = current.getJSONArray("elements").objects().single { it.optString("text") == "底部按钮" }.getJSONArray("bounds")
            call("tap", JSONObject().put("x", (button.getInt(0) + button.getInt(2)) / 2).put("y", (button.getInt(1) + button.getInt(3)) / 2))
            val afterTap = screen(displayId)
            assertTrue(afterTap.toString(), afterTap.getJSONArray("elements").objects().any { it.optString("text") == "底部点击成功" })
            call("swipe", JSONObject().put("direction", "up"))
            image(displayId)
            if (networkBefore != null) assertEquals("Screen-off HTTPS status changed", networkBefore, networkStatus(checkNotNull(networkUrl)))
            assertFalse("Virtual operation woke the physical screen", power.isInteractive)
        } finally { if (wake.isHeld) wake.release(); shell("input keyevent 224"); shell("wm dismiss-keyguard") }
    }

    private fun overlayNode(label: String): AccessibilityNodeInfo? {
        automation.clearCache()
        return automation.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        .mapNotNull { it.root }.firstNotNullOfOrNull { root ->
            fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
                return null
            }
            find(root)
        }
    }

    private fun overlay(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            overlayNode(label)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing overlay control: $label")
    }

    private fun clickOverlay(label: String) {
        val bounds = Rect().also { overlay(label).getBoundsInScreen(it) }
        shell("input -d 0 tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun captureOverlay(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun floatingThemeFollowsClientAndReconnect() = withPhone {
        val original = Appearance.from(context.getSharedPreferences("phonebot", 0).getString("appearance", null))
        val client = dev.phonebot.android.engine.EngineClient(context)
        fun await(mode: Appearance) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (call("test_overlay").getString("appearance") != mode.name && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertEquals(mode.name, call("test_overlay").getString("appearance"))
        }
        try {
            instrumentation.runOnMainSync { client.attach() }
            await(original)
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK)) {
                instrumentation.runOnMainSync { client.appearance(mode) }
                await(mode)
            }
            instrumentation.runOnMainSync { client.detach(); client.attach() }
            await(Appearance.DARK)
        } finally { instrumentation.runOnMainSync { client.appearance(original); client.detach() } }
    }

    @Test fun floatingVirtualPreview() = withPhone {
        val helper = listOf("builtin", "shizuku").firstOrNull(::ready)
        assumeTrue("No connected helper", helper != null)
        val displayId = target(checkNotNull(helper), "virtual").getInt("displayId")
        call("test_launch")
        val focus = shell("dumpsys window displays").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }
        assertNotNull("Main focus was not reported", focus)
        val preview = call("test_preview")
        assertTrue(preview.toString(), preview.getInt("width") <= 360)
        val original = screen(displayId)
        assertEquals(original.getInt("height").toDouble() / original.getInt("width"), preview.getInt("height").toDouble() / preview.getInt("width"), 0.01)
        call("test_overlay", JSONObject().put("show", true).put("avatarColor", 0xFF8B5CF6L))
        val bubble = Rect().also { overlay("查看 Agent 屏幕").getBoundsInScreen(it) }
        shell("input -d 0 swipe ${bubble.centerX()} ${bubble.centerY()} ${bubble.centerX() + 200} ${bubble.centerY() + 300} 500")
        val moved = Rect()
        val dragDeadline = SystemClock.uptimeMillis() + 3000
        do { overlay("查看 Agent 屏幕").getBoundsInScreen(moved); if (moved.top != bubble.top) break; SystemClock.sleep(100) }
        while (SystemClock.uptimeMillis() < dragDeadline)
        assertNotEquals("Floating control did not move", bubble.top, moved.top)
        clickOverlay("查看 Agent 屏幕")
        val frame = Rect().also { overlay("收起 Agent 屏幕").getBoundsInScreen(it) }
        assertEquals("Preview aspect ratio changed", preview.getInt("width").toDouble() / preview.getInt("height"), frame.width().toDouble() / frame.height(), 0.01)
        val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val card = Rect().also { overlay("收起 Agent 屏幕").window.getBoundsInScreen(it) }
        assertTrue("Preview covers system bars: $card / ${metrics.bounds} / $insets", card.top >= insets.top && card.bottom <= metrics.bounds.bottom - insets.bottom)
        clickOverlay("放大 Agent 屏幕")
        overlay("缩小 Agent 屏幕")
        val full = Rect().also { overlay("收起 Agent 屏幕").getBoundsInScreen(it) }
        assertEquals("Small preview width", full.width().toDouble() / 2, frame.width().toDouble(), 2.0)
        assertEquals("Small preview height", full.height().toDouble() / 2, frame.height().toDouble(), 2.0)
        val fullWindow = Rect()
        val resizeDeadline = SystemClock.uptimeMillis() + 3000
        do {
            overlay("收起 Agent 屏幕").window.getBoundsInScreen(fullWindow)
            if (fullWindow.width() > card.width() && fullWindow.height() > card.height()) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < resizeDeadline)
        assertTrue("Preview window kept its small bounds", fullWindow.width() > card.width() && fullWindow.height() > card.height())
        captureOverlay("overlay-virtual-large.jpg")
        shell("dumpsys gfxinfo ${context.packageName}:engine reset")
        SystemClock.sleep(3000)
        val timing = shell("dumpsys gfxinfo ${context.packageName}:engine").lineSequence()
            .filter { it.contains("frames rendered:") || it.contains("Janky frames:") || it.contains("percentile:") }.joinToString("\n")
        File(context.cacheDir, "overlay-frame-timing.txt").writeText(timing)
        instrumentation.sendStatus(2, Bundle().apply { putString("previewFrameTiming", timing) })
        clickOverlay("缩小 Agent 屏幕")
        overlay("放大 Agent 屏幕")
        captureOverlay("overlay-virtual.jpg")
        assertEquals("Preview stole main focus", focus, shell("dumpsys window displays").lineSequence().firstOrNull { it.contains("mCurrentFocus=") })
        call("input_text", JSONObject().put("resourceId", "android:id/edit").put("text", "悬浮预览中文测试"))
        captureOverlay("overlay-virtual-input.jpg")
        image(displayId)
        clickOverlay("收起 Agent 屏幕")
        overlay("查看 Agent 屏幕")
        assertEquals(displayId, call("test_overlay").getInt("displayId"))
        assertTrue(screen(displayId).getJSONArray("elements").objects().any { it.optString("text") == "悬浮预览中文测试" })
        call("test_overlay", JSONObject().put("close", true))
        assertNull(overlayNode("查看 Agent 屏幕"))
        assertNull(overlayNode("收起 Agent 屏幕"))
    }

    @Test fun floatingMainBubble() = withPhone {
        val helper = listOf("builtin", "shizuku").firstOrNull(::ready)
        assumeTrue("No connected helper", helper != null)
        target(checkNotNull(helper), "main")
        call("test_launch")
        call("test_overlay", JSONObject().put("show", true))
        overlay("返回当前会话")
        captureOverlay("overlay-main.jpg")
        val observed = screen(0)
        assertFalse(observed.toString(), observed.getJSONArray("elements").objects().any { it.optString("text") == "检查执行屏幕" })
        val screenshot = call("screen_read", JSONObject().put("image", true).put("ocr", true))
        images += File(screenshot.getString("image"))
        assertFalse(screenshot.toString(), screenshot.getJSONArray("ocr").objects().any { it.optString("text").contains("读取页面") })
        val button = screen(0).getJSONArray("elements").objects().single { it.optString("text") == "确认点击" }.getJSONArray("bounds")
        val tapped = call("tap", JSONObject().put("x", (button.getInt(0) + button.getInt(2)) / 2)
            .put("y", (button.getInt(1) + button.getInt(3)) / 2).put("expect", JSONObject().put("text", "点击成功")))
        assertTrue(tapped.toString(), tapped.getBoolean("conditionMet"))
        call("test_overlay", JSONObject().put("close", true))
        assertNull(overlayNode("返回当前会话"))
    }
}
