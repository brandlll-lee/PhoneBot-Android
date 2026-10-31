package dev.phonebot.android

import android.app.Application
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class TaskStep(val id: String, val name: String, val args: String = "", val detail: String = "", val done: Boolean = false, val error: Boolean = false)
data class ChatTurn(val id: String, val prompt: String, val answer: String = "", val steps: List<TaskStep> = emptyList(), val status: String = "running", val image: String = "")
data class ChatSession(val id: String = UUID.randomUUID().toString(), val title: String = "新对话", val updated: Long = System.currentTimeMillis(), val turns: List<ChatTurn> = emptyList())
data class PiProvider(val id: String, val name: String, val count: Int, val configured: Boolean, val auth: List<Pair<String, String>>, val ambient: Boolean)
data class PiModel(val id: String, val name: String, val image: Boolean)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

private fun ChatTurn.event(event: JSONObject): ChatTurn = when (event.optString("type")) {
    "text" -> copy(answer = answer + event.getString("delta"))
    "attachment" -> copy(image = event.getString("path"))
    "step" -> copy(steps = steps.filterNot { it.id == event.getString("id") } + TaskStep(event.getString("id"), event.getString("name"), event.optJSONObject("args")?.toString().orEmpty()))
    "step_result" -> copy(steps = steps.map { if (it.id == event.getString("id")) it.copy(done = true, error = event.optBoolean("error"), detail = event.optString("text")) else it })
    "result" -> copy(answer = event.getString("text"), status = "done")
    "error", "cancelled", "interrupted" -> copy(status = when (event.getString("type")) { "cancelled" -> "stopped"; "interrupted" -> "interrupted"; else -> "error" },
        answer = event.optString("message", "任务已停止").let { if (answer.isBlank()) it else "$answer\n\n$it" })
    else -> this
}

class PhoneBotModel(application: Application) : AndroidViewModel(application) {
    val engine = EngineClient(application)
    private val preferences = application.getSharedPreferences("phonebot", 0)
    private val historyFile = AtomicFile(File(application.filesDir, "chats.json"))
    private val journalDirectory = File(application.filesDir, "task-events")
    var sessions by mutableStateOf(listOf<ChatSession>()); private set
    var selectedSession by mutableStateOf(""); private set
    var provider by mutableStateOf(preferences.getString("provider", "").orEmpty()); private set
    var model by mutableStateOf(preferences.getString("model", "").orEmpty()); private set
    var providers by mutableStateOf(listOf<PiProvider>()); private set
    var models by mutableStateOf(listOf<PiModel>()); private set
    var modelProvider by mutableStateOf(""); private set
    var modelTotal by mutableIntStateOf(0); private set
    var activeTask by mutableStateOf(""); private set
    var permission by mutableStateOf(""); private set
    var permissions by mutableStateOf(PhonePermissions.check(application)); private set
    var authPrompt by mutableStateOf<JSONObject?>(null); private set
    var authEvent by mutableStateOf<JSONObject?>(null); private set
    var notice by mutableStateOf(""); private set
    private var verifiedModels by mutableStateOf(preferences.all.keys.filter { it.startsWith("verified:") }.map { it.removePrefix("verified:") }.toSet())
    private var pending: JSONObject? = null
    private var saveJob: Job? = null
    private val historyMutex = Mutex()
    val session get() = sessions.find { it.id == selectedSession }
    val deviceId: String = preferences.getString("device", null) ?: UUID.randomUUID().toString().also { preferences.edit { putString("device", it) } }

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    var restored = if (!historyFile.baseFile.exists()) emptyList() else JSONArray(String(historyFile.readFully(), Charsets.UTF_8)).objects().map { value ->
                        ChatSession(value.getString("id"), value.getString("title"), value.getLong("updated"), value.getJSONArray("turns").objects().map { turn ->
                            ChatTurn(turn.getString("id"), turn.getString("prompt"), turn.optString("answer"),
                                turn.getJSONArray("steps").objects().map { TaskStep(it.getString("id"), it.getString("name"), it.optString("args"), it.optString("detail"), it.optBoolean("done", true), it.optBoolean("error")) },
                                turn.optString("status").let { if (it == "running") "interrupted" else it }, turn.optString("image"))
                        })
                    }
                    val journal = TaskJournal(journalDirectory)
                    journalDirectory.listFiles()?.filter { it.extension == "jsonl" }?.sortedBy { it.lastModified() }?.forEach { file ->
                        val events = journal.records(file)
                        val start = events.firstOrNull { it.optString("type") == "task_open" } ?: return@forEach
                        val id = start.getString("session")
                        var turn = ChatTurn(start.getString("taskId"), start.getString("prompt"))
                        events.forEach { turn = turn.event(it) }
                        if (turn.status == "running") turn = turn.copy(status = "interrupted")
                        val session = restored.find { it.id == id } ?: ChatSession(id, turn.prompt.take(30), start.optLong("time"))
                        restored = restored.filterNot { it.id == id } + session.copy(turns = session.turns.filterNot { it.id == turn.id } + turn)
                    }
                    restored.sortedByDescending { it.updated }
                }
                sessions = loaded
                selectedSession = loaded.firstOrNull()?.id.orEmpty()
            } catch (_: Exception) { notice = "会话记录读取失败" }
            if (sessions.isEmpty()) newSession()
            engine.start()
        }
        viewModelScope.launch { engine.events.collect(::onEvent) }
        viewModelScope.launch {
            engine.state.collect { state ->
                if (state.canRun) pending?.let { pending = null; engine.command(it) }
                if (state.phase == EnginePhase.FAILED || state.phase == EnginePhase.STOPPED) {
                    if (activeTask.isNotEmpty()) changeTurn { it.copy(status = "interrupted", answer = it.answer.ifEmpty { "引擎已停止" }) }
                    activeTask = ""; permission = ""; authPrompt = null; authEvent = null
                }
            }
        }
    }
    fun refreshPermissions() { permissions = PhonePermissions.check(getApplication()) }
    fun newSession() {
        if (activeTask.isNotEmpty()) return
        val next = ChatSession()
        sessions = listOf(next) + sessions.filter { it.turns.isNotEmpty() }
        selectedSession = next.id
        save()
    }
    fun selectSession(id: String) { if (activeTask.isEmpty()) selectedSession = id }
    fun deleteSession(id: String) {
        if (activeTask.isNotEmpty()) return
        sessions = sessions.filter { it.id != id }
        if (selectedSession == id) selectedSession = sessions.firstOrNull()?.id.orEmpty()
        if (sessions.isEmpty()) newSession() else save()
        viewModelScope.launch(Dispatchers.IO) {
            File(getApplication<Application>().filesDir, "transcripts/$id.json").delete()
            val journal = TaskJournal(journalDirectory)
            journalDirectory.listFiles()?.filter { file -> journal.records(file).firstOrNull()?.optString("session") == id }?.forEach { it.delete() }
        }
    }
    fun choose(providerId: String, modelId: String) {
        provider = providerId; model = modelId
        preferences.edit { putString("provider", provider); putString("model", model) }
    }
    fun verified(providerId: String, modelId: String = model): Boolean = "$providerId:$modelId" in verifiedModels
    fun catalog(providerId: String = "", offset: Int = 0, query: String = "") {
        if (offset == 0) { models = emptyList(); modelProvider = providerId }
        command(JSONObject().put("type", "catalog").put("provider", providerId).put("offset", offset).put("query", query))
    }
    fun login(id: String, auth: String) {
        authEvent = null
        command(JSONObject().put("type", "login").put("provider", id).put("auth", auth).put("deviceId", deviceId))
    }
    fun logout(id: String) = command(JSONObject().put("type", "logout").put("provider", id))
    fun refresh(id: String) = command(JSONObject().put("type", "refresh").put("provider", id))
    fun verify() = command(JSONObject().put("type", "verify").put("provider", provider).put("model", model))
    fun answerAuth(answer: String) {
        val prompt = authPrompt ?: return
        engine.command(JSONObject().put("type", "tool_result").put("id", prompt.getString("id")).put("result", answer))
        authPrompt = null
    }
    fun cancel() { permission = ""; authPrompt = null; authEvent = null; pending = null; engine.cancel() }
    fun resume() { permission = ""; engine.command(JSONObject().put("type", "resume")) }
    fun submit(prompt: String): Boolean {
        if (prompt.isBlank() || activeTask.isNotEmpty()) return false
        if (provider.isEmpty() || model.isEmpty()) { notice = "请先选择服务商和模型"; return false }
        if (!engine.state.value.canRun) { notice = "请等待引擎就绪"; return false }
        if (session == null) newSession()
        activeTask = UUID.randomUUID().toString()
        sessions = sessions.map { if (it.id == selectedSession) it.copy(title = if (it.turns.isEmpty()) prompt.take(30) else it.title,
            updated = System.currentTimeMillis(), turns = it.turns + ChatTurn(activeTask, prompt)) else it }.sortedByDescending { it.updated }
        save()
        engine.command(JSONObject().put("type", "task").put("taskId", activeTask).put("session", selectedSession).put("prompt", prompt).put("provider", provider).put("model", model))
        return true
    }
    private fun command(command: JSONObject) {
        val state = engine.state.value
        if (state.canRun) engine.command(command)
        else if (state.canStart || state.phase == EnginePhase.STARTING) { pending = command; if (state.canStart) engine.start() }
        else if (command.optString("type") == "catalog" && activeTask.isEmpty()) pending = command
        else notice = "请先结束当前操作"
    }
    private fun changeTurn(change: (ChatTurn) -> ChatTurn) {
        sessions = sessions.map { session -> session.copy(turns = session.turns.map { if (it.id == activeTask) change(it) else it }) }
        save()
    }
    private fun onEvent(event: JSONObject) {
        when (event.getString("type")) {
            "task_open" -> {
                activeTask = event.getString("taskId")
                selectedSession = event.getString("session")
                if (sessions.none { it.id == selectedSession }) sessions = listOf(ChatSession(id = selectedSession, title = event.getString("prompt").take(30))) + sessions
                sessions = sessions.map { session -> if (session.id == selectedSession) session.copy(turns = session.turns.filter { it.id != activeTask } + ChatTurn(activeTask, event.getString("prompt"))) else session }
            }
            "catalog" -> {
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
            "permission" -> { permission = event.getString("permission"); refreshPermissions() }
            "text", "attachment", "step", "step_result" -> if (event.optString("taskId") == activeTask) changeTurn { it.event(event) }
            "result" -> if (event.optString("taskId") == activeTask) changeTurn { it.copy(answer = event.getString("text"), status = "done") } else notice = "自检通过"
            "error", "cancelled", "interrupted" -> {
                val message = event.optString("message", "任务已停止")
                if (activeTask.isNotEmpty()) changeTurn { it.event(event) }
                else notice = message
            }
            "idle" -> { activeTask = ""; permission = ""; authPrompt = null; authEvent = null }
        }
    }
    fun clearNotice() { notice = "" }
    private fun save() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(200)
            val value = JSONArray(sessions.map { session -> JSONObject().put("id", session.id).put("title", session.title).put("updated", session.updated)
                .put("turns", JSONArray(session.turns.map { turn -> JSONObject().put("id", turn.id).put("prompt", turn.prompt).put("answer", turn.answer).put("status", turn.status).put("image", turn.image)
                    .put("steps", JSONArray(turn.steps.map { step -> JSONObject().put("id", step.id).put("name", step.name).put("args", step.args).put("detail", step.detail).put("done", step.done).put("error", step.error) })) })) }).toString()
            try {
                withContext(Dispatchers.IO) {
                    historyMutex.withLock {
                        val stream = historyFile.startWrite()
                        try { stream.write(value.toByteArray(Charsets.UTF_8)); historyFile.finishWrite(stream) }
                        catch (error: Exception) { historyFile.failWrite(stream); throw error }
                    }
                }
            } catch (error: java.io.IOException) { notice = "会话保存失败：${error.message}" }
        }
    }
    override fun onCleared() { engine.detach(); super.onCleared() }
}
