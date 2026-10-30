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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineIntegrationTest {
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

            instrumentation.runOnMainSync { engine.verify("http://example.com", "test", "test-key") }
            await(engine) { it.phase == EnginePhase.READY && it.detail.contains("HTTPS") }

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
