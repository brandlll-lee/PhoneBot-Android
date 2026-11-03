package dev.phonebot.android.engine

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    @Test fun cloudContinuationKeepsThePageAndUsesOrderedTools() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run with -e cloud true to use the configured model", arguments.getString("cloud") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        val services = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
        val enabled = shell("settings get secure accessibility_enabled")
        val screenTimeout = shell("settings get system screen_off_timeout")
        val component = "${context.packageName}/.phone.PhoneAccessibilityService"
        val others = services.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(component) }.joinToString(":")
        val provider = arguments.getString("provider", "deepseek")
        val model = arguments.getString("model", "deepseek-flash")
        assertTrue("Configured model account required", CredentialVault(context).list().toString().contains(provider))
        val bot = "workflow-${UUID.randomUUID()}"
        val events = LinkedBlockingQueue<JSONObject>()
        val seen = mutableListOf<JSONObject>()
        val tasks = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val activity = instrumentation.startActivitySync(Intent().setComponent(ComponentName(context.packageName,
            "dev.phonebot.android.testing.PhoneTestActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var engine: EngineClient
        instrumentation.runOnMainSync {
            engine = EngineClient(context)
            scope.launch { engine.events.collect { events.add(it) } }
            engine.start()
        }
        fun command(value: JSONObject) = instrumentation.runOnMainSync { engine.command(value) }
        fun waitUntil(message: String, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 30000
            while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertTrue(message, predicate())
        }
        fun task(prompt: String): List<JSONObject> {
            val id = UUID.randomUUID().toString().also(tasks::add)
            command(JSONObject().put("type", "task").put("taskId", id).put("session", bot).put("botName", "Workflow test")
                .put("provider", provider).put("model", model).put("helper", arguments.getString("helper", "builtin"))
                .put("screen", "virtual").put("prompt", prompt))
            val own = mutableListOf<JSONObject>()
            val until = SystemClock.elapsedRealtime() + 180000
            while (SystemClock.elapsedRealtime() < until) {
                val event = events.poll(250, TimeUnit.MILLISECONDS) ?: continue
                seen += event
                if (event.optString("taskId") != id) continue
                own += event
                assertTrue(event.toString(), event.optString("type") !in setOf("error", "permission", "cancelled", "interrupted"))
                if (event.optString("type") == "idle") {
                    assertEquals(1, own.count { it.optString("type") == "result" })
                    val records = TaskJournal(File(context.filesDir, "task-events")).records(File(context.filesDir, "task-events/$id.jsonl"))
                    assertTrue(records.filter { it.optString("type") == "host_result" }.none {
                        !it.isNull("error") || it.optJSONObject("result")?.optString("stopReason") == "timeout"
                    })
                    return records
                }
            }
            throw AssertionError("Cloud task timed out: ${own.map { it.optString("type") }}")
        }
        try {
            waitUntil("Engine ready") { engine.state.value.canRun }
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            shell("settings put secure enabled_accessibility_services ${listOf(others, component).filter(String::isNotEmpty).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            waitUntil("Accessibility bound") { shell("dumpsys accessibility").substringAfter("Bound services:").substringBefore("Enabled services:").contains(context.packageName) }
            command(JSONObject().put("type", "helper").put("action", "connect").put("helper", arguments.getString("helper", "builtin")))
            val helperDeadline = SystemClock.elapsedRealtime() + 45000
            var helperReady = false
            while (!helperReady && SystemClock.elapsedRealtime() < helperDeadline) {
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                seen += event
                helperReady = event.optString("type") == "helper_status" && event.optString("helper") == arguments.getString("helper", "builtin") && event.optString("state") == "ready"
            }
            assertTrue("Helper ready", helperReady)
            shell("input keyevent 3")
            SystemClock.sleep(1000)
            val first = task("在独立屏幕打开测试页：Android action 为 dev.phonebot.android.TEST，package 为 ${context.packageName}，extras 的 conditionFixture 为 true。点击‘下一张’，用 expect.textContains=‘第2张’核对结果，再截图给我。保留页面，后续继续。")
            val display = first.first { it.optString("type") == "phone_target" }.getInt("displayId")
            val manager = context.getSystemService(DisplayManager::class.java)
            assertTrue(display > 0)
            assertNotNull("Completed task retains its workspace", manager.getDisplay(display))
            shell("settings put system screen_off_timeout 3000")
            waitUntil("Idle workspace sleeps after task completion") {
                shell("dumpsys display").substringAfter("Display Power Controller:\n  mDisplayId=$display\n")
                    .substringBefore("Display Power Controller:").contains("mScreenState=OFF")
            }
            val marker = "继续工作${SystemClock.uptimeMillis()}"
            val second = task("继续使用刚才的页面。不要重新打开。先读取当前页面，再在‘中文输入框’填写‘$marker’，核对仍然显示第2张，再截图给我。")
            assertTrue(second.any { it.optString("type") == "phone_target" })
            assertTrue(second.filter { it.optString("type") == "phone_target" }.all { it.getInt("displayId") == display })
            fun ids(records: List<JSONObject>) = records.filter { it.optString("type") == "host_result" }.flatMap { event ->
                val elements = event.optJSONObject("result")?.optJSONArray("elements") ?: JSONArray()
                (0 until elements.length()).map { elements.getJSONObject(it).getString("id") }
            }.toSet()
            assertTrue("Both turns return observed elements", ids(first).isNotEmpty() && ids(second).isNotEmpty())
            assertTrue("Task observations have distinct element IDs", ids(first).intersect(ids(second)).isEmpty())
            assertTrue(second.filter { it.optString("type") == "step" }.none { it.optString("name") in setOf("app_launch", "app_send_intent") })
            assertTrue(second.any { it.optString("type") == "host_result" && it.optString("name") == "input_text" &&
                it.optJSONObject("result")?.let { result -> result.optBoolean("inputVerified") && result.optString("actualText") == marker } == true })
            task("保持当前页面不变。请把读取当前页面和截图这两项工具调用放在同一次回复中，按顺序提交，然后简要报告看到的内容。")
            assertTrue("Cloud model should use Pi's ordered tool calls", seen.any {
                it.optString("type") == "model_response" && it.optJSONArray("content")?.let { content ->
                    (0 until content.length()).count { index -> content.getJSONObject(index).optString("type") == "toolCall" } > 1
                } == true
            })
            command(JSONObject().put("type", "phone_close").put("session", bot))
            waitUntil("Closing Bot releases its display") { manager.getDisplay(display) == null }
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("workflow", "2 continuation turns retained display $display; explicit ordered-tools turn passed") })
        } finally {
            instrumentation.runOnMainSync { tasks.forEach(engine::cancel) }
            val deadline = SystemClock.elapsedRealtime() + 10000
            val pending = tasks.filter { id -> seen.none { it.optString("taskId") == id && it.optString("type") == "idle" } }.toMutableSet()
            while (pending.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                seen += event
                if (event.optString("type") == "idle") pending.remove(event.optString("taskId"))
            }
            File(context.cacheDir, "workflow-cloud.json").writeText(JSONArray(tasks.flatMap {
                TaskJournal(File(context.filesDir, "task-events")).records(File(context.filesDir, "task-events/$it.jsonl"))
            }).toString())
            command(JSONObject().put("type", "phone_close").put("session", bot))
            instrumentation.runOnMainSync { scope.cancel(); engine.detach(); activity.finish() }
            shell(if (services.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $services")
            shell(if (enabled == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $enabled")
            shell(if (screenTimeout == "null") "settings delete system screen_off_timeout" else "settings put system screen_off_timeout $screenTimeout")
            File(context.filesDir, "sessions/$bot.jsonl").delete()
            File(context.filesDir, "images/$bot").deleteRecursively()
            tasks.forEach {
                context.getSystemService(android.app.NotificationManager::class.java).cancel(it, 2)
                File(context.filesDir, "task-events/$it.jsonl").delete()
            }
        }
    }
}
