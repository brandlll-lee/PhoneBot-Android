package dev.phonebot.android.engine

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.ViewModelProvider
import dev.phonebot.android.PhoneBotModel

@RunWith(AndroidJUnit4::class)
class EngineIntegrationTest {
    @Test fun parallelProbesKeepTheirOwnEventsAndResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val events = LinkedBlockingQueue<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val suffix = java.util.UUID.randomUUID().toString()
        val ids = setOf("probe-a-$suffix", "probe-b-$suffix")
        lateinit var engine: EngineClient
        instrumentation.runOnMainSync {
            engine = EngineClient(context)
            scope.launch { engine.events.collect { events.add(it) } }
            engine.start()
        }
        try {
            await(engine) { it.canRun }
            instrumentation.runOnMainSync {
                ids.forEach { engine.command(JSONObject().put("type", "probe").put("taskId", it)) }
            }
            val seen = mutableListOf<JSONObject>()
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (seen.filter { it.optString("type") == "idle" }.map { it.getString("taskId") }.toSet() != ids && SystemClock.elapsedRealtime() < deadline) {
                val event = events.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (event.optString("taskId") !in ids) continue
                seen += event
                assertTrue(event.toString(), event.optString("type") !in setOf("error", "cancelled", "interrupted"))
            }
            for (id in ids) {
                val own = seen.filter { it.getString("taskId") == id }
                for (type in listOf("running", "result", "idle")) assertEquals("$id/$type: $own", 1, own.count { it.optString("type") == type })
                val result = own.single { it.optString("type") == "result" }
                assertEquals(2, result.getJSONObject("evidence").getInt("requests"))
                val info = JSONObject(result.getJSONObject("evidence").getString("toolResult"))
                assertEquals("android", info.getString("platform"))
                assertEquals(engine.state.value.pid, info.getInt("pid"))
                assertTrue(own.indexOfFirst { it.optString("type") == "running" } < own.indexOfFirst { it.optString("type") == "result" })
                assertTrue(own.indexOfFirst { it.optString("type") == "result" } < own.indexOfFirst { it.optString("type") == "idle" })
            }
            assertTrue("Both probes must run before either finishes: $seen",
                seen.indexOfLast { it.optString("type") == "running" } < seen.indexOfFirst { it.optString("type") == "result" })
        } finally {
            instrumentation.runOnMainSync {
                ids.forEach { engine.cancel(it) }
                scope.cancel(); engine.detach(); activity.finish()
            }
        }
    }

    @Test fun piCatalogAuthenticationAndSavedSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as androidx.activity.ComponentActivity
        val events = LinkedBlockingQueue<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var engine: EngineClient
        instrumentation.runOnMainSync {
            engine = EngineClient(context)
            scope.launch { engine.events.collect { events.add(it) } }
            engine.start()
        }
        fun command(value: JSONObject) = instrumentation.runOnMainSync { engine.command(value) }
        fun event(type: String): JSONObject {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline) {
                val next = events.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (next.optString("type") == "error") throw AssertionError(next.toString())
                if (next.optString("type") == type) return next
            }
            throw AssertionError("Missing $type; ${engine.state.value}")
        }
        try {
            await(engine) { it.canRun }
            command(JSONObject().put("type", "catalog").put("provider", "deepseek"))
            val catalog = event("catalog")
            assertTrue(catalog.getJSONArray("providers").length() >= 40)
            val model = catalog.getJSONArray("models").getJSONObject(0).getString("id")
            await(engine) { it.canRun }
            command(JSONObject().put("type", "login").put("provider", "deepseek").put("auth", "api_key"))
            val prompt = event("auth_prompt")
            assertEquals("secret", prompt.getJSONObject("params").getString("type"))
            command(JSONObject().put("type", "tool_result").put("taskId", prompt.getString("taskId")).put("id", prompt.getString("id")).put("result", "phonebot-instrumentation-key"))
            assertTrue(event("account").getBoolean("configured"))
            await(engine) { it.canRun }
            assertEquals("phonebot-instrumentation-key", CredentialVault(context).read("deepseek")!!.getString("key"))
            instrumentation.runOnMainSync { ViewModelProvider(activity)[PhoneBotModel::class.java].choose("deepseek", model) }
            instrumentation.runOnMainSync { assertEquals(model, ViewModelProvider(activity)[PhoneBotModel::class.java].model) }
            command(JSONObject().put("type", "logout").put("provider", "deepseek"))
            assertTrue(!event("account").getBoolean("configured"))
            await(engine) { it.canRun }
            instrumentation.waitForIdleSync()
            assertNull(CredentialVault(context).read("deepseek"))
            instrumentation.runOnMainSync { assertEquals("", ViewModelProvider(activity)[PhoneBotModel::class.java].provider) }

            command(JSONObject().put("type", "task").put("taskId", "journal-failure-test").put("session", "journal-test-session")
                .put("provider", "deepseek").put("model", model).put("prompt", "打开设置"))
            await(engine) { it.canRun && it.detail.startsWith("Error:") }
            val journal = TaskJournal(File(context.filesDir, "task-events"))
            val records = journal.records(File(context.filesDir, "task-events/journal-failure-test.jsonl"))
            assertTrue(records.any { it.optString("type") == "task_open" })
            assertTrue(records.any { it.optString("type") == "model_request" })
            assertTrue(records.any { it.optString("type") == "error" })
            val transcript = File(context.filesDir, "sessions/journal-test-session.jsonl")
            assertTrue(transcript.exists())
            assertTrue(transcript.readLines().map(::JSONObject).any { it.optString("type") == "message" })
            assertTrue(!File(context.filesDir, "task-events/journal-failure-test.jsonl").readText().contains("phonebot-instrumentation-key"))
        } finally {
            instrumentation.runOnMainSync { if (engine.state.value.canStop) engine.stop() }
            await(engine) { it.canStart }
            instrumentation.runOnMainSync { scope.cancel(); engine.detach(); activity.finish() }
            CredentialVault(context).delete("deepseek")
            File(context.filesDir, "task-events/journal-failure-test.jsonl").delete()
            File(context.filesDir, "transcripts/journal-test-session.json").delete()
            File(context.filesDir, "sessions/journal-test-session.jsonl").delete()
        }
    }

    @Test fun runtimeRoundTripAndProcessLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var engine: EngineClient
        instrumentation.runOnMainSync { engine = EngineClient(context); engine.start() }
        try {
            await(engine) { it.phase == EnginePhase.READY }
            val firstPid = engine.state.value.pid
            assertNotEquals(Process.myPid(), firstPid)
            assertTrue(engine.state.value.detail.contains("Node v24.18.0"))
            assertTrue(engine.state.value.detail.contains("Pi 1.4.0"))

            instrumentation.runOnMainSync { engine.probe() }
            await(engine) { it.phase == EnginePhase.READY && it.detail.contains("\"requests\":2") }
            assertTrue(engine.state.value.detail.contains("android"))
            assertTrue(engine.state.value.detail.contains(firstPid.toString()))

            instrumentation.runOnMainSync { engine.command(org.json.JSONObject().put("type", "verify").put("provider", "unknown").put("model", "test")) }
            await(engine) { it.phase == EnginePhase.READY && it.detail.startsWith("Error:") }

            instrumentation.runOnMainSync { engine.stop() }
            await(engine) { it.phase == EnginePhase.STOPPED }
            instrumentation.runOnMainSync { engine.start() }
            await(engine) { it.phase == EnginePhase.READY }
            assertNotEquals(firstPid, engine.state.value.pid)

            Process.killProcess(engine.state.value.pid)
            await(engine) { it.phase == EnginePhase.FAILED }
            instrumentation.runOnMainSync { engine.start() }
            await(engine) { it.phase == EnginePhase.READY }
            instrumentation.runOnMainSync { engine.probe() }
            await(engine) { it.phase == EnginePhase.READY && it.detail.contains("\"requests\":2") }
            instrumentation.runOnMainSync { engine.stop() }
            await(engine) { it.phase == EnginePhase.STOPPED }
            assertEquals(EnginePhase.STOPPED, engine.state.value.phase)
        } finally {
            instrumentation.runOnMainSync { if (engine.state.value.canStop) engine.stop(); engine.detach(); activity.finish() }
        }
    }

    private fun await(engine: EngineClient, condition: (EngineState) -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition(engine.state.value)) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Engine state: ${engine.state.value}")
    }
}
