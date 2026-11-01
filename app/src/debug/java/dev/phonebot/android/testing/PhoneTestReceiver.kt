package dev.phonebot.android.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import dev.phonebot.android.phone.PhoneAccessibilityService
import dev.phonebot.android.phone.PhoneSystem
import dev.phonebot.android.phone.PhoneShell
import dev.phonebot.android.phone.PhoneDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class PhoneTestReceiver : BroadcastReceiver() {
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var shell: PhoneShell? = null
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var display: PhoneDisplay? = null
        private var overlayStopped = false
    }
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION") val result = intent.getParcelableExtra<ResultReceiver>("reply") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val output = withTimeout(45000) {
                    val name = intent.getStringExtra("tool")!!
                    val params = JSONObject(intent.getStringExtra("params") ?: "{}")
                    val executor = shell ?: PhoneShell(context.applicationContext).also { shell = it }
                    val target = display ?: PhoneDisplay(context.applicationContext, executor).also { display = it }
                    if (name == "helper_status") return@withTimeout executor.state(params.optString("helper", "shizuku"))
                    if (name == "helper_pair") { executor.pair(params.getInt("port"), params.getString("code")); return@withTimeout executor.state("builtin") }
                    if (name == "test_release") { PhoneAccessibilityService.instance?.overlay?.close(); target.select("test-release", "shizuku", "main"); PhoneAccessibilityService.instance?.bind(target); return@withTimeout JSONObject().put("ok", true) }
                    if (name in PhoneSystem.tools) return@withTimeout PhoneSystem(context, executor, target).execute(name, params)
                    while (PhoneAccessibilityService.instance == null) delay(100)
                    val service = checkNotNull(PhoneAccessibilityService.instance) { "无障碍服务未连接" }
                    if (name == "test_preview") {
                        val bitmap = target.previewFrames().first()
                        try { return@withTimeout JSONObject().put("width", bitmap.width).put("height", bitmap.height) }
                        finally { bitmap.recycle() }
                    }
                    if (name == "test_overlay") {
                        if (params.optBoolean("show")) {
                            overlayStopped = false
                            service.overlay!!.update(JSONObject().put("taskId", "overlay-test").put("session", "overlay-test").put("prompt", "检查执行屏幕"), target,
                                JSONObject().put("type", "step").put("label", "读取页面")) { overlayStopped = true; service.overlay?.close() }
                        }
                        return@withTimeout JSONObject().put("stopped", overlayStopped).put("displayId", target.displayId)
                    }
                    if (name == "test_target") {
                        target.select(params.optString("session", "test-session"), params.getString("helper"), params.getString("screen"))
                        target.prepare(); service.bind(target)
                        return@withTimeout JSONObject().put("displayId", target.displayId).put("tasks", org.json.JSONArray(target.tasks()))
                            .put("identity", org.json.JSONArray(executor.service(target.helper).identity().toList()))
                    }
                    if (name == "test_launch") {
                        target.launch(Intent(context, PhoneTestActivity::class.java).putExtra("pasteInput", params.optBoolean("pasteInput")))
                        delay(700)
                        return@withTimeout service.execute("screen_read", JSONObject())
                    }
                    service.execute(name, params)
                }
                result.send(0, Bundle().apply { putString("result", output.toString()) })
            } catch (error: Exception) { result.send(1, Bundle().apply { putString("error", error.message) }) }
            finally { pending.finish() }
        }
    }
}
