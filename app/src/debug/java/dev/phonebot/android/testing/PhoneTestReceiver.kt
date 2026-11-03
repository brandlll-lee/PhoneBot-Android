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
import dev.phonebot.android.phone.PhoneExecution
import dev.phonebot.android.phone.PhoneResources
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
        private val displays = mutableMapOf<String, PhoneDisplay>()
        private val executions = mutableMapOf<String, PhoneExecution>()
        private val resources = PhoneResources()
    }
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION") val result = intent.getParcelableExtra<ResultReceiver>("reply") ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val output = withTimeout(45000) {
                    val name = intent.getStringExtra("tool")!!
                    val params = JSONObject(intent.getStringExtra("params") ?: "{}")
                    val executor = shell ?: PhoneShell(context.applicationContext).also { shell = it }
                    val owner = intent.getStringExtra("owner") ?: "test-session"
                    if (name == "helper_status") return@withTimeout executor.state(params.optString("helper", "shizuku"))
                    if (name == "helper_pair") { executor.pair(params.getInt("port"), params.getString("code")); return@withTimeout executor.state("builtin") }
                    if (name == "test_release") {
                        PhoneAccessibilityService.instance?.overlay?.close()
                        displays.remove(owner)?.release(); executions.remove(owner); resources.release(owner)
                        return@withTimeout JSONObject().put("ok", true)
                    }
                    val target = displays.getOrPut(owner) { PhoneDisplay(context.applicationContext, executor, resources, owner) }
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
                            service.overlay!!.update(JSONObject().put("taskId", "overlay-test").put("session", "overlay-test").put("prompt", "检查执行屏幕")
                                .put("avatarColor", params.optLong("avatarColor")), target)
                        }
                        if (params.optBoolean("close")) service.overlay?.close()
                        return@withTimeout JSONObject().put("displayId", target.displayId).put("appearance", dev.phonebot.android.PhoneAppearance.mode.name)
                    }
                    if (name == "test_target") {
                        val session = params.optString("session", "test-session")
                        val helper = params.getString("helper")
                        val screen = params.getString("screen")
                        if (target.session != session || target.helper != helper || target.mode != screen) {
                            target.release(); executions.remove(owner)
                            target.select(session, helper, screen)
                        }
                        if (!target.virtual && !resources.main.holdsLock(owner)) resources.main.lock(owner)
                        target.prepare()
                        executions.getOrPut(owner) { PhoneExecution(service, target, owner) }
                        return@withTimeout JSONObject().put("displayId", target.displayId).put("tasks", org.json.JSONArray(target.tasks()))
                            .put("identity", org.json.JSONArray(executor.service(target.helper).identity().toList()))
                    }
                    val execution = executions.getOrPut(owner) { PhoneExecution(service, target, owner) }
                    if (name == "test_launch") {
                        val activity = params.optString("activity")
                        val launch = if (activity.isNotEmpty()) Intent().setComponent(android.content.ComponentName(params.getString("package"), activity))
                            else Intent(context, PhoneTestActivity::class.java)
                        target.launch(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("pasteInput", params.optBoolean("pasteInput"))
                            .putExtra("inputMode", params.optString("inputMode")).putExtra("conditionFixture", params.optBoolean("conditionFixture")))
                        delay(700)
                        return@withTimeout execution.execute("screen_read", JSONObject())
                    }
                    execution.execute(name, params)
                }
                result.send(0, Bundle().apply { putString("result", output.toString()) })
            } catch (error: Exception) { result.send(1, Bundle().apply { putString("error", error.message) }) }
        }
    }
}
