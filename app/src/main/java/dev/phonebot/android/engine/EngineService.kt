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
import dev.phonebot.android.phone.PhoneExecution
import dev.phonebot.android.phone.PhoneResources
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.cancelAndJoin
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
    private val resources = PhoneResources()
    private val displays = mutableMapOf<String, PhoneDisplay>()
    private inner class Run(val id: String, val start: JSONObject?) {
        val display = (start?.let { displays.getOrPut(it.getString("session")) {
            PhoneDisplay(this@EngineService, shell, resources, it.getString("session"))
        } } ?: PhoneDisplay(this@EngineService, shell, resources, id)).apply {
            if (start != null) select(start.getString("session"), start.optString("helper", "shizuku"), start.optString("screen", "main"))
        }
        val system = PhoneSystem(this@EngineService, shell, display, id)
        var phone: PhoneExecution? = null
        var accessibility: PhoneAccessibilityService? = null
        var toolJob: Job? = null
        var startJob: Job? = null
        var pausedTool: JSONObject? = null
        var authPrompt: String? = null
        var promptEvent: JSONObject? = null
        var loginEvent: JSONObject? = null
        var cancelled = false
        var started = false
        @Volatile var ended = false
    }
    private val runs = ConcurrentHashMap<String, Run>()
    private var viewedBot = ""
    private val wakeLock by lazy { getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBot:task").apply { setReferenceCounted(false) } }
    private var helperJob: Job? = null
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
                    runs.values.forEach { run ->
                        run.start?.let { start -> journal.records(File(filesDir, "task-events/${start.getString("taskId")}.jsonl")).forEach { message.replyTo.send(eventMessage(it)) } }
                        run.loginEvent?.let { message.replyTo.send(eventMessage(it)) }
                        run.promptEvent?.let { message.replyTo.send(eventMessage(it)) }
                        run.pausedTool?.let { message.replyTo.send(eventMessage(JSONObject().put("type", "permission").put("taskId", run.id)
                            .put("permission", "screen").put("message", run.display.pauseReason))) }
                    }
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
                            val run = runs[event.optString("taskId")]
                            val recorded = event.optString("type") != "tool" && run?.start != null
                            val safe = if (recorded) journal.append(event) else event
                            if (recorded && event.optString("type") in setOf("result", "error", "cancelled", "interrupted")) run?.ended = true
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
        var command = JSONObject()
        try {
            command = JSONObject(json)
            when (command.getString("type")) {
                "helper" -> { helperCommand(command); return }
                "phone_select" -> {
                    viewedBot = command.getString("session")
                    if (runs.values.none { !it.ended && it.start?.optString("session") == viewedBot })
                        displays[viewedBot]?.select(viewedBot, command.getString("helper"), command.getString("screen"))
                    updateOverlay(); return
                }
                "phone_close" -> {
                    val bot = command.getString("session")
                    check(runs.values.none { !it.ended && it.start?.optString("session") == bot }) { "此 Bot 正在执行任务" }
                    displays.remove(bot)?.release()
                    return
                }
                "stop" -> { stopEngine(); return }
                "cancel" -> {
                    val run = runs[command.getString("taskId")] ?: return
                    run.cancelled = true; run.pausedTool = null; run.authPrompt = null; run.promptEvent = null; run.loginEvent = null
                    run.toolJob?.cancel()
                    if (run.started) write(command) else {
                        run.startJob?.cancel()
                        emit(JSONObject().put("type", "cancelled").put("taskId", run.id).put("message", "任务已停止"))
                        handleEvent(JSONObject().put("type", "idle").put("taskId", run.id))
                    }
                    updateWakeLock()
                    return
                }
                "resume" -> {
                    val run = runs[command.getString("taskId")] ?: return
                    val pending = run.pausedTool ?: return
                    run.pausedTool = null; run.display.pauseReason = ""; updateWakeLock(); executeTool(pending)
                    return
                }
                "tool_result" -> {
                    val run = runs[command.getString("taskId")] ?: error("认证步骤已结束")
                    check(command.optString("id") == run.authPrompt) { "认证步骤已结束" }
                    run.authPrompt = null; run.promptEvent = null
                    journal.remember(command.opt("result"))
                    write(command.put("secret", true))
                    return
                }
            }
            check(state.canRun) { "Engine is not ready" }
            val type = command.getString("type")
            require(type in setOf("probe", "verify", "catalog", "login", "logout", "refresh", "task")) { "Unknown command" }
            val id = command.optString("taskId").ifEmpty { UUID.randomUUID().toString() }
            require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "任务编号无效" }
            command.put("taskId", id)
            check(!runs.containsKey(id)) { "任务编号重复" }
            if (type == "task") check(runs.values.none { !it.ended && it.start?.optString("session") == command.getString("session") }) { "此 Bot 正在执行任务" }
            val start = command.takeIf { type == "task" }?.let { JSONObject(it.toString()).put("type", "task_open") }
            val run = Run(id, start)
            runs[id] = run
            if (start != null) emit(start)
            publish(state.copy(phase = EnginePhase.RUNNING, detail = ""))
            updateWakeLock()
            run.startJob = scope.launch {
                try {
                    if (start != null && !run.display.virtual) {
                        if (resources.main.isLocked) emit(JSONObject().put("type", "waiting").put("taskId", id).put("resource", "main"))
                        resources.main.lock(id)
                    }
                    run.started = true
                    if (start != null) command.put("displayId", if (run.display.alive()) run.display.displayId else android.view.Display.INVALID_DISPLAY)
                    write(command)
                } catch (_: CancellationException) { resources.release(id) }
            }
        } catch (error: Exception) {
            val id = command.optString("taskId")
            if (id.isNotEmpty() && !runs.containsKey(id)) sendToClients { eventMessage(JSONObject().put("type", "error").put("taskId", id).put("message", error.message)) }
            publish(state.copy(detail = error.message ?: "Command failed"))
        }
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
            "idle" -> {
                val run = runs[event.optString("taskId")] ?: return
                scope.launch {
                    run.toolJob?.cancelAndJoin()
                    resources.release(run.id)
                    runs.remove(run.id, run)
                    updateWakeLock()
                    publish(state.copy(phase = if (runs.isEmpty()) EnginePhase.READY else EnginePhase.RUNNING))
                    emit(event, !recorded)
                }
            }
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
                        "forget" -> { require(helper == "builtin"); shell.forget() }
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

    private fun updateWakeLock() {
        val active = runs.values.filter { it.start != null && it.display.virtual && !it.ended && !it.cancelled && it.pausedTool == null }
        displays.values.filter { display -> active.none { it.display === display } }.forEach { it.rest() }
        if (active.isNotEmpty()) {
            if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)
        } else releaseWakeLock()
    }

    private fun releaseWakeLock() { if (wakeLock.isHeld) wakeLock.release() }

    private fun executeTool(event: JSONObject) {
        val run = runs[event.optString("taskId")] ?: return
        val name = event.getString("name")
        val id = event.getString("id")
        if (run.cancelled || run.ended) { write(JSONObject().put("type", "tool_result").put("taskId", run.id).put("id", id).put("error", "任务已停止")); return }
        val params = event.optJSONObject("params") ?: JSONObject()
        if (name == "auth_prompt") { run.authPrompt = id; run.promptEvent = event.put("type", "auth_prompt"); emit(event); return }
        val internal = name.startsWith("vault_") || name in setOf("runtime_info", "image_prepare")
        val usesScreen = !internal && name !in PhoneSystem.tools && name != "app_list"
        val phone = PhoneAccessibilityService.instance
        if (usesScreen && (phone == null || run.display.pauseReason.isNotEmpty() || !run.display.virtual && !phone.available(run.display))) {
            pause(run, event, if (phone == null) "accessibility" else if (run.display.pauseReason.isNotEmpty()) "screen" else "unlock")
            return
        }
        run.toolJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            val reply = JSONObject().put("type", "tool_result").put("taskId", run.id).put("id", id)
            try {
                if (usesScreen) {
                    try { withTimeout(20000) { run.display.prepare() } }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        pause(run, event, if (run.display.alive()) "screen" else run.display.helper, error.message ?: "执行屏幕不可用")
                        helperStates()
                        return@launch
                    }
                    if (run.accessibility !== phone) { run.phone = PhoneExecution(checkNotNull(phone), run.display, run.id); run.accessibility = phone }
                    if (run.start != null) journal.append(JSONObject().put("type", "phone_target").put("taskId", run.id)
                        .put("helper", run.display.helper).put("screen", run.display.mode).put("displayId", run.display.displayId))
                }
                val result: Any? = if (name in PhoneSystem.tools) run.system.execute(name, params)
                else if (usesScreen) checkNotNull(run.phone).execute(name, params) { substep ->
                    substep.put("taskId", run.id).put("id", "$id:${substep.getString("id")}")
                    emit(substep)
                } else withTimeout(30000) {
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
                        else -> error("工具不存在")
                    }
                }
                check(!run.cancelled) { "任务已停止" }
                reply.put("result", result ?: JSONObject.NULL).put("secret", internal && name == "vault_read")
            } catch (_: CancellationException) {
                reply.put("error", if (run.cancelled) "任务已停止" else "工具执行超时")
            } catch (error: Exception) { reply.put("error", error.message ?: "工具执行失败") }
            if (!internal && run.start != null) journal.append(JSONObject().put("type", "host_result").put("taskId", run.id)
                .put("id", id).put("name", name).put("result", reply.opt("result")).put("error", reply.opt("error")).put("durationMs", SystemClock.elapsedRealtime() - began))
            if (!closing) write(reply)
        }
    }

    private fun pause(run: Run, event: JSONObject, permission: String, message: String = run.display.pauseReason) {
        run.pausedTool = event
        emit(JSONObject().put("type", "permission").put("taskId", run.id).put("permission", permission).put("message", message))
        updateWakeLock()
    }

    private fun emit(event: JSONObject, persist: Boolean = true) {
        val run = runs[event.optString("taskId")]
        val safe = if (persist && run?.start != null) journal.append(event) else journal.clean(event)
        if (event.optString("type") == "auth_event") run?.loginEvent = event
        if (event.optString("type") in setOf("result", "error", "cancelled", "interrupted")) {
            run?.ended = true
            updateWakeLock()
            run?.start?.let { TaskNotifications.post(this, journal, it, safe) }
        }
        updateOverlay()
        sendToClients { eventMessage(safe) }
    }

    private fun updateOverlay() {
        val overlay = PhoneAccessibilityService.instance?.overlay ?: return
        val live = runs.values.filter { it.start != null && !it.ended && !it.cancelled }
        val run = live.firstOrNull { it.start?.optString("session") == viewedBot } ?: live.firstOrNull { it.id == overlay.taskId } ?: live.firstOrNull()
        if (closing || run == null) overlay.close() else overlay.update(checkNotNull(run.start), run.display)
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
        runs.values.filter { it.start != null && !it.ended }.forEach { emit(JSONObject().put("type", "cancelled").put("taskId", it.id).put("message", "引擎已停止")) }
        closing = true
        releaseWakeLock()
        releaseRuns()
        helperJob?.cancel()
        shell.close()
        handler.removeCallbacks(startupDeadline)
        publish(state.copy(phase = EnginePhase.STOPPING))
        writer?.let { write(JSONObject().put("type", "stop")) }
        handler.postDelayed({ finish(EngineState(EnginePhase.STOPPED, "Engine stopped")) }, 3000)
    }

    private fun fail(message: String) {
        finish(EngineState(EnginePhase.FAILED, message, Process.myPid()))
    }

    private fun finish(result: EngineState) {
        runs.values.filter { it.start != null && !it.ended }.forEach {
            emit(JSONObject().put("type", "interrupted").put("taskId", it.id).put("message", result.detail.ifEmpty { "引擎已停止" }))
        }
        closing = true
        releaseWakeLock()
        releaseRuns()
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

    private fun releaseRuns() {
        runs.values.forEach { it.startJob?.cancel(); it.toolJob?.cancel(); resources.release(it.id) }; runs.clear()
        displays.values.forEach { it.release() }; displays.clear()
    }

    override fun onDestroy() {
        releaseWakeLock()
        releaseRuns()
        shell.close()
        writer?.close()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
