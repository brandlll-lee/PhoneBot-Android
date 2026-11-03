package dev.phonebot.android.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import dev.phonebot.android.Appearance

class EngineClient(private val context: Context) {
    private val mutableState = MutableStateFlow(EngineState())
    val state = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)
    val events = mutableEvents.asSharedFlow()
    private var remote: Messenger? = null
    private var bound = false
    private var terminalState: EngineState? = null
    private var appearance = Appearance.from(context.getSharedPreferences("phonebot", 0).getString("appearance", null))
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == EngineIpc.STATE) {
            val data = message.data
            val next = EngineState(EnginePhase.valueOf(data.getString("phase")!!), data.getString("detail").orEmpty(), data.getInt("pid"))
            if (next.canStart) {
                terminalState = next
                mutableState.value = next.copy(phase = EnginePhase.STOPPING)
            } else mutableState.value = next
        }
        if (message.what == EngineIpc.EVENT) mutableEvents.tryEmit(JSONObject(message.data.getString("json")!!))
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder)
            try { remote?.send(Message.obtain(null, EngineIpc.CONNECT).apply {
                replyTo = receiver; data = Bundle().apply { putString("appearance", appearance.name) }
            }) }
            catch (error: RemoteException) { disconnected(error.message ?: "Engine connection failed") }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected("Engine process disconnected")
        override fun onBindingDied(name: ComponentName) = disconnected("Engine process ended")
        override fun onNullBinding(name: ComponentName) = disconnected("Engine binding failed")
    }

    fun attach() {
        if (!bound) bound = context.bindService(Intent(context, EngineService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun start() {
        if (!mutableState.value.canStart) return
        terminalState = null
        mutableState.value = EngineState(EnginePhase.STARTING)
        try {
            context.startForegroundService(Intent(context, EngineService::class.java))
            attach()
            check(bound) { "Engine service binding failed" }
        } catch (error: Exception) { disconnected(error.message ?: "Engine start failed") }
    }

    fun appearance(value: Appearance) {
        appearance = value
        try { remote?.send(Message.obtain(null, EngineIpc.APPEARANCE).apply { data = Bundle().apply { putString("appearance", value.name) } }) }
        catch (error: RemoteException) { disconnected(error.message ?: "Engine connection failed") }
    }

    fun probe() = send(JSONObject().put("type", "probe"))
    fun command(command: JSONObject) = send(command)
    fun cancel(taskId: String) = send(JSONObject().put("type", "cancel").put("taskId", taskId))
    fun stop() {
        terminalState = EngineState(EnginePhase.STOPPED)
        mutableState.value = mutableState.value.copy(phase = EnginePhase.STOPPING)
        try { context.startService(Intent(context, EngineService::class.java).setAction(EngineIpc.STOP)) }
        catch (error: Exception) { terminalState = null; disconnected(error.message ?: "Engine stop failed") }
    }

    private fun send(command: JSONObject) {
        val control = command.optString("type") in setOf("cancel", "tool_result", "resume", "helper", "phone_select", "phone_close")
        if (!mutableState.value.canRun && !control) return
        try {
            checkNotNull(remote) { "Engine is disconnected" }.send(Message.obtain(null, EngineIpc.COMMAND).apply {
                data = Bundle().apply { putString("json", command.toString()) }
            })
            if (!control) mutableState.value = mutableState.value.copy(phase = EnginePhase.RUNNING, detail = "")
        } catch (error: Exception) { disconnected(error.message ?: "Engine command failed") }
    }

    private fun disconnected(reason: String) {
        if (!bound && mutableState.value.canStart) return
        val result = terminalState ?: EngineState(EnginePhase.FAILED, reason)
        terminalState = null
        detach()
        mutableState.value = result
    }

    fun detach() {
        try { remote?.send(Message.obtain(null, EngineIpc.DISCONNECT).apply { replyTo = receiver }) } catch (_: RemoteException) { }
        if (bound) context.unbindService(connection)
        bound = false
        remote = null
    }
}
