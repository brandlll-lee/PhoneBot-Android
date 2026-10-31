package dev.phonebot.android.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.json.JSONObject

class TaskJournal(private val directory: File) {
    private val secrets = mutableSetOf<String>()
    private val terminals = setOf("result", "error", "cancelled", "interrupted")

    @Synchronized fun remember(value: Any?) {
        when (value) {
            is String -> if (value.length >= 6) secrets += value
            is JSONObject -> value.keys().forEach { remember(value.opt(it)) }
        }
    }

    @Synchronized fun clean(event: JSONObject): JSONObject {
        fun scrub(value: Any?, key: String = ""): Any {
            if (key.lowercase() in setOf("apikey", "api_key", "authorization", "accesstoken", "refreshtoken", "password", "credential", "headers")) return "[redacted]"
            return when (value) {
                is JSONObject -> JSONObject().also { out -> value.keys().forEach { out.put(it, scrub(value.opt(it), it)) } }
                is org.json.JSONArray -> org.json.JSONArray((0 until value.length()).map { scrub(value.opt(it)) })
                is String -> secrets.fold(value) { text, secret -> text.replace(secret, "[redacted]") }
                null -> JSONObject.NULL
                else -> value
            }
        }
        return scrub(event) as JSONObject
    }

    @Synchronized fun append(event: JSONObject): JSONObject {
        val safe = clean(event)
        val id = safe.optString("taskId")
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "任务编号无效" }
        directory.mkdirs()
        safe.put("time", System.currentTimeMillis())
        val file = File(directory, "$id.jsonl")
        if (file.exists()) RandomAccessFile(file, "rw").use { stored ->
            var end = stored.length()
            while (end > 0) { stored.seek(end - 1); if (stored.read() == 10) break; end-- }
            if (end != stored.length()) stored.setLength(end)
        }
        FileOutputStream(file, true).use { stream -> stream.write((safe.toString() + "\n").toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        return safe
    }

    fun records(file: File): List<JSONObject> = if (!file.exists()) emptyList() else file.useLines { lines -> lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList() }

    @Synchronized fun recover() {
        directory.listFiles()?.filter { it.extension == "jsonl" }?.forEach { file ->
            val events = records(file)
            if (events.isNotEmpty() && events.none { it.optString("type") in terminals }) {
                append(JSONObject().put("type", "interrupted").put("taskId", file.nameWithoutExtension).put("message", "引擎意外中断"))
            }
        }
    }
}
