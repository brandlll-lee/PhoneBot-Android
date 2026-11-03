package dev.phonebot.android.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.TextAttribute
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal class PhoneActions(private val service: PhoneAccessibilityService, private val screen: ScreenState, private val target: PhoneDisplay) {
    private val actions = setOf("app_launch", "app_send_intent", "phone_open_link", "tap", "swipe", "input_text", "back", "home")

    private suspend fun settle(expected: JSONObject?, timeout: Long, packageName: String?, dialogWindow: Int?, before: String, conditionBefore: Boolean, inputStop: String?): JSONObject {
        val began = SystemClock.uptimeMillis()
        var previous = ""
        var repeats = 0
        var matches = 0
        while (true) {
            coroutineContext.ensureActive()
            check(service.available(target)) { "执行屏幕不可用，请检查连接和锁屏状态" }
            screen.scan()
            val fingerprint = screen.fingerprint()
            repeats = if (fingerprint.isNotEmpty() && fingerprint == previous) repeats + 1 else 0
            val stable = repeats >= 2 && SystemClock.uptimeMillis() - service.lastScreenEvent(target.displayId) >= 250
            val met = fingerprint.isNotEmpty() && expected?.let(screen::condition) == true
            matches = if (met) matches + 1 else 0
            val changed = fingerprint.isNotEmpty() && fingerprint != before
            val expired = SystemClock.uptimeMillis() - began >= timeout
            val popup = dialog() && screen.root?.windowId != dialogWindow
            val switched = fingerprint.isNotEmpty() && packageName != null && screen.latest.optString("package") != packageName &&
                expected?.optString("package") != screen.latest.optString("package")
            val reason = when {
                popup -> "dialog"
                switched -> "navigation"
                inputStop != null -> inputStop
                met && (stable || !conditionBefore && matches >= 2) -> if (conditionBefore && !changed) "condition_unchanged" else "condition_met"
                expected == null && stable -> "tree_stable"
                expired -> if (fingerprint.isEmpty()) "tree_unreadable" else "timeout"
                else -> null
            }
            if (reason != null) return JSONObject()
                .put("treeStable", stable).put("conditionMet", if (expected == null) JSONObject.NULL else met)
                .put("conditionBefore", if (expected == null) JSONObject.NULL else conditionBefore).put("changed", changed)
                .put("waitMs", SystemClock.uptimeMillis() - began)
                .put("stopReason", reason)
            previous = fingerprint
            delay(120)
        }
    }

    suspend fun execute(name: String, params: JSONObject, report: (JSONObject) -> Unit): JSONObject {
        if (name != "do_steps") {
            val result = step(name, params, params.optJSONObject("expect"))
            val view = screen.present(false)
            result.keys().forEach { view.put(it, result.get(it)) }
            return view
        }
        val steps = params.getJSONArray("steps")
        require(steps.length() in 1..12) { "连续操作需要 1 到 12 步" }
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            require(step.getString("tool") in actions) { "连续操作包含未知工具" }
        }
        val results = JSONArray()
        var stopped = false
        var remainingMs = 90000L
        for (i in 0 until steps.length()) {
            coroutineContext.ensureActive()
            val spec = steps.getJSONObject(i)
            val tool = spec.getString("tool")
            val args = spec.getJSONObject("args")
            val started = SystemClock.elapsedRealtime()
            report(JSONObject().put("type", "step").put("id", "batch-$i").put("name", tool).put("args", args))
            val result = JSONObject().put("index", i).put("tool", tool)
            try {
                val outcome = step(tool, args, args.optJSONObject("expect"), remainingMs)
                remainingMs -= outcome.getLong("executionMs")
                outcome.keys().forEach { result.put(it, outcome.get(it)) }
                stopped = !outcome.getBoolean("canContinue")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { result.put("ok", false).put("error", error.message); stopped = true }
            result.put("durationMs", SystemClock.elapsedRealtime() - started)
            results.put(result)
            report(JSONObject().put("type", "step_result").put("id", "batch-$i").put("error", !result.optBoolean("ok")).put("text", result.toString()))
            if (stopped) break
        }
        return JSONObject().put("ok", (0 until results.length()).all { results.getJSONObject(it).optBoolean("ok") })
            .put("canContinue", !stopped).put("steps", results).put("remaining", steps.length() - results.length()).put("screen", screen.present(false))
    }

    private suspend fun step(name: String, params: JSONObject, expected: JSONObject?, budgetMs: Long = 30000): JSONObject {
        when (name) {
            "app_launch" -> {
                val name = params.getString("package")
                require(name.matches(Regex("[a-zA-Z0-9_.]+"))) { "应用包名无效" }
                target.reserve(service.packageManager.getLaunchIntentForPackage(name) ?: error("找不到可启动的应用"), screen.taskId)
            }
            "app_send_intent", "phone_open_link" -> target.reserve(PhoneIntents.build(name, params), screen.taskId)
            else -> { screen.scan(); target.observePackage(screen.latest.optString("package"), screen.taskId) }
        }
        val began = SystemClock.elapsedRealtime()
        return withTimeout(minOf(30000, budgetMs)) { perform(name, params, expected) }
            .put("executionMs", SystemClock.elapsedRealtime() - began)
    }

    private suspend fun perform(name: String, params: JSONObject, expected: JSONObject?): JSONObject {
        val target = expected ?: if (name == "app_launch") JSONObject().put("package", params.getString("package")) else null
        val timeout = params.optLong("timeoutMs", if (target == null) 2500 else 8000)
        require(timeout in 250..15000) { "等待上限需为 250 到 15000 毫秒" }
        screen.scan()
        val dialogWindow = screen.root?.windowId?.takeIf { dialog() }
        val before = screen.latest.optString("package").takeUnless { it == "null" || it.isEmpty() }
        val fingerprint = screen.fingerprint()
        val conditionBefore = target?.let(screen::condition) == true
        val display = this.target.takeIf { it.virtual }
        val tasksBefore = display?.tasks().orEmpty()
        val input = act(name, params)
        val inputStop = if (input.has("inputVerified") && !input.optBoolean("inputVerified")) {
            if (input.isNull("inputVerified")) "input_unknown" else "input_mismatch"
        } else null
        val result = settle(target, timeout, before.takeIf { name !in setOf("app_launch", "home", "back") }, dialogWindow, fingerprint, conditionBefore, inputStop)
        input.keys().forEach { result.put(it, input.get(it)) }
        val reason = result.getString("stopReason")
        val changedApp = before != null && screen.latest.optString("package") != before
        if (display != null) {
            val agentBefore = tasksBefore.filter { it.optInt("displayId") == display.displayId }.map { it.getInt("taskId") }.toSet()
            val agentPackages = tasksBefore.filter { it.optInt("displayId") == display.displayId }.map { it.getString("package") }.toSet()
            if (display.tasks().any { it.optInt("displayId") == 0 && it.optBoolean("visible") &&
                    (it.getInt("taskId") in agentBefore || it.getString("package") in agentPackages) }) {
                display.pauseReason = "应用跳转到主屏，任务已暂停。请处理主屏页面后继续，或停止任务。"
                return result.put("executed", true).put("ok", true).put("canContinue", false).put("stopReason", "main_screen_changed")
            }
        }
        return result.put("executed", true).put("ok", true)
            .put("navigation", if (!changedApp) "none" else if (reason == "navigation") "unplanned" else "expected")
            .put("canContinue", inputStop == null && (reason == "condition_met" || reason == "tree_stable" && name in setOf("input_text", "home", "back")))
    }

    private fun dialog(): Boolean {
        val root = screen.root ?: return false
        return root.className?.contains("Dialog", true) == true || root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").isNotEmpty()
    }

    private suspend fun act(name: String, params: JSONObject): JSONObject {
        coroutineContext.ensureActive()
        check(service.available(target)) { "执行屏幕不可用，请检查连接和锁屏状态" }
        require(name in actions) { "工具不存在" }
        when (name) {
            "app_launch" -> {
                val target = params.getString("package")
                require(target.matches(Regex("[a-zA-Z0-9_.]+"))) { "应用包名无效" }
                val intent = service.packageManager.getLaunchIntentForPackage(target) ?: error("找不到可启动的应用")
                this.target.launch(intent, screen.taskId)
            }
            "app_send_intent", "phone_open_link" -> {
                val intent = PhoneIntents.build(name, params)
                target.launch(intent, screen.taskId)
            }
            "tap" -> {
                val node = if (params.has("element") || params.has("text") || params.has("resourceId")) screen.resolve(params) else null
                if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
                    val point = if (bounds != null) screen.geometry.point(bounds.exactCenterX().toInt().toDouble(), bounds.exactCenterY().toInt().toDouble())
                        else screen.geometry.point(params.getDouble("x"), params.getDouble("y"))
                    if (target.virtual) target.touch(point.first, point.second, point.first, point.second, 80)
                    else service.gesture(Path().apply { moveTo(point.first, point.second) }, 80)
                }
            }
            "swipe" -> {
                val geometry = screen.geometry
                val direction = params.optString("direction")
                val points = when (direction) {
                    "up" -> listOf(.5, .75, .5, .25)
                    "down" -> listOf(.5, .25, .5, .75)
                    "left" -> listOf(.75, .5, .25, .5)
                    "right" -> listOf(.25, .5, .75, .5)
                    "" -> null
                    else -> error("滑动方向无效")
                }
                val start = if (points != null) geometry.point((geometry.width * points[0]).toInt().toDouble(), (geometry.height * points[1]).toInt().toDouble()) else geometry.point(params.getDouble("x"), params.getDouble("y"))
                val end = if (points != null) geometry.point((geometry.width * points[2]).toInt().toDouble(), (geometry.height * points[3]).toInt().toDouble()) else geometry.point(params.getDouble("endX"), params.getDouble("endY"))
                val duration = params.optLong("duration", 400)
                require(duration in 100..2000) { "滑动时间无效" }
                if (target.virtual) target.touch(start.first, start.second, end.first, end.second, duration)
                else service.gesture(Path().apply { moveTo(start.first, start.second); lineTo(end.first, end.second) }, duration)
            }
            "input_text" -> {
                val node = if (params.has("element") || params.has("target") || params.has("resourceId")) {
                    val target = JSONObject(params.toString()).apply { remove("text"); if (has("target")) put("text", getString("target")) }
                    screen.resolve(target)
                } else screen.editable() ?: error("请选择普通输入框")
                check(node.isEditable && !node.isPassword) { "请选择可编辑的普通输入框" }
                val text = params.getString("text")
                require(text.length in 1..4000) { "输入长度无效" }
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                if (!accepted && target.virtual) {
                    check(node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "无法聚焦虚拟屏输入框" }
                    if (!node.text.isNullOrEmpty()) check(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text.length)
                    })) { "输入框拒绝选择文字" }
                    target.paste(text, screen.taskId)
                } else if (!accepted && Build.VERSION.SDK_INT >= 33) {
                    check(node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "无法聚焦输入框" }
                    val connection = service.inputMethod?.currentInputConnection ?: error("此输入框不支持文字输入")
                    connection.setSelection(0, node.text?.length ?: 0)
                    connection.commitText(text, 1, TextAttribute.Builder().build())
                } else check(accepted) { "输入框拒绝写入文字" }
                val deadline = SystemClock.uptimeMillis() + 1500
                while (SystemClock.uptimeMillis() < deadline) { if (!node.refresh() || !node.isShowingHintText && node.text?.toString() == text) break; delay(80) }
                val readable = node.refresh() && node.isVisibleToUser && node.isEditable && !node.isPassword
                val actual = if (!readable) null else if (node.isShowingHintText) "" else node.text?.toString()
                val verified = when {
                    actual == text -> true
                    readable && (node.isShowingHintText || node.maxTextLength >= 0 && text.length > node.maxTextLength) -> false
                    else -> null
                }
                return JSONObject().put("inputVerified", verified ?: JSONObject.NULL).put("actualText", actual ?: JSONObject.NULL)
                    .put("hintText", if (readable) node.hintText?.toString().orEmpty() else JSONObject.NULL)
                    .put("showingHintText", if (readable) node.isShowingHintText else JSONObject.NULL)
            }
            "back" -> if (target.virtual) target.key(KeyEvent.KEYCODE_BACK)
                else check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "系统拒绝返回操作" }
            "home" -> if (target.virtual) target.key(KeyEvent.KEYCODE_HOME)
                else check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) { "系统拒绝主页操作" }
        }
        return JSONObject()
    }
}
