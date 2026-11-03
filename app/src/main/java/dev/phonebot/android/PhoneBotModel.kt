package dev.phonebot.android

import android.app.Application
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import dev.phonebot.android.engine.EngineClient
import dev.phonebot.android.engine.EnginePhase
import dev.phonebot.android.engine.TaskJournal
import dev.phonebot.android.phone.PhonePermissions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class TaskStep(val id: String, val name: String, val args: String = "", val detail: String = "", val done: Boolean = false, val error: Boolean = false,
    val kind: String = "tool", val request: Int = 0)
data class ChatTurn(val id: String, val prompt: String, val answer: String = "", val steps: List<TaskStep> = emptyList(), val status: String = "running", val image: String = "", val streamText: String = "")
data class ChatSession(val id: String = UUID.randomUUID().toString(), val title: String = "新 Bot", val updated: Long = System.currentTimeMillis(), val turns: List<ChatTurn> = emptyList(),
    val helper: String = "shizuku", val screen: String = "main", val provider: String = "", val model: String = "", val avatarColor: Long = 0L)
data class PiProvider(val id: String, val name: String, val count: Int, val configured: Boolean, val auth: List<Pair<String, String>>, val ambient: Boolean)
data class PiModel(val id: String, val name: String, val image: Boolean)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun ChatTurn.event(event: JSONObject): ChatTurn = when (event.optString("type")) {
    "model_request", "toolcall_start" -> copy(streamText = "")
    "text_delta", "thinking_delta" -> {
        val request = event.getInt("request")
        val id = "message-$request-${event.getInt("contentIndex")}"
        val kind = event.getString("type").removeSuffix("_delta")
        val detail = steps.find { it.id == id }?.detail.orEmpty() + event.getString("delta")
        val step = TaskStep(id, "", detail = detail, kind = kind, request = request)
        val updated = if (steps.any { it.id == id }) steps.map { if (it.id == id) step else it } else steps + step
        copy(steps = updated, streamText = if (kind == "text") {
            if (event.optBoolean("toolCalls")) "" else updated.filter { it.request == request && it.kind == "text" }.joinToString("") { it.detail }
        } else streamText)
    }
    "model_response" -> {
        val request = event.getInt("request")
        val blocks = event.getJSONArray("content").objects().mapIndexedNotNull { index, block ->
            val kind = block.getString("type")
            if (kind == "text" || kind == "thinking") TaskStep("message-$request-$index", "", detail = block.getString(if (kind == "text") "text" else "thinking"),
                done = true, kind = kind, request = request) else null
        }
        copy(steps = steps.filterNot { it.request == request && it.kind in listOf("text", "thinking") } + blocks,
            streamText = if (event.getJSONArray("content").objects().any { it.optString("type") == "toolCall" }) "" else blocks.filter { it.kind == "text" }.joinToString("") { it.detail })
    }
    "attachment" -> copy(image = event.getString("path"))
    "step" -> copy(steps = steps.filterNot { it.id == event.getString("id") } + TaskStep(event.getString("id"), event.optString("label", event.getString("name")), event.optJSONObject("args")?.toString().orEmpty()))
    "step_result" -> copy(steps = steps.map { if (it.id == event.getString("id")) it.copy(done = true, error = event.optBoolean("error"), detail = event.optString("text")) else it })
    "result" -> copy(answer = event.getString("text"), status = "done", streamText = "",
        steps = steps.filterNot { it.kind == "text" && it.request == event.optInt("request", steps.maxOfOrNull { step -> step.request } ?: 0) })
    "error", "cancelled", "interrupted" -> copy(streamText = "", status = when (event.getString("type")) { "cancelled" -> "stopped"; "interrupted" -> "interrupted"; else -> "error" },
        steps = steps + TaskStep("terminal", "", detail = event.optString("message", "任务已停止"), done = true,
            error = event.getString("type") != "cancelled", kind = "status"))
    else -> this
}

class PhoneBotModel(application: Application) : AndroidViewModel(application) {
    val engine = EngineClient(application)
    private val preferences = application.getSharedPreferences("phonebot", 0)
    var appearance by mutableStateOf(Appearance.from(preferences.getString("appearance", null))); private set
    private val historyFile = AtomicFile(File(application.filesDir, "chats.json"))
    private val journalDirectory = File(application.filesDir, "task-events")
    var sessions by mutableStateOf(listOf<ChatSession>()); private set
    var selectedSession by mutableStateOf(""); private set
    var focusedTask by mutableStateOf(""); private set
    private var requestedSession = ""
    val provider get() = session?.provider.orEmpty()
    val model get() = session?.model.orEmpty()
    var providers by mutableStateOf(listOf<PiProvider>()); private set
    var models by mutableStateOf(listOf<PiModel>()); private set
    var modelProvider by mutableStateOf(""); private set
    var modelTotal by mutableIntStateOf(0); private set
    val activeTask get() = session?.turns?.lastOrNull { it.status == "running" }?.id.orEmpty()
    private val taskPermissions = mutableStateMapOf<String, JSONObject>()
    val permission get() = taskPermissions[activeTask]?.optString("permission").orEmpty()
    val permissionMessage get() = taskPermissions[activeTask]?.optString("message").orEmpty()
    var permissions by mutableStateOf(PhonePermissions.check(application)); private set
    var authPrompt by mutableStateOf<JSONObject?>(null); private set
    var authEvent by mutableStateOf<JSONObject?>(null); private set
    var notice by mutableStateOf(""); private set
    val helperStates = mutableStateMapOf<String, JSONObject>()
    var defaultHelper by mutableStateOf(preferences.getString("defaultHelper", "shizuku").orEmpty()); private set
    var defaultScreen by mutableStateOf(preferences.getString("defaultScreen", "main").orEmpty()); private set
    private var verifiedModels by mutableStateOf(preferences.all.keys.filter { it.startsWith("verified:") }.map { it.removePrefix("verified:") }.toSet())
    private var pending: JSONObject? = null
    private var catalogRequest = ""
    private var saveJob: Job? = null
    private val historyMutex = Mutex()
    internal val markdown = mutableStateMapOf<String, Pair<String, State>>()
    internal val submissions = mutableStateMapOf<String, String>()
    val session get() = sessions.find { it.id == selectedSession }
    val deviceId: String = preferences.getString("device", null) ?: UUID.randomUUID().toString().also { preferences.edit { putString("device", it) } }

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    var restored = if (!historyFile.baseFile.exists()) emptyList() else JSONArray(String(historyFile.readFully(), Charsets.UTF_8)).objects().map { value ->
                        ChatSession(value.getString("id"), value.getString("title"), value.getLong("updated"), value.getJSONArray("turns").objects().map { turn ->
                            ChatTurn(turn.getString("id"), turn.getString("prompt"), turn.optString("answer"),
                                turn.getJSONArray("steps").objects().map { TaskStep(it.getString("id"), it.getString("name"), it.optString("args"), it.optString("detail"), it.optBoolean("done", true), it.optBoolean("error"),
                                    it.optString("kind", "tool"), it.optInt("request")) },
                                turn.optString("status").let { if (it == "running") "interrupted" else it }, turn.optString("image"))
                        }, value.optString("helper", "shizuku"), value.optString("screen", "main"),
                            value.optString("provider", preferences.getString("provider", "").orEmpty()), value.optString("model", preferences.getString("model", "").orEmpty()), value.optLong("avatarColor", 0L))
                    }
                    val journal = TaskJournal(journalDirectory)
                    journalDirectory.listFiles()?.filter { it.extension == "jsonl" }?.sortedBy { it.lastModified() }?.forEach { file ->
                        val events = journal.records(file)
                        val start = events.firstOrNull { it.optString("type") == "task_open" } ?: return@forEach
                        val id = start.getString("session")
                        var turn = ChatTurn(start.getString("taskId"), start.getString("prompt"))
                        events.forEach { turn = turn.event(it) }
                        if (turn.status == "running") turn = turn.copy(status = "interrupted")
                        val session = restored.find { it.id == id } ?: ChatSession(id, start.optString("botName", "PhoneBot"), start.optLong("time"), helper = start.optString("helper", "shizuku"), screen = start.optString("screen", "main"),
                            provider = start.optString("provider"), model = start.optString("model"), avatarColor = start.optLong("avatarColor", 0L))
                        restored = restored.filterNot { it.id == id } + session.copy(turns = session.turns.filterNot { it.id == turn.id } + turn)
                    }
                    restored.sortedByDescending { it.updated }
                }
                sessions = loaded
                selectedSession = loaded.firstOrNull()?.id.orEmpty()
                if (requestedSession.isNotEmpty()) openTask(requestedSession, focusedTask)
            } catch (_: Exception) { notice = "聊天记录读取失败" }
            if (sessions.isEmpty()) newSession()
            engine.start()
        }
        viewModelScope.launch { engine.events.collect(::onEvent) }
        viewModelScope.launch {
            engine.state.collect { state ->
                if (state.canRun) pending?.let { pending = null; engine.command(it) }
                if (state.phase == EnginePhase.FAILED || state.phase == EnginePhase.STOPPED) {
                    sessions = sessions.map { bot -> bot.copy(turns = bot.turns.map { if (it.status == "running") it.event(JSONObject().put("type", "interrupted").put("message", "引擎已停止")) else it }) }
                    taskPermissions.clear(); authPrompt = null; authEvent = null
                    save()
                }
            }
        }
    }
    fun refreshPermissions() { permissions = PhonePermissions.check(getApplication()) }
    fun newSession() {
        focusedTask = ""; requestedSession = ""
        val next = ChatSession(title = "新 Bot", helper = defaultHelper, screen = defaultScreen)
        sessions = listOf(next) + sessions
        selectedSession = next.id
        selectPhone()
        save()
    }
    fun selectSession(id: String) { selectedSession = id; focusedTask = ""; requestedSession = ""; selectPhone() }
    private fun selectPhone() {
        val current = session ?: return
        if (engine.state.value.canRun) engine.command(JSONObject().put("type", "phone_select").put("session", current.id).put("helper", current.helper).put("screen", current.screen))
    }
    fun chooseAppearance(value: Appearance) {
        appearance = value
        preferences.edit { putString("appearance", value.name) }
        engine.appearance(value)
    }

    fun choosePhone(helper: String = session?.helper ?: defaultHelper, screen: String = session?.screen ?: defaultScreen) {
        if (activeTask.isNotEmpty()) return
        require(helper in setOf("builtin", "shizuku") && screen in setOf("main", "virtual"))
        sessions = sessions.map { if (it.id == selectedSession) it.copy(helper = helper, screen = screen) else it }
        selectPhone()
        save()
    }
    fun savePhoneDefaults() {
        val current = session ?: return
        if (activeTask.isNotEmpty()) return
        defaultHelper = current.helper; defaultScreen = current.screen
        preferences.edit { putString("defaultHelper", defaultHelper); putString("defaultScreen", defaultScreen) }
        notice = "新 Bot 将使用当前配置"
    }
    fun helperCommand(action: String, helper: String = session?.helper ?: defaultHelper, port: Int = 0, code: String = "", enabled: Boolean = false) {
        if (activeTask.isNotEmpty() && action in setOf("forget", "recovery")) return
        command(JSONObject().put("type", "helper").put("action", action).put("helper", helper).put("port", port).put("code", code).put("enabled", enabled))
    }
    fun openTask(session: String, task: String) {
        requestedSession = session
        focusedTask = task
        if (sessions.any { it.id == session && it.turns.any { turn -> turn.id == task } }) {
            selectedSession = session
            selectPhone()
        }
    }
    fun deleteSession(id: String) {
        if (sessions.find { it.id == id }?.turns?.any { it.status == "running" } == true) return
        if (engine.state.value.canRun) engine.command(JSONObject().put("type", "phone_close").put("session", id))
        sessions = sessions.filter { it.id != id }
        markdown.keys.removeAll { it.startsWith("$id/") }
        submissions.remove(id)
        if (selectedSession == id) selectedSession = sessions.firstOrNull()?.id.orEmpty()
        if (sessions.isEmpty()) newSession() else save()
        selectPhone()
        viewModelScope.launch(Dispatchers.IO) {
            File(getApplication<Application>().filesDir, "sessions/$id.jsonl").delete()
            File(getApplication<Application>().filesDir, "transcripts/$id.json").delete()
            File(getApplication<Application>().filesDir, "images/$id").deleteRecursively()
            val journal = TaskJournal(journalDirectory)
            journalDirectory.listFiles()?.filter { file -> journal.records(file).firstOrNull()?.optString("session") == id }?.forEach { it.delete() }
        }
    }
    fun choose(providerId: String, modelId: String) {
        if (activeTask.isNotEmpty()) return
        sessions = sessions.map { if (it.id == selectedSession) it.copy(provider = providerId, model = modelId) else it }
        save()
    }
    fun editBot(name: String = session?.title.orEmpty(), color: Long = session?.avatarColor ?: 0L) {
        if (activeTask.isNotEmpty() || name.isBlank()) return
        sessions = sessions.map { if (it.id == selectedSession) it.copy(title = name.trim(), avatarColor = color) else it }
        save()
    }
    fun verified(providerId: String, modelId: String = model): Boolean = "$providerId:$modelId" in verifiedModels
    fun catalog(providerId: String = "", offset: Int = 0, query: String = "") {
        if (offset == 0) { models = emptyList(); modelProvider = providerId }
        catalogRequest = UUID.randomUUID().toString()
        command(JSONObject().put("type", "catalog").put("taskId", catalogRequest).put("provider", providerId).put("offset", offset).put("query", query))
    }
    fun login(id: String, auth: String) {
        authEvent = null
        command(JSONObject().put("type", "login").put("provider", id).put("auth", auth).put("deviceId", deviceId))
    }
    fun logout(id: String) = command(JSONObject().put("type", "logout").put("provider", id))
    fun refresh(id: String) {
        catalogRequest = UUID.randomUUID().toString()
        command(JSONObject().put("type", "refresh").put("taskId", catalogRequest).put("provider", id))
    }
    fun verify() = command(JSONObject().put("type", "verify").put("provider", provider).put("model", model))
    fun answerAuth(answer: String) {
        val prompt = authPrompt ?: return
        engine.command(JSONObject().put("type", "tool_result").put("taskId", prompt.optString("taskId")).put("id", prompt.getString("id")).put("result", answer))
        authPrompt = null
    }
    fun cancel() { taskPermissions.remove(activeTask); engine.cancel(activeTask) }
    fun cancelAuth() {
        val taskId = authPrompt?.optString("taskId")?.ifEmpty { null } ?: authEvent?.optString("taskId").orEmpty()
        engine.cancel(taskId)
        authPrompt = null; authEvent = null
    }
    fun resume() { taskPermissions.remove(activeTask); engine.command(JSONObject().put("type", "resume").put("taskId", activeTask)) }
    fun submit(prompt: String): Boolean {
        if (prompt.isBlank() || activeTask.isNotEmpty()) return false
        if (provider.isEmpty() || model.isEmpty()) { notice = "请先选择服务商和模型"; return false }
        if (!engine.state.value.canRun) { notice = "请等待引擎就绪"; return false }
        if (session == null) newSession()
        focusedTask = ""; requestedSession = ""
        val taskId = UUID.randomUUID().toString()
        sessions = sessions.map { if (it.id == selectedSession) it.copy(
            updated = System.currentTimeMillis(), turns = it.turns + ChatTurn(taskId, prompt)) else it }.sortedByDescending { it.updated }
        submissions[selectedSession] = taskId
        save()
        engine.command(JSONObject().put("type", "task").put("taskId", taskId).put("session", selectedSession).put("prompt", prompt).put("provider", provider).put("model", model)
            .put("helper", session?.helper ?: defaultHelper).put("screen", session?.screen ?: defaultScreen).put("botName", session?.title).put("avatarColor", session?.avatarColor))
        return true
    }
    private fun command(command: JSONObject) {
        val state = engine.state.value
        if (state.canRun || command.optString("type") == "helper" && state.phase == EnginePhase.RUNNING) engine.command(command)
        else if (state.canStart || state.phase == EnginePhase.STARTING) { pending = command; if (state.canStart) engine.start() }
        else if (command.optString("type") == "catalog" && activeTask.isEmpty()) pending = command
        else notice = "请先结束当前操作"
    }
    private fun changeTurn(taskId: String, change: (ChatTurn) -> ChatTurn) {
        sessions = sessions.map { session -> if (session.turns.any { it.id == taskId }) session.copy(updated = System.currentTimeMillis(), turns = session.turns.map { if (it.id == taskId) change(it) else it }) else session }.sortedByDescending { it.updated }
        save()
    }
    private fun onEvent(event: JSONObject) {
        when (event.getString("type")) {
            "helper_status" -> { helperStates[event.getString("helper")] = event; refreshPermissions() }
            "task_open" -> {
                val taskId = event.getString("taskId")
                val owner = event.getString("session")
                if (sessions.none { it.id == owner }) sessions = listOf(ChatSession(id = owner, title = event.optString("botName", "PhoneBot"), helper = event.optString("helper", "shizuku"), screen = event.optString("screen", "main"),
                    provider = event.optString("provider"), model = event.optString("model"), avatarColor = event.optLong("avatarColor", 0L))) + sessions
                sessions = sessions.map { session -> if (session.id == owner) session.copy(turns = if (session.turns.none { it.id == taskId }) session.turns + ChatTurn(taskId, event.getString("prompt"))
                    else session.turns.map { if (it.id == taskId) it.copy(status = "running") else it }) else session }
            }
            "catalog" -> {
                if (event.optString("taskId") != catalogRequest) return
                providers = event.getJSONArray("providers").objects().map { value -> PiProvider(value.getString("id"), value.getString("name"), value.getInt("count"), value.getBoolean("configured"),
                    value.getJSONArray("auth").objects().map { it.getString("id") to it.getString("name") }, value.optBoolean("ambient")) }
                val id = event.optString("provider")
                if (id.isNotEmpty()) {
                    val batch = event.getJSONArray("models").objects().map { PiModel(it.getString("id"), it.getString("name"), it.getBoolean("image")) }
                    models = if (event.optInt("offset") == 0 || modelProvider != id) batch else (models + batch).distinctBy { it.id }
                    modelProvider = id; modelTotal = event.getInt("total")
                }
            }
            "account" -> {
                val id = event.getString("provider")
                providers = providers.map { if (it.id == id) it.copy(configured = event.getBoolean("configured")) else it }
                preferences.edit { preferences.all.keys.filter { it.startsWith("verified:$id:") }.forEach(::remove) }
                verifiedModels = verifiedModels.filterNot { it.startsWith("$id:") }.toSet()
                if (!event.getBoolean("configured") && provider == id) choose("", "")
                authPrompt = null; authEvent = null; notice = if (event.getBoolean("configured")) "认证已保存" else "凭据已删除"
            }
            "verified" -> {
                val key = "${event.getString("provider")}:${event.getString("model")}"
                preferences.edit { putLong("verified:$key", System.currentTimeMillis()) }
                verifiedModels = verifiedModels + key
                notice = "本机连接验证通过"
            }
            "auth_prompt" -> authPrompt = event
            "auth_event" -> authEvent = event
            "permission" -> { taskPermissions[event.getString("taskId")] = event; refreshPermissions() }
            "waiting" -> changeTurn(event.optString("taskId")) { it.copy(steps = it.steps.filterNot { step -> step.id == "waiting" } + TaskStep("waiting", "", detail = event.optString("message", "等待共享资源"), kind = "status", done = true)) }
            "model_request", "toolcall_start", "text_delta", "thinking_delta", "model_response", "attachment", "step", "step_result" -> changeTurn(event.optString("taskId")) { it.event(event) }
            "result" -> if (sessions.any { bot -> bot.turns.any { it.id == event.optString("taskId") } }) changeTurn(event.optString("taskId")) { it.event(event) } else notice = "自检通过"
            "error", "cancelled", "interrupted" -> {
                val message = event.optString("message", "任务已停止")
                if (sessions.any { bot -> bot.turns.any { it.id == event.optString("taskId") } }) changeTurn(event.optString("taskId")) { it.event(event) }
                else notice = message
            }
            "idle" -> taskPermissions.remove(event.optString("taskId"))
        }
    }
    fun clearNotice() { notice = "" }
    private fun save() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(200)
            val snapshot = sessions
            val value = withContext(Dispatchers.Default) { JSONArray(snapshot.map { session -> JSONObject().put("id", session.id).put("title", session.title).put("updated", session.updated).put("helper", session.helper).put("screen", session.screen)
                .put("provider", session.provider).put("model", session.model).put("avatarColor", session.avatarColor)
                .put("turns", JSONArray(session.turns.map { turn -> JSONObject().put("id", turn.id).put("prompt", turn.prompt).put("answer", turn.answer).put("status", turn.status).put("image", turn.image)
                    .put("steps", JSONArray(turn.steps.map { step -> JSONObject().put("id", step.id).put("name", step.name).put("args", step.args).put("detail", step.detail).put("done", step.done).put("error", step.error)
                        .put("kind", step.kind).put("request", step.request) })) })) }).toString() }
            try {
                withContext(Dispatchers.IO) {
                    historyMutex.withLock {
                        val stream = historyFile.startWrite()
                        try { stream.write(value.toByteArray(Charsets.UTF_8)); historyFile.finishWrite(stream) }
                        catch (error: Exception) { historyFile.failWrite(stream); throw error }
                    }
                }
            } catch (error: java.io.IOException) { notice = "聊天保存失败：${error.message}" }
        }
    }
    override fun onCleared() { engine.detach() }
}
