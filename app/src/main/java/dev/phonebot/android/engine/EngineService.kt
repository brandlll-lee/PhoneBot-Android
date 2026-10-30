package dev.phonebot.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import dev.phonebot.android.MainActivity
import java.io.File
import java.io.OutputStream
import org.json.JSONObject

class EngineService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var client: Messenger? = null
    private var writer: OutputStream? = null
    private var state = EngineState()
    private var started = false
    private var closing = false
    private val startupDeadline = Runnable { fail("Engine startup timed out") }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            EngineIpc.CONNECT -> { client = message.replyTo; publish(state) }
            EngineIpc.COMMAND -> handleCommand(message.data.getString("json").orEmpty())
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == EngineIpc.STOP) { stopEngine(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        publish(EngineState(EnginePhase.STARTING, pid = Process.myPid()))
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("engine", "PhoneBot engine", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 0, Intent(this, EngineService::class.java).setAction(EngineIpc.STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "engine")
                .setContentTitle("PhoneBot")
                .setContentText("Engine is active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, type)
            else startForeground(1, notification)
            handler.postDelayed(startupDeadline, 20000)
            Thread({ startRuntime() }, "node-runtime").start()
        } catch (error: Exception) { fail(error.message ?: "Foreground service failed") }
        return START_NOT_STICKY
    }

    private fun startRuntime() {
        try {
            val runtime = File(filesDir, "runtime").apply { mkdirs() }
            val version = assets.open("runtime/version.txt").bufferedReader().use { it.readText() }
            val script = File(runtime, "engine-$version.mjs")
            if (!script.isFile) assets.open("runtime/engine.mjs").use { source ->
                script.outputStream().use { source.copyTo(it) }
            }
            runtime.listFiles()?.filter { it != script }?.forEach { it.delete() }
            val incoming = ParcelFileDescriptor.createPipe()
            val outgoing = ParcelFileDescriptor.createPipe()
            val commandWriter = ParcelFileDescriptor.AutoCloseOutputStream(incoming[1])
            val eventReader = ParcelFileDescriptor.AutoCloseInputStream(outgoing[0])
            handler.post { writer = commandWriter; if (closing) write(JSONObject().put("type", "stop")) }
            Thread({
                try {
                    eventReader.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            require(line.length <= 65536) { "Engine event exceeds 64 KiB" }
                            val event = JSONObject(line)
                            handler.post { handleEvent(event) }
                        }
                    }
                    handler.post { if (!closing) fail("Engine output closed") }
                } catch (error: Exception) { handler.post { if (!closing) fail(error.message ?: "Engine read failed") } }
            }, "node-events").start()
            val code = NodeRuntime.start(script.path, filesDir.path, cacheDir.path, incoming[0].detachFd(), outgoing[1].detachFd())
            handler.post { if (!closing) fail("Node exited with code $code") }
        } catch (error: Exception) { handler.post { fail(error.message ?: "Engine startup failed") } }
        catch (error: LinkageError) { handler.post { fail(error.message ?: "Native runtime failed to load") } }
    }

    private fun handleCommand(json: String) {
        try {
            val command = JSONObject(json)
            if (command.getString("type") == "stop") { stopEngine(); return }
            check(state.canRun) { "Engine is not ready" }
            require(command.getString("type") in setOf("probe", "verify")) { "Unknown command" }
            publish(state.copy(phase = EnginePhase.RUNNING, detail = ""))
            write(command)
        } catch (error: Exception) { publish(state.copy(detail = error.message ?: "Command failed")) }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.getString("type")
        if (closing && type != "stopped") return
        when (type) {
            "ready" -> {
                handler.removeCallbacks(startupDeadline)
                publish(EngineState(EnginePhase.READY, "Node ${event.getString("node")} / Pi ${event.getString("pi")}", Process.myPid()))
            }
            "tool" -> {
                val reply = JSONObject().put("type", "tool_result").put("id", event.getString("id"))
                if (event.getString("name") == "runtime_info") {
                    reply.put("result", JSONObject().put("platform", "android").put("sdk", Build.VERSION.SDK_INT)
                        .put("pid", Process.myPid()).put("manufacturer", Build.MANUFACTURER))
                } else reply.put("error", "Unknown Kotlin tool")
                write(reply)
            }
            "result" -> publish(state.copy(phase = EnginePhase.READY, detail = event.toString()))
            "error" -> publish(state.copy(phase = EnginePhase.READY, detail = "Error: ${event.getString("message")}"))
            "stopped" -> finish(EngineState(EnginePhase.STOPPED))
        }
    }

    private fun write(command: JSONObject) {
        try {
            val output = checkNotNull(writer) { "Engine input is not open" }
            output.write((command.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (error: Exception) { if (!closing) fail(error.message ?: "Engine write failed") }
    }

    private fun stopEngine() {
        if (closing) return
        closing = true
        handler.removeCallbacks(startupDeadline)
        publish(state.copy(phase = EnginePhase.STOPPING))
        writer?.let { write(JSONObject().put("type", "stop")) }
        handler.postDelayed({ finish(EngineState(EnginePhase.STOPPED, "Engine stopped")) }, 3000)
    }

    private fun fail(message: String) {
        finish(EngineState(EnginePhase.FAILED, message, Process.myPid()))
    }

    private fun finish(result: EngineState) {
        closing = true
        handler.removeCallbacksAndMessages(null)
        publish(result)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Node supports one instance per process. A fresh process owns each new run.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 200)
    }

    private fun publish(next: EngineState) {
        state = next
        try {
            client?.send(Message.obtain(null, EngineIpc.STATE).apply {
                data = Bundle().apply { putString("phase", next.phase.name); putString("detail", next.detail); putInt("pid", next.pid) }
            })
        } catch (_: RemoteException) { client = null }
    }

    override fun onDestroy() {
        writer?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
