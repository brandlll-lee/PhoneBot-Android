package dev.phonebot.android.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import dev.phonebot.android.phone.PhoneAccessibilityService
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
                    while (PhoneAccessibilityService.instance == null) delay(100)
                    val service = checkNotNull(PhoneAccessibilityService.instance) { "无障碍服务未连接" }
                    service.execute(intent.getStringExtra("tool")!!, JSONObject(intent.getStringExtra("params") ?: "{}"))
                }
                result.send(0, Bundle().apply { putString("result", output.toString()) })
            } catch (error: Exception) { result.send(1, Bundle().apply { putString("error", error.message) }) }
            finally { pending.finish() }
        }
    }
}
