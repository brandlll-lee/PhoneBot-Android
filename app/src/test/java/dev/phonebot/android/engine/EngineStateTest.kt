package dev.phonebot.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStateTest {
    @Test fun controlsMatchEngineLifecycle() {
        for (phase in EnginePhase.entries) {
            val state = EngineState(phase)
            assertEquals(phase in setOf(EnginePhase.STOPPED, EnginePhase.FAILED), state.canStart)
            assertEquals(phase == EnginePhase.READY, state.canRun)
            assertEquals(phase in setOf(EnginePhase.STARTING, EnginePhase.READY, EnginePhase.RUNNING), state.canStop)
        }
    }
}
