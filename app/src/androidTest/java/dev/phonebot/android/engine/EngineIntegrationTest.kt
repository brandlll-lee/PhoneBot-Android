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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.ViewModelProvider
import dev.phonebot.android.PhoneBotModel

@RunWith(AndroidJUnit4::class)
class EngineIntegrationTest {
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
            command(JSONObject().put("type", "tool_result").put("id", prompt.getString("id")).put("result", "phonebot-instrumentation-key"))
            assertTrue(event("account").getBoolean("configured"))
            await(engine) { it.canRun }
            assertEquals("phonebot-instrumentation-key", CredentialVault(context).read("deepseek")!!.getString("key"))
            instrumentation.runOnMainSync { ViewModelProvider(activity)[PhoneBotModel::class.java].choose("deepseek", model) }
            assertEquals(model, context.getSharedPreferences("phonebot", 0).getString("model", ""))
            command(JSONObject().put("type", "logout").put("provider", "deepseek"))
            assertTrue(!event("account").getBoolean("configured"))
            await(engine) { it.canRun }
            instrumentation.waitForIdleSync()
            assertNull(CredentialVault(context).read("deepseek"))
            assertEquals("", context.getSharedPreferences("phonebot", 0).getString("provider", ""))
        } finally {
            instrumentation.runOnMainSync { if (engine.state.value.canStop) engine.stop() }
            await(engine) { it.canStart }
            instrumentation.runOnMainSync { scope.cancel(); engine.detach(); activity.finish() }
            CredentialVault(context).delete("deepseek")
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
