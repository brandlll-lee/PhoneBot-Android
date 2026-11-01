package dev.phonebot.android.engine

enum class EnginePhase { STOPPED, STARTING, READY, RUNNING, STOPPING, FAILED }

data class EngineState(
    val phase: EnginePhase = EnginePhase.STOPPED,
    val detail: String = "",
    val pid: Int = 0,
) {
    val canStart get() = phase == EnginePhase.STOPPED || phase == EnginePhase.FAILED
    val canRun get() = phase == EnginePhase.READY || phase == EnginePhase.RUNNING
    val canStop get() = phase == EnginePhase.STARTING || phase == EnginePhase.READY || phase == EnginePhase.RUNNING
}

internal object EngineIpc {
    const val CONNECT = 1
    const val COMMAND = 2
    const val STATE = 3
    const val EVENT = 4
    const val DISCONNECT = 5
    const val APPEARANCE = 6
    const val STOP = "dev.phonebot.android.STOP"
}
