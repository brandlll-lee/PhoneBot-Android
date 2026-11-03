package dev.phonebot.android.engine

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PiSessionTest {
    @Test fun migrationCompactionToolsAndContinuation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val home = File(context.cacheDir, "pi-session-test").apply { deleteRecursively(); mkdirs() }
        val bundle = File(home, "engine.mjs")
        context.assets.open("runtime/engine.mjs").use { source -> bundle.outputStream().use { source.copyTo(it) } }
        val wrapper = File(home, "fixture.mjs").apply { writeText("""
            let turn = 0;
            globalThis.fetch = async (url, options) => {
              const body = JSON.parse(options.body);
              const delta = !body.tools?.length ? {content:'Previous request ended: not found; user stopped.'}
                : ++turn === 1 ? {tool_calls:[{index:0,id:'read',type:'function',function:{name:'screen_read',arguments:'{}'}}]}
                : {content:'Current page received.'};
              if (body.tools?.length && body.tools.length !== 21) throw new Error('Missing phone tools');
              const chunk = {id:'fixture',choices:[{index:0,delta,finish_reason:null}]};
              const end = {id:'fixture',choices:[{index:0,delta:{},finish_reason:delta.tool_calls?'tool_calls':'stop'}]};
              return new Response('data: '+JSON.stringify(chunk)+'\n\ndata: '+JSON.stringify(end)+'\n\ndata: [DONE]\n\n',
                {headers:{'Content-Type':'text/event-stream'}});
            };
            await import('./engine.mjs');
        """.trimIndent()) }
        val old = File(home, "transcripts/conversation.json").apply { parentFile!!.mkdirs() }
        val history = JSONArray()
        repeat(4) { i ->
            history.put(JSONObject().put("role", "user").put("content", "Previous request $i").put("timestamp", i + 1))
            history.put(JSONObject().put("role", "assistant").put("api", "openai-completions").put("provider", "deepseek")
                .put("model", "deepseek-flash").put("stopReason", "stop").put("timestamp", i + 1)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Stopped. " + "Page text. ".repeat(40000))))
                .put("usage", JSONObject().put("input", 990000).put("output", 20).put("cacheRead", 0).put("cacheWrite", 0).put("totalTokens", 990020)
                    .put("cost", JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0).put("total", 0))))
        }
        old.writeText(history.toString())
        val incoming = ParcelFileDescriptor.createPipe()
        val outgoing = ParcelFileDescriptor.createPipe()
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(incoming[1])
        val events = LinkedBlockingQueue<JSONObject>()
        val credentials = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        fun send(value: JSONObject) = synchronized(writer) { writer.write((value.toString() + "\n").toByteArray()); writer.flush() }
        val reader = Thread {
            ParcelFileDescriptor.AutoCloseInputStream(outgoing[0]).bufferedReader().useLines { lines -> lines.forEach { line ->
                val event = JSONObject(line)
                if (event.optString("type") == "tool") {
                    val result: Any = when (event.getString("name")) {
                        "vault_read" -> credentials[event.getJSONObject("params").getString("provider")] ?: JSONObject.NULL
                        "vault_list" -> JSONArray(credentials.keys.map { JSONObject().put("providerId", it) })
                        "vault_write" -> { credentials[event.getJSONObject("params").getString("provider")] = event.getJSONObject("params").getJSONObject("credential"); true }
                        "vault_delete" -> { credentials.remove(event.getJSONObject("params").getString("provider")); true }
                        "auth_prompt" -> { assertEquals("secret", event.getJSONObject("params").getString("type")); "fixture-key" }
                        "screen_read" -> JSONObject().put("kind", "full").put("snapshot", 1).put("elements", JSONArray())
                        else -> throw AssertionError(event.toString())
                    }
                    send(JSONObject().put("type", "tool_result").put("id", event.getString("id")).put("result", result))
                } else events.add(event)
            } }
        }.apply { start() }
        var exit = -1
        val node = Thread { exit = NodeRuntime.start(wrapper.path, home.path, home.path, incoming[0].detachFd(), outgoing[1].detachFd()) }.apply { start() }
        fun event(type: String): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val next = events.poll(250, TimeUnit.MILLISECONDS) ?: continue
                assertFalse(next.toString(), next.optString("type") == "error")
                if (next.optString("type") == type) return next
            }
            throw AssertionError("Missing $type; node alive=${node.isAlive}")
        }
        fun task(prompt: String) = send(JSONObject().put("type", "task").put("session", "conversation")
            .put("provider", "deepseek").put("model", "deepseek-flash").put("prompt", prompt))
        try {
            event("ready")
            send(JSONObject().put("type", "login").put("provider", "deepseek").put("auth", "api_key"))
            assertTrue(event("account").getBoolean("configured"))
            assertEquals("fixture-key", credentials["deepseek"]!!.getString("key"))
            event("idle")
            task("Read the current page")
            assertFalse(event("compaction_end").getBoolean("aborted"))
            assertEquals("Current page received.", event("result").getString("text"))
            event("idle")
            assertFalse(old.exists())
            val saved = File(home, "sessions/conversation.jsonl")
            val entries = saved.readLines().map(::JSONObject)
            assertTrue(entries.any { it.optString("type") == "compaction" })
            assertTrue(entries.any { it.optJSONObject("message")?.optString("role") == "toolResult" })
            task("Continue from the previous result")
            assertEquals("Current page received.", event("result").getString("text"))
            event("idle")
            send(JSONObject().put("type", "logout").put("provider", "deepseek"))
            assertFalse(event("account").getBoolean("configured"))
            assertTrue(credentials.isEmpty())
            event("idle")
        } finally {
            send(JSONObject().put("type", "stop"))
            writer.close()
            node.join(10000)
            reader.join(1000)
            home.deleteRecursively()
        }
        assertFalse("Node did not stop", node.isAlive)
        assertEquals(0, exit)
    }
}
