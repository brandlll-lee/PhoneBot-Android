package dev.phonebot.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.os.Parcel
import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.engine.CredentialVault
import dev.phonebot.android.engine.TaskJournal
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    private fun call(tool: String, params: JSONObject = JSONObject(), success: Boolean = true): JSONObject {
        val latch = CountDownLatch(1)
        var code = -1
        var output = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) { code = resultCode; output = resultData; latch.countDown() }
        }
        val parcel = Parcel.obtain()
        receiver.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val remoteReceiver = ResultReceiver.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        context.sendBroadcast(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestReceiver"))
            .putExtra("reply", remoteReceiver).putExtra("tool", tool).putExtra("params", params.toString()))
        assertTrue("Tool response timed out", latch.await(20, TimeUnit.SECONDS))
        assertEquals(output.getString("error", tool), if (success) 0 else 1, code)
        return if (success) JSONObject(output.getString("result")!!) else JSONObject().put("error", output.getString("error"))
    }
    @Test fun keystoreEncryptsPersistsUpdatesAndDeletesCredentials() {
        val vault = CredentialVault(context)
        val provider = "phonebot-test"
        try {
            vault.write(provider, JSONObject().put("type", "api_key").put("key", "private-key-中文"))
            val file = File(context.noBackupFilesDir, "credentials/$provider.bin")
            assertFalse(file.readText().contains("private-key"))
            assertEquals("private-key-中文", CredentialVault(context).read(provider)!!.getString("key"))
            val first = file.readBytes()
            vault.write(provider, JSONObject().put("type", "api_key").put("key", "private-key-中文"))
            assertFalse(first.contentEquals(file.readBytes()))
            vault.write(provider, JSONObject().put("type", "oauth").put("access", "access-token").put("refresh", "refresh-token").put("expires", 42))
            assertEquals("oauth", vault.list().objects().find { it.getString("providerId") == provider }!!.getString("type"))
        } finally { vault.delete(provider) }
        assertNull(vault.read(provider))
    }
    @Test fun accessibilityReadsActsVerifiesChineseAndRecognizesPixels() {
        val originalServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val component = "${context.packageName}/.phone.PhoneAccessibilityService"
        try {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = instrumentation.startActivitySync(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val others = originalServices.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(component) }.joinToString(":")
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            shell("settings put secure enabled_accessibility_services ${if (others.isEmpty()) component else "$others:$component"}")
            shell("settings put secure accessibility_enabled 1")
            SystemClock.sleep(2000)
            var screen = call("screen_read")
            val input = checkNotNull(screen.getJSONArray("elements").objects().firstOrNull { it.optBoolean("editable") }) { "输入框缺失：$screen" }.getString("id")
            val typed = call("input_text", JSONObject().put("element", input).put("text", "你好，PhoneBot"))
            assertTrue(typed.getBoolean("inputVerified"))
            assertEquals("diff", typed.getString("kind"))
            assertTrue(typed.getJSONArray("updated").objects().any { it.optString("text") == "你好，PhoneBot" && it.optString("id") == input })
            call("tap", JSONObject().put("element", input))
            screen = call("tap", JSONObject().put("text", "确认点击"))
            screen = call("screen_read")
            assertTrue(screen.getJSONArray("elements").objects().any { it.optString("text") == "点击成功" })
            instrumentation.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
            SystemClock.sleep(400)
            val image = call("screen_read", JSONObject().put("ocr", true).put("image", true))
            assertTrue(File(image.getString("image")).length() > 1000)
            val info = image.getJSONObject("imageInfo")
            assertEquals("image/jpeg", info.getString("mimeType"))
            assertTrue(maxOf(info.getInt("width"), info.getInt("height")) <= 1280)
            val bitmap = BitmapFactory.decodeFile(image.getString("image"))
            assertEquals(info.getInt("width"), bitmap.width)
            assertEquals(image.getInt("width").toDouble(), bitmap.width * info.getDouble("screenPerImageX"), 0.01)
            bitmap.recycle()
            val ocr = image.getJSONArray("ocr").objects().joinToString { it.getString("text") }
            assertTrue(ocr, ocr.contains("English 123") && (ocr.contains("识别") || ocr.contains("屏幕")))
            assertTrue(call("app_list").getJSONArray("apps").objects().any { it.getString("package") == context.packageName })
            val status = call("device_status")
            assertTrue(status.getJSONObject("volume").getInt("max") > 0)
            val command = call("phone_system_command", JSONObject().put("command", "id -u"))
            if (status.getString("shell") == "ready") {
                assertTrue(command.toString(), command.getBoolean("ok"))
                assertTrue(command.getInt("uid") in listOf(0, 2000))
            } else assertEquals("shizuku", command.getString("permission"))
            call("tap", JSONObject().put("x", -1).put("y", 1), false)
            call("tap", JSONObject().put("text", "重复目标"), false)
            val batch = call("do_steps", JSONObject("""{"steps":[{"tool":"input_text","args":{"element":"$input","text":"连续中文","expect":{"text":"连续中文","editable":true}}},{"tool":"tap","args":{"text":"确认点击","expect":{"text":"点击成功"}}}]}"""))
            assertTrue(batch.toString(), batch.getBoolean("ok"))
            assertEquals(2, batch.getJSONArray("steps").length())
            val delayed = call("tap", JSONObject("""{"text":"延迟加载","expect":{"resourceId":"android:id/text1","text":"异步结果","withinResourceId":"android:id/list"}}"""))
            assertTrue(delayed.toString(), delayed.getBoolean("conditionMet"))
            assertTrue(delayed.getLong("waitMs") >= 900)
            val quiet = call("input_text", JSONObject("""{"resourceId":"android:id/edit","text":"仅输入"}"""))
            assertTrue(quiet.getBoolean("treeStable"))
            assertTrue(quiet.isNull("conditionMet"))
            val unmet = call("do_steps", JSONObject("""{"steps":[{"tool":"input_text","args":{"resourceId":"android:id/edit","text":"输入并非结果","expect":{"text":"输入并非结果"},"timeoutMs":500}},{"tool":"home","args":{}}]}"""))
            assertTrue(unmet.getBoolean("ok"))
            assertTrue(unmet.getJSONArray("steps").getJSONObject(0).getBoolean("executed"))
            assertFalse(unmet.getJSONArray("steps").getJSONObject(0).getBoolean("conditionMet"))
            assertFalse(unmet.getBoolean("canContinue"))
            assertEquals("timeout", unmet.getJSONArray("steps").getJSONObject(0).getString("stopReason"))
            assertEquals(1, unmet.getInt("remaining"))
            val stopped = call("do_steps", JSONObject("""{"steps":[{"tool":"tap","args":{"text":"不存在的按钮"}},{"tool":"input_text","args":{"resourceId":"android:id/edit","text":"不应执行"}}]}"""))
            assertFalse(stopped.getBoolean("ok"))
            assertEquals(1, stopped.getInt("remaining"))
            assertFalse(call("screen_read").getJSONArray("elements").objects().any { it.optString("text") == "不应执行" })
            val dialog = call("do_steps", JSONObject("""{"steps":[{"tool":"tap","args":{"text":"显示弹窗"}},{"tool":"home","args":{}}]}"""))
            assertTrue(dialog.getBoolean("ok"))
            assertFalse(dialog.getBoolean("canContinue"))
            assertEquals(1, dialog.getInt("remaining"))
            assertTrue(call("tap", JSONObject().put("text", "关闭")).getBoolean("ok"))
            assertEquals("dialog", call("tap", JSONObject().put("text", "显示弹窗")).getString("stopReason"))
            call("back")
            val unplanned = call("do_steps", JSONObject("""{"steps":[{"tool":"tap","args":{"text":"打开系统设置"}},{"tool":"home","args":{}}]}"""))
            assertTrue(unplanned.toString(), unplanned.getBoolean("ok"))
            assertFalse(unplanned.toString(), unplanned.getBoolean("canContinue"))
            assertEquals(1, unplanned.getInt("remaining"))
            val deadline = SystemClock.uptimeMillis() + 5000
            while (call("screen_read").optString("package") != "com.android.settings" && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertEquals("com.android.settings", call("screen_read").getString("package"))
            val navigation = call("do_steps", JSONObject("""{"steps":[{"tool":"phone_open_link","args":{"action":"android.settings.SOUND_SETTINGS","expect":{"package":"com.android.settings"}}},{"tool":"home","args":{}}]}"""))
            assertTrue(navigation.toString(), navigation.getBoolean("ok"))
            call("app_send_intent", JSONObject().put("action", "android.settings.SETTINGS"))
            call("app_launch", JSONObject().put("package", context.packageName))
            instrumentation.runOnMainSync { activity.startActivity(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestActivity"))) }
            SystemClock.sleep(400)
            instrumentation.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
            SystemClock.sleep(400)
            screen = call("screen_read")
            val bottom = checkNotNull(screen.getJSONArray("elements").objects().firstOrNull { it.optString("text") == "底部按钮" }) { "底部按钮缺失：$screen" }.getJSONArray("bounds")
            call("tap", JSONObject().put("x", (bottom.getInt(0) + bottom.getInt(2)) / 2).put("y", (bottom.getInt(1) + bottom.getInt(3)) / 2))
            assertTrue(call("screen_read").getJSONArray("elements").objects().any { it.optString("text") == "底部点击成功" })
            val height = screen.getInt("height"); val width = screen.getInt("width")
            call("swipe", JSONObject().put("x", width / 2).put("y", height * 3 / 4).put("endX", width / 2).put("endY", height / 2))
            call("home")
            assertEquals(context.packageName, call("app_launch", JSONObject().put("package", context.packageName)).getString("package"))
            val observationActivity = instrumentation.startActivitySync(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                val panel = android.widget.LinearLayout(observationActivity).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(20, 120, 20, 20) }
                val grid = android.widget.GridLayout(observationActivity).apply { columnCount = 10 }
                repeat(100) { i -> grid.addView(android.widget.TextView(observationActivity).apply { text = "$i"; textSize = 12f; minWidth = 48; minHeight = 36 }) }
                panel.addView(grid)
                val tab = android.widget.LinearLayout(observationActivity).apply {
                    id = android.R.id.tabs; isClickable = true
                    addView(android.widget.TextView(observationActivity).apply { text = "详情" })
                }
                tab.setOnClickListener { }
                panel.addView(tab)
                observationActivity.setContentView(panel)
            }
            screen = call("screen_read")
            val nodes = screen.getJSONArray("elements").objects()
            assertTrue(screen.toString(), nodes.size > 100)
            assertFalse(screen.getBoolean("truncated"))
            val tab = nodes.single { it.optString("resourceId") == "android:id/tabs" }
            assertEquals("详情", tab.getString("label"))
            assertEquals(tab.getString("id"), nodes.single { it.optString("text") == "详情" }.getString("parent"))
            val existing = call("tap", JSONObject().put("element", tab.getString("id")).put("expect", JSONObject().put("text", "详情")))
            assertTrue(existing.getBoolean("executed"))
            assertTrue(existing.getBoolean("conditionBefore"))
            assertFalse(existing.getBoolean("changed"))
            assertFalse(existing.getBoolean("canContinue"))
            assertEquals("condition_unchanged", existing.getString("stopReason"))
            instrumentation.runOnMainSync {
                val tab = observationActivity.findViewById<android.view.View>(android.R.id.tabs)
                tab.setOnClickListener { tab.postDelayed({ tab.isSelected = true }, 1100) }
            }
            val selected = call("tap", JSONObject().put("resourceId", "android:id/tabs")
                .put("expect", JSONObject().put("resourceId", "android:id/tabs").put("selected", true)))
            assertEquals("condition_met", selected.getString("stopReason"))
            assertTrue(selected.getBoolean("conditionMet"))
            assertFalse(selected.getBoolean("conditionBefore"))
            assertTrue(selected.getBoolean("changed"))
            assertTrue(selected.getLong("waitMs") >= 900)
            assertTrue(call("screen_read").getJSONArray("elements").objects().single { it.optString("resourceId") == "android:id/tabs" }.getBoolean("selected"))
            lateinit var ticker: Runnable
            lateinit var changing: android.widget.TextView
            instrumentation.runOnMainSync {
                val tab = observationActivity.findViewById<android.view.View>(android.R.id.tabs)
                tab.isSelected = false
                tab.setOnClickListener { tab.postDelayed({ tab.isSelected = true }, 400) }
                changing = (tab.parent as android.view.ViewGroup)
                    .let { (it.getChildAt(0) as android.widget.GridLayout).getChildAt(0) as android.widget.TextView }
                var tick = 0
                ticker = object : Runnable {
                    override fun run() { changing.text = "更新 ${tick++}"; changing.postDelayed(this, 80) }
                }
                changing.post(ticker)
            }
            val resetDeadline = SystemClock.uptimeMillis() + 3000
            while (call("screen_read").getJSONArray("elements").objects().single { it.optString("resourceId") == "android:id/tabs" }.getBoolean("selected")) {
                check(SystemClock.uptimeMillis() < resetDeadline) { "Target selection did not reset" }
                SystemClock.sleep(80)
            }
            try {
                val dynamic = call("tap", JSONObject().put("resourceId", "android:id/tabs").put("timeoutMs", 1800)
                    .put("expect", JSONObject().put("resourceId", "android:id/tabs").put("selected", true)))
                assertTrue(dynamic.toString(), dynamic.getBoolean("conditionMet"))
                assertFalse(dynamic.toString(), dynamic.getBoolean("conditionBefore"))
                assertFalse(dynamic.toString(), dynamic.getBoolean("treeStable"))
                assertEquals(dynamic.toString(), "condition_met", dynamic.getString("stopReason"))
                assertTrue(dynamic.toString(), dynamic.getLong("waitMs") < 1500)
                instrumentation.sendStatus(2, Bundle().apply { putString("conditionWait", "waitMs=${dynamic.getLong("waitMs")}, treeStable=${dynamic.getBoolean("treeStable")}, conditionMet=${dynamic.getBoolean("conditionMet")}") })
            } finally { instrumentation.runOnMainSync { changing.removeCallbacks(ticker) } }
            instrumentation.runOnMainSync { observationActivity.finish(); activity.finish() }
        } finally {
            shell(if (originalServices.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $originalServices")
            if (originalServices.isEmpty()) shell("settings put secure accessibility_enabled 0")
        }
    }

    @Test fun taskJournalPreservesFailureRecoveryAndRedactsCredentials() {
        val directory = File(context.cacheDir, "journal-test").apply { mkdirs() }
        val journal = TaskJournal(directory)
        try {
            val secret = "key-with-\"quotes\\and中文"
            journal.remember(secret)
            journal.append(JSONObject().put("type", "task_open").put("taskId", "test-task").put("session", "test-session").put("prompt", "test"))
            journal.append(JSONObject().put("type", "step").put("taskId", "test-task").put("apiKey", "unknown-key").put("text", "echo $secret"))
            val file = File(directory, "test-task.jsonl")
            file.appendText("{\"type\":")
            journal.recover()
            val records = journal.records(file)
            assertEquals(3, records.size)
            assertEquals("interrupted", records.last().getString("type"))
            assertFalse(file.readText().contains("unknown-key"))
            assertEquals("echo [redacted]", records[1].getString("text"))
            journal.recover()
            assertEquals(3, journal.records(file).size)
            for (type in listOf("error", "cancelled", "result")) {
                journal.append(JSONObject().put("type", "task_open").put("taskId", "test-$type"))
                journal.append(JSONObject().put("type", type).put("taskId", "test-$type"))
            }
            journal.recover()
            for (type in listOf("error", "cancelled", "result")) assertEquals(type, journal.records(File(directory, "test-$type.jsonl")).last().getString("type"))
        } finally { directory.deleteRecursively() }
    }
}
