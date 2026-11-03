package dev.phonebot.android.phone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

internal class PhoneSystem(private val context: Context, private val shell: PhoneShell, private val display: PhoneDisplay? = null, private val owner: String) {
    companion object {
        val tools = setOf("device_status", "volume_set", "brightness_set", "wifi_toggle", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_system_command")
    }
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val wifi get() = context.applicationContext.getSystemService(WifiManager::class.java)
    private val resolver get() = context.contentResolver
    private fun volume() = JSONObject().put("value", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        .put("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
    private fun brightness() = JSONObject().put("value", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1))
        .put("automatic", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    private fun percent(args: JSONObject): Int = args.getInt("percent").also { require(it in 0..100) { "百分比需为 0 到 100" } }
    private suspend fun open(intent: Intent): JSONObject {
        display?.reserve(intent, owner)
        kotlinx.coroutines.withTimeout(30000) { display?.launch(intent, owner) ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return JSONObject().put("ok", true).put("status", "opened").put("verified", false).put("action", intent.action)
            .put("message", "已打开系统处理界面；需读取页面并确认结果")
    }

    suspend fun execute(name: String, args: JSONObject): JSONObject {
        val result = when (name) {
            "device_status" -> JSONObject().put("volume", volume()).put("brightness", brightness()).put("wifiEnabled", wifi.isWifiEnabled)
                .put("shell", shell.status(display?.helper ?: "shizuku")).put("timeZone", java.util.TimeZone.getDefault().id)
            "volume_set" -> {
                val target = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent(args) / 100.0).roundToInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val actual = volume()
                actual.put("ok", actual.getInt("value") == target && (target == 0 || !actual.getBoolean("muted"))).put("verified", true)
            }
            "brightness_set" -> {
                if (!Settings.System.canWrite(context)) return JSONObject().put("ok", false).put("permission", "write_settings").put("message", "需要修改系统设置权限；请在权限页授权")
                val automatic = args.optBoolean("auto")
                val value = if (automatic) null else (percent(args) * 255.0 / 100).roundToInt().coerceAtLeast(1)
                val mode = if (automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                check(Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)) { "亮度模式写入失败" }
                if (value != null) check(Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)) { "亮度写入失败" }
                val actual = brightness()
                actual.put("ok", actual.getBoolean("automatic") == automatic && (value == null || actual.getInt("value") == value)).put("verified", true)
            }
            "wifi_toggle" -> {
                val on = args.getBoolean("on")
                if (wifi.isWifiEnabled == on) return JSONObject().put("ok", true).put("verified", true).put("wifiEnabled", on)
                if (!shell.ready(display?.helper ?: "shizuku")) return open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)).put("requestedEnabled", on)
                val command = shell.execute("svc wifi ${if (on) "enable" else "disable"}", display?.helper ?: "shizuku")
                if (!command.optBoolean("ok")) return command
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (wifi.isWifiEnabled != on && SystemClock.elapsedRealtime() < deadline) delay(100)
                JSONObject().put("ok", wifi.isWifiEnabled == on).put("verified", wifi.isWifiEnabled == on).put("wifiEnabled", wifi.isWifiEnabled)
            }
            "phone_set_alarm" -> {
                val hour = args.getInt("hour"); val minute = args.getInt("minute")
                require(hour in 0..23 && minute in 0..59) { "闹钟时间无效" }
                open(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")))
            }
            "phone_set_timer" -> {
                val seconds = args.getInt("seconds")
                require(seconds in 1..86400) { "计时秒数需为 1 到 86400" }
                open(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")))
            }
            "phone_add_calendar_event" -> {
                val begin = args.getLong("beginTime"); val end = args.getLong("endTime")
                require(begin > 0 && end > begin) { "日程开始和结束时间无效" }
                open(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, args.getString("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location")).putExtra(CalendarContract.Events.DESCRIPTION, args.optString("description")))
            }
            "phone_system_command" -> shell.execute(args.getString("command"), display?.helper ?: "shizuku")
            else -> error("系统工具不存在")
        }
        if (!result.has("ok")) result.put("ok", true)
        return result.put("observedAt", System.currentTimeMillis())
    }
}
