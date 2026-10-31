package dev.phonebot.android.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import dev.phonebot.android.phone.PhoneAccessibilityService
import dev.phonebot.android.phone.PhoneSystem
import dev.phonebot.android.phone.PhoneShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PhoneTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION") val result = intent.getParcelableExtra<ResultReceiver>("reply") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val output = withTimeout(15000) {
                    val name = intent.getStringExtra("tool")!!
                    val params = JSONObject(intent.getStringExtra("params") ?: "{}")
                    if (name in PhoneSystem.tools) return@withTimeout PhoneSystem(context, PhoneShell(context)).execute(name, params)
                    while (PhoneAccessibilityService.instance == null) delay(100)
                    val service = checkNotNull(PhoneAccessibilityService.instance) { "无障碍服务未连接" }
                    service.execute(name, params)
                }
                result.send(0, Bundle().apply { putString("result", output.toString()) })
            } catch (error: Exception) { result.send(1, Bundle().apply { putString("error", error.message) }) }
            finally { pending.finish() }
        }
    }
}
