package dev.phonebot.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mikepenz.markdown.model.parseMarkdown
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionSettingsTest {
    @Test fun sessionChoicesPersistAndNewSessionsInheritDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-test-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val application = object : Application() {
            init { attachBaseContext(object : ContextWrapper(context) {
                override fun getFilesDir() = directory
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$name-${directory.name}", mode)
            }) }
        }
        val saved = File(directory, "chats.json")
        val turns = JSONArray()
        repeat(150) { index -> turns.put(JSONObject().put("id", "turn-$index").put("prompt", "你好 $index").put("answer", "**结果** $index").put("status", "done")
            .put("steps", JSONArray().apply { repeat(50) { step -> put(JSONObject().put("id", "step-$step").put("name", "读屏").put("done", true).put("detail", "页面观察。".repeat(100))) } })) }
        saved.writeText(JSONArray().put(JSONObject().put("id", "existing").put("title", "历史会话").put("updated", 1).put("turns", turns)).toString())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var store = ViewModelStore()
        lateinit var model: PhoneBotModel
        fun create() = instrumentation.runOnMainSync { model = PhoneBotModel(application); store.put("session", model) }
        fun await(check: () -> Boolean) {
            val end = android.os.SystemClock.uptimeMillis() + 5000
            var matched = false
            while (!matched && android.os.SystemClock.uptimeMillis() < end) {
                instrumentation.runOnMainSync { matched = check() }
                if (!matched) android.os.SystemClock.sleep(20)
            }
            assertTrue("session state did not settle", matched)
        }
        try {
            create()
            await { model.session?.id == "existing" }
            repeat(4) { version ->
                instrumentation.runOnMainSync { model.editBot("保存版本 $version") }
                android.os.SystemClock.sleep(220)
            }
            await { saved.readText().contains("保存版本 3") }
            instrumentation.runOnMainSync {
                assertEquals("main", model.session?.screen)
                assertEquals("shizuku", model.session?.helper)
                model.choosePhone("builtin", "virtual")
                model.choose("provider-a", "model-a")
                model.editBot("音乐搭档", 0xFF8B5CF6)
                model.savePhoneDefaults()
                model.newSession()
                assertEquals(2, model.sessions.size)
                model.editBot("阅读搭档", 0xFF10B981)
                model.choose("provider-b", "model-b")
                assertEquals("virtual", model.session?.screen)
                assertEquals("builtin", model.session?.helper)
                model.choosePhone("shizuku", "main")
                model.selectSession("existing")
                assertEquals(150, model.session?.turns?.size)
                assertEquals(50, model.session?.turns?.last()?.steps?.size)
                assertEquals("音乐搭档", model.session?.title)
                assertEquals("provider-a", model.provider)
                assertEquals("model-a", model.model)
                assertEquals(0xFF8B5CF6, model.session?.avatarColor)
                assertEquals("virtual", model.session?.screen)
                assertEquals("builtin", model.session?.helper)
            }
            await { saved.readText().contains("\"helper\":\"builtin\"") }
            instrumentation.runOnMainSync { store.clear(); store = ViewModelStore() }
            create()
            await { model.sessions.any { it.id == "existing" && it.helper == "builtin" && it.screen == "virtual" } }
            instrumentation.runOnMainSync {
                assertEquals(2, model.sessions.size)
                val reader = model.sessions.first { it.title == "阅读搭档" }
                model.selectSession(reader.id)
                assertEquals("provider-b", model.provider)
                assertEquals("model-b", model.model)
                assertEquals(0xFF10B981, model.session?.avatarColor)
                model.newSession()
                assertEquals("builtin", model.session?.helper)
                assertEquals("virtual", model.session?.screen)
                model.markdown["existing/turn-149/reply/"] = "**结果** 149" to parseMarkdown("**结果** 149")
                model.submissions["existing"] = "turn-149"
                model.deleteSession("existing")
                assertFalse(model.markdown.keys.any { it.startsWith("existing/") })
                assertFalse(model.submissions.containsKey("existing"))
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            application.getSharedPreferences("phonebot", 0).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}
