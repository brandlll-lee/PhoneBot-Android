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
    private val actions = setOf("app_launch", "tap", "swipe", "input_text", "back", "home")

    private suspend fun settle(): Boolean {
        val deadline = SystemClock.uptimeMillis() + 2500
        var previous = ""
        var repeats = 0
        while (true) {
            coroutineContext.ensureActive()
            val fingerprint = runCatching { screen.scan(); screen.fingerprint() }.getOrDefault("")
            repeats = if (fingerprint.isNotEmpty() && fingerprint == previous) repeats + 1 else 0
            if (repeats >= 2 && SystemClock.uptimeMillis() - service.lastScreenEvent >= 250) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            previous = fingerprint
            delay(120)
        }
    }

    suspend fun execute(name: String, params: JSONObject, report: (JSONObject) -> Unit): JSONObject {
        if (name != "do_steps") {
            val stable = act(name, params)
            return screen.present(false).put("stable", stable).put("inputVerified", name == "input_text")
        }
        val steps = params.getJSONArray("steps")
        require(steps.length() in 1..12) { "连续操作需要 1 到 12 步" }
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            require(step.getString("tool") in actions) { "连续操作包含未知工具" }
            val args = step.getJSONObject("args")
            require(!args.has("element") && !args.has("x") && !args.has("y")) { "连续操作请使用文字或资源 ID 定位目标" }
        }
        val results = JSONArray()
        var stopped = false
        for (i in 0 until steps.length()) {
            coroutineContext.ensureActive()
            val step = steps.getJSONObject(i)
            val tool = step.getString("tool")
            val args = step.getJSONObject("args")
            val started = SystemClock.elapsedRealtime()
            report(JSONObject().put("type", "step").put("id", "batch-$i").put("name", tool).put("args", args))
            val result = JSONObject().put("index", i).put("tool", tool)
            try {
                check(settle()) { "页面尚未稳定，请重新判断" }
                check(!dialog()) { "出现弹窗，请重新判断" }
                val before = screen.latest.optString("package")
                val stable = act(tool, args)
                check(stable) { "页面尚未稳定，请重新判断" }
                if (tool !in setOf("app_launch", "home", "back")) check(screen.latest.optString("package") == before) { "应用发生切换，请重新判断" }
                check(!dialog()) { "出现弹窗，请重新判断" }
                step.optJSONObject("expect")?.let { expected ->
                    if (expected.has("package")) check(screen.latest.optString("package") == expected.getString("package")) { "未进入预期应用" }
                    if (expected.has("text")) check(screen.text().contains(expected.getString("text"))) { "未出现预期文字" }
                }
                result.put("ok", true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { result.put("ok", false).put("error", error.message); stopped = true }
            result.put("durationMs", SystemClock.elapsedRealtime() - started)
            results.put(result)
            report(JSONObject().put("type", "step_result").put("id", "batch-$i").put("error", stopped).put("text", result.toString()))
            if (stopped) break
        }
        return JSONObject().put("ok", !stopped).put("steps", results).put("remaining", steps.length() - results.length()).put("screen", screen.present(false))
    }

    private fun dialog(): Boolean {
        val root = service.rootInActiveWindow ?: return true
        return root.className?.contains("Dialog", true) == true || root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").isNotEmpty()
    }

    private suspend fun act(name: String, params: JSONObject): Boolean {
        coroutineContext.ensureActive()
        check(service.available()) { "请亮屏并解锁手机" }
        require(name in actions) { "工具不存在" }
        screen.scan()
        val before = screen.fingerprint()
        when (name) {
            "app_launch" -> {
                val target = params.getString("package")
                require(target.matches(Regex("[a-zA-Z0-9_.]+"))) { "应用包名无效" }
                val intent = service.packageManager.getLaunchIntentForPackage(target) ?: error("找不到可启动的应用")
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val deadline = SystemClock.uptimeMillis() + 4000
                while (service.rootInActiveWindow?.packageName?.toString() != target && SystemClock.uptimeMillis() < deadline) delay(100)
                check(service.rootInActiveWindow?.packageName?.toString() == target) { "应用启动后尚未进入目标页面" }
            }
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
        val stable = settle()
        screen.latest.put("changed", before != screen.fingerprint())
        return stable
    }
}
