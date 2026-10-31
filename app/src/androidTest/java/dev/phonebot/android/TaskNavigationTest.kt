package dev.phonebot.android

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.engine.TaskJournal
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskNavigationTest {
    @Test fun notificationOpensExactTaskOnColdAndWarmActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "task-events")
        val journal = TaskJournal(directory)
        val ids = listOf("navigation-one", "navigation-two")
        ids.forEachIndexed { index, id ->
            File(directory, "$id.jsonl").delete()
            journal.append(JSONObject().put("type", "task_open").put("taskId", id).put("session", id).put("prompt", "跳转测试 $index"))
            journal.append(JSONObject().put("type", "result").put("taskId", id).put("text", "**结果 $index**"))
        }
        fun intent(id: String) = Intent(context, MainActivity::class.java).putExtra("session", id).putExtra("task", id)
        val scenario = ActivityScenario.launch<MainActivity>(intent(ids[0]))
        fun await(id: String) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline) {
                var selected = ""
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[PhoneBotModel::class.java]
                    if (model.session?.turns?.any { it.id == id } == true) selected = model.selectedSession
                    if (selected == id) assertEquals(id, model.focusedTask)
                }
                if (selected == id) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Task $id did not open")
        }
        try {
            await(ids[0])
            context.startActivity(intent(ids[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await(ids[1])
        } finally {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[PhoneBotModel::class.java]
                ids.forEach(model::deleteSession)
            }
            SystemClock.sleep(400)
            scenario.close()
            ids.forEach { File(directory, "$it.jsonl").delete() }
        }
    }
}
