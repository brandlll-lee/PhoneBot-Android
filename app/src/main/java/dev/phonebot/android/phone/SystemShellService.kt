package dev.phonebot.android.phone

import android.os.Process
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.system.exitProcess

class SystemShellService : ISystemShell.Stub() {
    @Volatile private var process: java.lang.Process? = null

    override fun execute(command: String): String {
        require(command.isNotBlank() && command.length <= 4000 && '\u0000' !in command)
        val child = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        process = child
        val output = StringBuilder()
        var truncated = false
        var outputError: IOException? = null
        val reader = Thread({
            try { child.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val keep = minOf(count, 32768 - output.length)
                        output.append(buffer, 0, keep)
                        if (keep < count) truncated = true
                    }
                }
            } } catch (error: IOException) { synchronized(output) { outputError = error } }
        }, "shell-output").apply { start() }
        try {
            val ended = child.waitFor(10, TimeUnit.SECONDS)
            if (!ended) child.destroyForcibly()
            reader.join(1000)
            return synchronized(output) { JSONObject().put("ok", ended && child.exitValue() == 0 && outputError == null)
                .put("exitCode", if (ended) child.exitValue() else -1).put("timedOut", !ended).put("uid", Process.myUid())
                .put("output", output.toString()).put("outputError", outputError?.message).put("truncated", truncated).toString() }
        } finally { child.destroy(); process = null }
    }
    override fun cancel() { process?.destroyForcibly() }
    override fun destroy() { cancel(); exitProcess(0) }
}
