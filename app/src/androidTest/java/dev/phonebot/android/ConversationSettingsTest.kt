package dev.phonebot.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import dev.phonebot.android.engine.TaskJournal
import androidx.test.filters.SdkSuppress
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class ConversationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsKeepTheirPositionDuringBoundaryScrollsAndDrags() {
        val frames = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(longArrayOf(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION), metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION),
                metrics.getMetric(android.view.FrameMetrics.DEADLINE)))
        }
        compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
        compose.onNodeWithTag("chat_identity").performClick()
        val title = compose.onNodeWithText("Bot 设置")
        val initial = title.getUnclippedBoundsInRoot().top
        val sheet = compose.onNodeWithTag("settings_sheet")
        repeat(6) { sheet.performTouchInput { swipeUp(durationMillis = 180) } }
        repeat(6) { sheet.performTouchInput { swipeDown(durationMillis = 180) } }
        title.performTouchInput { swipeDown(durationMillis = 180) }
        compose.mainClock.autoAdvance = false
        try {
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals(initial.value, title.getUnclippedBoundsInRoot().top.value, .5f)
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithContentDescription("关闭设置").performClick()
        compose.onNodeWithContentDescription("更多选项").performClick()
        compose.onNodeWithText("设置").performClick()
        val settings = compose.onNodeWithText("设置")
        val top = settings.getUnclippedBoundsInRoot().top
        repeat(4) { sheet.performTouchInput { swipeUp(durationMillis = 180) } }
        assertEquals(top.value, settings.getUnclippedBoundsInRoot().top.value, .5f)
        val close = compose.onAllNodesWithContentDescription("关闭设置")
        if (close.fetchSemanticsNodes().isNotEmpty()) close.onFirst().performClick()
        else compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
        val samples = synchronized(frames) { frames.toList() }
        assertTrue(samples.isNotEmpty())
        val summary = (0..2).map { column -> samples.map { it[column] / 1_000_000.0 }.sorted().let {
            "p50=${it[it.size / 2]} p95=${it[((it.size - 1) * .95).toInt()]} max=${it.last()}"
        } }
        val report = "frames=${samples.size} refreshHz=${compose.activity.display?.refreshRate} total/layout/draw(ms)=$summary overDeadline=${samples.count { it[3] > 0 && it[0] > it[3] }}"
        File(compose.activity.cacheDir, "settings-frame-timing.txt").writeText(report)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("settingsFrames", report) })
    }

    @Test fun floatingChatScrollsUnderControlsInBothThemes() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        compose.waitUntil(10000) { model.session != null }
        val original = model.selectedSession
        val appearance = model.appearance
        val id = "layout-${java.util.UUID.randomUUID()}"
        val directory = File(compose.activity.filesDir, "task-events")
        val journal = TaskJournal(directory)
        repeat(24) { index ->
            journal.append(JSONObject().put("type", "task_open").put("taskId", "$id-$index").put("session", id)
                .put("botName", "音乐搭档").put("avatarColor", 0xFF8B5CF6).put("prompt", "查看第 $index 项任务"))
            journal.append(JSONObject().put("type", "result").put("taskId", "$id-$index")
                .put("text", "**第 $index 项结果**\n\n页面、输入与执行记录均已保存。可以继续查看，也可以交代下一项任务。"))
        }
        try {
            compose.runOnIdle { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
            compose.waitUntil(10000) { model.sessions.any { it.id == id } }
            compose.runOnIdle { model.selectSession(id) }
            compose.onAllNodesWithText("第 23 项结果", substring = true).onFirst().assertIsDisplayed()
            compose.onNodeWithTag("chat_messages").performScrollToNode(hasText("第 12 项结果", substring = true))
            compose.runOnIdle { model.selectSession(original) }
            compose.runOnIdle { model.selectSession(id) }
            compose.onAllNodesWithText("第 23 项结果", substring = true).onFirst().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
            compose.onAllNodesWithText("第 23 项结果", substring = true).onFirst().assertIsDisplayed()
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            fun frames(command: String): String = automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            frames("dumpsys gfxinfo ${compose.activity.packageName} reset")
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK)) {
                compose.runOnIdle { model.chooseAppearance(mode) }
                val list = compose.onNodeWithTag("chat_messages")
                repeat(4) { list.performTouchInput { swipeDown(durationMillis = 300) } }
                repeat(4) { list.performTouchInput { swipeUp(durationMillis = 300) } }
                list.performScrollToNode(hasText("第 23 项结果", substring = true))
                compose.onNodeWithTag("chat_identity").assertIsDisplayed()
                compose.onNodeWithTag("task_input").assertIsDisplayed()
                File(compose.activity.cacheDir, "floating-chat-${mode.name.lowercase()}.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            File(compose.activity.cacheDir, "floating-chat-frames.txt").writeText(frames("dumpsys gfxinfo ${compose.activity.packageName} framestats"))
            compose.onNodeWithTag("task_input").performClick().performTextInput("键盘与浮层布局检查")
            compose.onNodeWithTag("task_input").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.deleteSession(id); model.selectSession(original); model.chooseAppearance(appearance) }
            repeat(24) { File(directory, "$id-$it.jsonl").delete() }
        }
    }

    @Test fun contactsKeepSeparateDraftsAndNames() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        compose.waitUntil(10000) { model.session != null }
        val original = model.selectedSession
        var first = ""
        var second = ""
        try {
            compose.runOnIdle { model.newSession(); first = model.selectedSession; model.editBot("音乐搭档", 0xFF8B5CF6) }
            compose.onNodeWithTag("task_input").performTextInput("第一位搭档的草稿")
            compose.runOnIdle { model.newSession(); second = model.selectedSession; model.editBot("阅读搭档", 0xFF10B981) }
            compose.onNodeWithTag("task_input").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNodeWithTag("task_input").performTextInput("第二位搭档的草稿")
            compose.runOnIdle { model.selectSession(first) }
            compose.onNodeWithTag("task_input").assertTextEquals("第一位搭档的草稿")
            compose.onNodeWithTag("chat_identity").assertTextContains("音乐搭档")
            compose.onNodeWithContentDescription("打开对话列表").performClick()
            compose.onNodeWithText("阅读搭档").performClick()
            compose.onNodeWithTag("task_input").assertTextEquals("第二位搭档的草稿")
            compose.onNodeWithTag("screen_selector").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择模型").assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                if (first.isNotEmpty()) model.deleteSession(first)
                if (second.isNotEmpty()) model.deleteSession(second)
                model.selectSession(original)
            }
        }
    }

    @Test fun appearanceChangesPersistAndUpdateSystemBars() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        val original = model.appearance
        try {
            compose.onNodeWithContentDescription("更多选项").performClick()
            compose.onNodeWithText("设置").performClick()
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK, Appearance.SYSTEM)) {
                compose.onNode(hasText("外观") and hasClickAction()).performClick()
                compose.onAllNodes(hasText(mode.label) and hasClickAction()).onLast().performClick()
                compose.runOnIdle {
                    assertEquals(mode, model.appearance)
                    assertEquals(mode.name, compose.activity.getSharedPreferences("phonebot", 0).getString("appearance", null))
                    if (mode != Appearance.SYSTEM) assertEquals(mode == Appearance.LIGHT,
                        androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        } finally { compose.runOnIdle { model.chooseAppearance(original) } }
    }

    @Test fun settingsMenusShareThemeAndNavigation() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        val original = model.appearance
        try {
            compose.onNodeWithContentDescription("更多选项").performClick()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("启动引擎").assertDoesNotExist()
            compose.onNodeWithText("运行自检").assertDoesNotExist()
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK)) {
                compose.runOnIdle { model.chooseAppearance(mode) }
                fun capture(name: String) {
                    compose.waitForIdle()
                    val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    val bitmap = checkNotNull(automation.takeScreenshot())
                    try { File(compose.activity.cacheDir, "settings-$name-${mode.name.lowercase()}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { bitmap.recycle() }
                }
                capture("home")
                compose.onNode(hasText("外观") and hasClickAction()).performClick()
                compose.onNodeWithContentDescription("已选择").assertExists()
                capture("menu")
                compose.onAllNodesWithText(mode.label).onLast().assertIsDisplayed()
                compose.onAllNodes(hasText(mode.label) and hasClickAction()).onLast().performClick()
                compose.onNodeWithText("关于 PhoneBot").performClick()
                compose.onNodeWithText("版本").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText("通知").performClick()
                compose.onNodeWithText("允许通知").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText("手机控制").performClick()
                compose.onNodeWithText("连接状态").assertIsDisplayed()
                if (model.helperStates[model.session?.helper]?.optString("state") in setOf("ready", "running")) {
                    compose.onNodeWithText("配对端口").assertDoesNotExist()
                    compose.onNodeWithText("六位配对码").assertDoesNotExist()
                }
                capture("control")
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText("排查问题").performScrollTo().performClick()
                compose.onNodeWithText("运行自检").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick()
            }
        } finally { compose.runOnIdle { model.chooseAppearance(original) } }
    }

    @Test fun phoneControlShowsOnlyTheRequiredConnectionStep() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        compose.onNodeWithTag("chat_identity").performClick()
        compose.onNodeWithText("连接和授权").performScrollTo().performClick()
        compose.onNode(hasText("控制方式") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("内置连接") and hasClickAction()).onLast().performClick()
        compose.waitUntil(15000) { model.engine.state.value.canRun }
        compose.runOnIdle { model.engine.detach() }
        compose.waitForIdle()
        val original = model.helperStates["builtin"]
        try {
            compose.runOnIdle { model.helperStates["builtin"] = JSONObject().put("state", "not_paired").put("paired", false) }
            compose.onNodeWithText("配对端口").performScrollTo().performTextInput("12345")
            compose.onNodeWithText("六位配对码").performScrollTo().performTextInput("123456")
            compose.onNodeWithText("连接", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
            compose.runOnIdle { model.helperStates["builtin"] = JSONObject().put("state", "disconnected").put("paired", true) }
            compose.onNodeWithText("配对端口").assertDoesNotExist()
            compose.onNodeWithText("重新连接").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { model.helperStates["builtin"] = JSONObject().put("state", "ready").put("paired", true) }
            compose.onNodeWithText("重新连接").assertDoesNotExist()
            compose.onNodeWithText("六位配对码").assertDoesNotExist()
            compose.runOnIdle { model.helperStates["builtin"] = JSONObject().put("state", "permission_required").put("paired", true).put("message", "请允许本地网络访问") }
            compose.onNodeWithText("请允许本地网络访问").performScrollTo().assertIsDisplayed()
        } finally { compose.runOnIdle {
            if (original == null) model.helperStates.remove("builtin") else model.helperStates["builtin"] = original
            model.engine.attach()
        } }
    }

    @Test fun settingsRowsChangeTheSessionHelperAndScreen() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        compose.waitUntil(10000) { model.session != null }
        val original = checkNotNull(model.session)
        assertEquals("", model.activeTask)
        try {
            compose.onNodeWithTag("chat_identity").performClick()
            for ((label, id) in listOf("内置连接" to "builtin", "Shizuku" to "shizuku")) {
                compose.onNode(hasText("控制方式") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
                compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
                compose.runOnIdle { assertEquals(id, model.session?.helper) }
            }
            for ((label, id) in listOf("手机屏幕" to "main", "独立屏幕" to "virtual")) {
                compose.onNode(hasText("工作屏幕") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
                compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
                compose.runOnIdle { assertEquals(id, model.session?.screen) }
            }
            compose.onNodeWithText("连接和授权").performScrollTo().performClick()
            compose.onNodeWithText("手机控制").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("Bot 设置").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("chat_identity").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.choosePhone(original.helper, original.screen) }
            compose.waitUntil(5000) {
                runCatching {
                    JSONArray(File(compose.activity.filesDir, "chats.json").readText()).objects()
                        .first { it.getString("id") == original.id }
                        .let { it.getString("helper") == original.helper && it.getString("screen") == original.screen }
                }.getOrDefault(false)
            }
        }
    }
}
