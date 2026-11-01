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
import android.os.SystemClock
import android.os.PowerManager
import dev.phonebot.android.MainActivity
import dev.phonebot.android.Appearance
import dev.phonebot.android.PhoneAppearance
import java.io.File
import java.io.OutputStream
import org.json.JSONObject
import dev.phonebot.android.phone.PhoneAccessibilityService
import dev.phonebot.android.phone.PhoneShell
import dev.phonebot.android.phone.PhoneSystem
import dev.phonebot.android.phone.PhoneDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import java.util.UUID

class EngineService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val clients = mutableSetOf<Messenger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vault by lazy { CredentialVault(this) }
    private val journal by lazy { TaskJournal(File(filesDir, "task-events")) }
    private val shell by lazy { PhoneShell(this) { handler.post { helperStates() } } }
    private val display by lazy { PhoneDisplay(this, shell) }
    private val system by lazy { PhoneSystem(this, shell, display) }
    private val wakeLock by lazy { getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBot:task").apply { setReferenceCounted(false) } }
    private var helperJob: Job? = null
    private var toolJob: Job? = null
    private var pausedTool: JSONObject? = null
    private var authPrompt: String? = null
    private var taskId = ""
    private var cancelled = false
    @Volatile private var taskStart: JSONObject? = null
    @Volatile private var taskEnded = false
    private var promptEvent: JSONObject? = null
    private var loginEvent: JSONObject? = null
    private var writer: OutputStream? = null
    private var state = EngineState()
    private var started = false
    private var closing = false
    private val startupDeadline = Runnable { fail("Engine startup timed out") }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            EngineIpc.CONNECT -> {
                PhoneAppearance.mode = Appearance.from(message.data.getString("appearance"))
                clients.add(message.replyTo); publish(state)
                try {
                    taskStart?.let { start -> journal.records(File(filesDir, "task-events/${start.getString("taskId")}.jsonl")).forEach { message.replyTo.send(eventMessage(it)) } }
                    loginEvent?.let { message.replyTo.send(eventMessage(it)) }
                    promptEvent?.let { message.replyTo.send(eventMessage(it)) }
                } catch (_: RemoteException) { clients.remove(message.replyTo) }
            }
            EngineIpc.DISCONNECT -> clients.remove(message.replyTo)
            EngineIpc.COMMAND -> handleCommand(message.data.getString("json").orEmpty())
            EngineIpc.APPEARANCE -> PhoneAppearance.mode = Appearance.from(message.data.getString("appearance"))
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        journal.recover()
    }

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
                .setSmallIcon(dev.phonebot.android.R.drawable.ic_phonebot_mark)
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
                            val recorded = event.optString("type") != "tool" && taskStart?.optString("taskId") == event.optString("taskId")
                            val safe = if (recorded) journal.append(event) else event
                            if (recorded && event.optString("type") in setOf("result", "error", "cancelled", "interrupted")) taskEnded = true
                            handler.post { handleEvent(safe, recorded) }
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
            if (command.getString("type") == "helper") { helperCommand(command); return }
            if (command.getString("type") == "phone_select") {
                check(state.phase != EnginePhase.RUNNING) { "请先结束当前任务" }
                display.select(command.getString("session"), command.getString("helper"), command.getString("screen"))
                PhoneAccessibilityService.instance?.bind(display)
                return
            }
            if (command.getString("type") == "stop") { stopEngine(); return }
            if (command.getString("type") == "cancel") {
                cancelled = true; pausedTool = null; authPrompt = null; promptEvent = null; loginEvent = null; toolJob?.cancel(); releaseWakeLock(); write(command); return
            }
            if (command.getString("type") == "resume") {
                val pending = pausedTool ?: return
                pausedTool = null
                display.pauseReason = ""
                holdWakeLock()
                executeTool(pending)
                return
            }
            if (command.getString("type") == "tool_result") {
                check(command.optString("id") == authPrompt) { "认证步骤已结束" }
                authPrompt = null
                promptEvent = null
                journal.remember(command.opt("result"))
                write(command.put("secret", true))
                return
            }
            check(state.canRun) { "Engine is not ready" }
            require(command.getString("type") in setOf("probe", "verify", "catalog", "login", "logout", "refresh", "task")) { "Unknown command" }
            cancelled = false
            taskId = command.optString("taskId").ifEmpty { UUID.randomUUID().toString() }
            command.put("taskId", taskId)
            if (command.getString("type") == "task") {
                display.select(command.getString("session"), command.optString("helper", "shizuku"), command.optString("screen", "main"))
                taskStart = JSONObject(command.toString()).put("type", "task_open")
                taskEnded = false
                journal.append(checkNotNull(taskStart))
                PhoneAccessibilityService.instance?.beginTask(taskId)
                holdWakeLock()
            }
            publish(state.copy(phase = EnginePhase.RUNNING, detail = ""))
            write(command)
        } catch (error: Exception) { publish(state.copy(detail = error.message ?: "Command failed")) }
    }

    private fun handleEvent(event: JSONObject, recorded: Boolean = false) {
        val type = event.getString("type")
        if (closing && type != "stopped") return
        when (type) {
            "ready" -> {
                handler.removeCallbacks(startupDeadline)
                publish(EngineState(EnginePhase.READY, "Node ${event.getString("node")} / Pi ${event.getString("pi")}", Process.myPid()))
                helperStates()
            }
            "tool" -> executeTool(event)
            "result" -> { publish(state.copy(detail = event.toString())); emit(event, !recorded) }
            "error" -> { publish(state.copy(detail = "Error: ${event.getString("message")}")); emit(event, !recorded) }
            "idle" -> { toolJob?.cancel(); pausedTool = null; authPrompt = null; promptEvent = null; loginEvent = null; releaseWakeLock(); publish(state.copy(phase = EnginePhase.READY)); emit(event, !recorded) }
            "stopped" -> finish(EngineState(EnginePhase.STOPPED))
            else -> emit(event, !recorded)
        }
    }

    private fun helperStates() {
        for (helper in listOf("builtin", "shizuku")) sendToClients {
            eventMessage(shell.state(helper).put("type", "helper_status"))
        }
    }

    private fun helperCommand(command: JSONObject) {
        val helper = command.optString("helper", "builtin")
        require(helper in setOf("builtin", "shizuku")) { "助手方式无效" }
        val action = command.getString("action")
        if (action == "status") { helperStates(); return }
        if (action in setOf("forget", "recovery")) check(state.phase != EnginePhase.RUNNING) { "请先结束当前任务" }
        check(helperJob?.isActive != true) { "助手正在连接" }
        helperJob = scope.launch {
            try {
                withTimeout(45000) {
                    when (action) {
                        "pair" -> { require(helper == "builtin"); shell.pair(command.getInt("port"), command.getString("code")) }
                        "connect" -> shell.service(helper)
                        "forget" -> { require(helper == "builtin"); if (display.helper == helper) display.close(); shell.forget() }
                        "recovery" -> shell.setAutoRestore(command.getBoolean("enabled"), helper)
                        else -> error("助手操作无效")
                    }
                }
                helperStates()
            } catch (error: Exception) {
                if (error is CancellationException && closing) return@launch
                sendToClients { eventMessage(shell.state(helper).put("type", "helper_status").put("state", "error")
                    .put("message", if (error is CancellationException) "助手连接超时" else error.message ?: "助手连接失败")) }
            }
        }
    }

    private fun holdWakeLock() {
        if (display.virtual && !wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() { if (wakeLock.isHeld) wakeLock.release() }

    private fun executeTool(event: JSONObject) {
        val name = event.getString("name")
        val id = event.getString("id")
        if (event.optString("taskId") != taskId) return
        if (cancelled) { write(JSONObject().put("type", "tool_result").put("id", id).put("error", "任务已停止")); return }
        val params = event.optJSONObject("params") ?: JSONObject()
        if (name == "auth_prompt") { authPrompt = id; promptEvent = event.put("type", "auth_prompt"); emit(event); return }
        val internal = name.startsWith("vault_") || name in setOf("runtime_info", "image_prepare")
        val usesScreen = !internal && name !in PhoneSystem.tools && name != "app_list"
        val phone = PhoneAccessibilityService.instance
        if (taskStart?.optString("taskId") == taskId) phone?.beginTask(taskId)
        if (usesScreen && (phone == null || display.pauseReason.isNotEmpty() || !display.virtual && !phone.available())) {
            pausedTool = event
            releaseWakeLock()
            emit(JSONObject().put("type", "permission").put("taskId", taskId).put("permission", if (phone == null) "accessibility" else if (display.pauseReason.isNotEmpty()) "screen" else "unlock").put("message", display.pauseReason))
            return
        }
        toolJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            val reply = JSONObject().put("type", "tool_result").put("id", id)
            try {
                if (usesScreen) {
                    try { withTimeout(20000) { display.prepare() } }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        pausedTool = event
                        releaseWakeLock()
                        emit(JSONObject().put("type", "permission").put("taskId", taskId).put("permission", display.helper)
                            .put("message", error.message ?: "执行屏幕不可用"))
                        helperStates()
                        return@launch
                    }
                    checkNotNull(phone).bind(display)
                    if (taskStart?.optString("taskId") == taskId) journal.append(JSONObject().put("type", "phone_target").put("taskId", taskId)
                        .put("helper", display.helper).put("screen", display.mode).put("displayId", display.displayId))
                }
                val result: Any? = withTimeout(if (name == "do_steps") 90000 else 30000) {
                    if (name in PhoneSystem.tools) return@withTimeout system.execute(name, params)
                    when (name) {
                        "runtime_info" -> JSONObject().put("platform", "android").put("sdk", Build.VERSION.SDK_INT).put("pid", Process.myPid()).put("manufacturer", Build.MANUFACTURER)
                        "app_list" -> PhoneAccessibilityService.listApps(this@EngineService)
                        "image_prepare" -> withContext(Dispatchers.IO) {
                            val session = params.getString("session")
                            require(session.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "图片会话无效" }
                            ModelImages.prepare(File(filesDir, "images/$session"), params.getString("reference"))
                        }
                        "vault_read" -> withContext(Dispatchers.IO) { vault.read(params.getString("provider")).also { journal.remember(it) } }
                        "vault_list" -> withContext(Dispatchers.IO) { vault.list() }
                        "vault_write" -> withContext(Dispatchers.IO) { journal.remember(params.getJSONObject("credential")); vault.write(params.getString("provider"), params.getJSONObject("credential")); true }
                        "vault_delete" -> withContext(Dispatchers.IO) { vault.delete(params.getString("provider")); true }
                        else -> checkNotNull(phone).execute(name, params) { substep ->
                            substep.put("taskId", taskId).put("id", "$id:${substep.getString("id")}")
                            emit(substep)
                        }
                    }
                }
                check(!cancelled) { "任务已停止" }
                reply.put("result", result ?: JSONObject.NULL).put("secret", internal && name == "vault_read")
            } catch (error: CancellationException) {
                reply.put("error", if (cancelled) "任务已停止" else "工具执行超时")
            } catch (error: Exception) { reply.put("error", error.message ?: "工具执行失败") }
            if (!internal && taskStart?.optString("taskId") == taskId) journal.append(JSONObject().put("type", "host_result").put("taskId", taskId)
                .put("id", id).put("name", name).put("result", reply.opt("result")).put("error", reply.opt("error")).put("durationMs", SystemClock.elapsedRealtime() - began))
            if (!closing) write(reply)
        }
    }

    private fun emit(event: JSONObject, persist: Boolean = true) {
        if (event.optString("type") in setOf("result", "error", "cancelled", "interrupted")) releaseWakeLock()
        val safe = if (persist && taskStart != null && event.optString("taskId") == taskStart?.optString("taskId")) journal.append(event) else journal.clean(event)
        if (event.optString("type") == "auth_event") loginEvent = event
        if (taskStart != null && event.optString("taskId") == taskStart?.optString("taskId") && event.optString("type") in setOf("result", "error", "cancelled", "interrupted")) taskEnded = true
        taskStart?.takeIf { it.optString("taskId") == event.optString("taskId") }?.let { TaskNotifications.post(this, journal, it, safe) }
        updateOverlay()
        sendToClients { eventMessage(safe) }
    }

    private fun updateOverlay() {
        val overlay = PhoneAccessibilityService.instance?.overlay ?: return
        val start = taskStart
        if (closing || taskEnded || state.phase != EnginePhase.RUNNING || start?.optString("taskId") != taskId) overlay.close()
        else overlay.update(start, display)
    }

    private fun eventMessage(event: JSONObject) = Message.obtain(null, EngineIpc.EVENT).apply { data = Bundle().apply { putString("json", event.toString()) } }

    private fun sendToClients(message: () -> Message) {
        val iterator = clients.iterator()
        while (iterator.hasNext()) try { iterator.next().send(message()) } catch (_: RemoteException) { iterator.remove() }
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
        if (taskStart?.optString("taskId") == taskId && !taskEnded) emit(JSONObject().put("type", "cancelled").put("taskId", taskId).put("message", "引擎已停止"))
        closing = true
        releaseWakeLock()
        display.close()
        helperJob?.cancel()
        shell.close()
        cancelled = true
        pausedTool = null
        toolJob?.cancel()
        handler.removeCallbacks(startupDeadline)
        publish(state.copy(phase = EnginePhase.STOPPING))
        writer?.let { write(JSONObject().put("type", "stop")) }
        handler.postDelayed({ finish(EngineState(EnginePhase.STOPPED, "Engine stopped")) }, 3000)
    }

    private fun fail(message: String) {
        finish(EngineState(EnginePhase.FAILED, message, Process.myPid()))
    }

    private fun finish(result: EngineState) {
        taskStart?.takeIf { it.optString("taskId") == taskId && !taskEnded }?.let {
            emit(JSONObject().put("type", "interrupted").put("taskId", it.getString("taskId")).put("message", result.detail.ifEmpty { "引擎已停止" }))
        }
        closing = true
        releaseWakeLock()
        display.close()
        helperJob?.cancel()
        shell.close()
        handler.removeCallbacksAndMessages(null)
        publish(result)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Node supports one instance per process. A fresh process owns each new run.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 200)
    }

    private fun publish(next: EngineState) {
        state = next
        updateOverlay()
        sendToClients {
            Message.obtain(null, EngineIpc.STATE).apply {
                data = Bundle().apply { putString("phase", next.phase.name); putString("detail", next.detail); putInt("pid", next.pid) }
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        display.close()
        shell.close()
        writer?.close()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
