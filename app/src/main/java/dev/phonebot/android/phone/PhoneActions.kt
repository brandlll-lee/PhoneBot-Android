package dev.phonebot.android.phone

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.TextAttribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal class PhoneActions(private val service: PhoneAccessibilityService, private val screen: ScreenState) {
    private val actions = setOf("app_launch", "app_send_intent", "phone_open_link", "tap", "swipe", "input_text", "back", "home")

    private suspend fun settle(expected: JSONObject?, timeout: Long, packageName: String?, dialogWindow: Int?, before: String, conditionBefore: Boolean): JSONObject {
        val began = SystemClock.uptimeMillis()
        var previous = ""
        var repeats = 0
        while (true) {
            coroutineContext.ensureActive()
            check(service.available()) { "请亮屏并解锁手机" }
            screen.scan()
            val fingerprint = screen.fingerprint()
            repeats = if (fingerprint.isNotEmpty() && fingerprint == previous) repeats + 1 else 0
            val stable = repeats >= 2 && SystemClock.uptimeMillis() - service.lastScreenEvent >= 250
            val met = fingerprint.isNotEmpty() && expected?.let(screen::condition) == true
            val changed = fingerprint.isNotEmpty() && fingerprint != before
            val expired = SystemClock.uptimeMillis() - began >= timeout
            val popup = dialog() && screen.root?.windowId != dialogWindow
            val switched = fingerprint.isNotEmpty() && packageName != null && screen.latest.optString("package") != packageName &&
                expected?.optString("package") != screen.latest.optString("package")
            val reason = when {
                popup -> "dialog"
                switched -> "navigation"
                met && stable -> if (conditionBefore && !changed) "condition_unchanged" else "condition_met"
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
        for (i in 0 until steps.length()) {
            coroutineContext.ensureActive()
            val spec = steps.getJSONObject(i)
            val tool = spec.getString("tool")
            val args = spec.getJSONObject("args")
            val started = SystemClock.elapsedRealtime()
            report(JSONObject().put("type", "step").put("id", "batch-$i").put("name", tool).put("args", args))
            val result = JSONObject().put("index", i).put("tool", tool)
            try {
                val outcome = step(tool, args, args.optJSONObject("expect"))
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

    private suspend fun step(name: String, params: JSONObject, expected: JSONObject?): JSONObject {
        val target = expected ?: if (name == "app_launch") JSONObject().put("package", params.getString("package")) else null
        val timeout = params.optLong("timeoutMs", if (target == null) 2500 else 8000)
        require(timeout in 250..15000) { "等待上限需为 250 到 15000 毫秒" }
        screen.scan()
        val dialogWindow = screen.root?.windowId?.takeIf { dialog() }
        val before = screen.latest.optString("package").takeUnless { it == "null" || it.isEmpty() }
        val fingerprint = screen.fingerprint()
        val conditionBefore = target?.let(screen::condition) == true
        act(name, params)
        val result = settle(target, timeout, before.takeIf { name !in setOf("app_launch", "home", "back") }, dialogWindow, fingerprint, conditionBefore).put("inputVerified", name == "input_text")
        val reason = result.getString("stopReason")
        val changedApp = before != null && screen.latest.optString("package") != before
        return result.put("executed", true).put("ok", true)
            .put("navigation", if (!changedApp) "none" else if (reason == "navigation") "unplanned" else "expected")
            .put("canContinue", reason == "condition_met" || reason == "tree_stable" && name in setOf("input_text", "home", "back"))
    }

    private fun dialog(): Boolean {
        val root = screen.root ?: return false
        return root.className?.contains("Dialog", true) == true || root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").isNotEmpty()
    }

    private suspend fun act(name: String, params: JSONObject) {
        coroutineContext.ensureActive()
        check(service.available()) { "请亮屏并解锁手机" }
        require(name in actions) { "工具不存在" }
        when (name) {
            "app_launch" -> {
                val target = params.getString("package")
                require(target.matches(Regex("[a-zA-Z0-9_.]+"))) { "应用包名无效" }
                val intent = service.packageManager.getLaunchIntentForPackage(target) ?: error("找不到可启动的应用")
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "app_send_intent", "phone_open_link" -> PhoneIntents.start(service, name, params)
            "tap" -> {
                val node = if (params.has("element") || params.has("text") || params.has("resourceId")) screen.resolve(params) else null
                if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
                    val point = if (bounds != null) screen.geometry.point(bounds.exactCenterX().toInt().toDouble(), bounds.exactCenterY().toInt().toDouble())
                        else screen.geometry.point(params.getDouble("x"), params.getDouble("y"))
                    service.gesture(Path().apply { moveTo(point.first, point.second) }, 80)
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
                service.gesture(Path().apply { moveTo(start.first, start.second); lineTo(end.first, end.second) }, duration)
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
                if (!accepted && Build.VERSION.SDK_INT >= 33) {
                    check(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "无法聚焦输入框" }
                    val connection = service.inputMethod?.currentInputConnection ?: error("此输入框不支持文字输入")
                    connection.setSelection(0, node.text?.length ?: 0)
                    connection.commitText(text, 1, TextAttribute.Builder().build())
                } else check(accepted) { "输入框拒绝写入文字" }
                val deadline = SystemClock.uptimeMillis() + 1500
                while (SystemClock.uptimeMillis() < deadline) { if (node.refresh() && node.text?.toString() == text) break; delay(80) }
                check(node.refresh() && node.text?.toString() == text) { "输入回读不一致。请检查页面。" }
            }
            "back" -> check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "系统拒绝返回操作" }
            "home" -> check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) { "系统拒绝主页操作" }
        }
    }
}
