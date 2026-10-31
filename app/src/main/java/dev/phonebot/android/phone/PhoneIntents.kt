package dev.phonebot.android.phone

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.json.JSONObject

internal object PhoneIntents {
    val settings = setOf("SETTINGS", "WIFI_SETTINGS", "BLUETOOTH_SETTINGS", "SOUND_SETTINGS", "DISPLAY_SETTINGS",
        "APPLICATION_DETAILS_SETTINGS", "APPLICATION_SETTINGS", "DATE_SETTINGS", "LOCALE_SETTINGS", "DEVICE_INFO_SETTINGS",
        "LOCATION_SOURCE_SETTINGS", "INTERNAL_STORAGE_SETTINGS", "ACCESSIBILITY_SETTINGS", "BATTERY_SAVER_SETTINGS")
        .map { "android.settings.$it" }.toSet()

    fun start(context: Context, tool: String, args: JSONObject) {
        val action = args.optString("action").ifEmpty { Intent.ACTION_VIEW }
        require(action.matches(Regex("[A-Za-z][A-Za-z0-9_.]+"))) { "Intent action 无效" }
        val data = args.optString("uri", args.optString("data")).takeIf(String::isNotEmpty)?.toUri()
        require(data == null || data.scheme in setOf("https", "http", "geo", "market", "tel", "sms", "mailto", "package", "content")) { "链接协议不支持" }
        if (tool == "phone_open_link" && args.has("action")) {
            require(action in settings) { "请选择支持的系统设置入口" }
            require(if (action == "android.settings.APPLICATION_DETAILS_SETTINGS") data?.scheme == "package" else data == null) { "应用详情需要 package:包名；其他设置入口只需 action" }
        }
        val intent = Intent(action)
        val type = args.optString("type").takeIf(String::isNotEmpty)
        intent.setDataAndType(data, type)
        args.optString("package").takeIf(String::isNotEmpty)?.let {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_.]+"))) { "应用包名无效" }
            intent.setPackage(it)
        }
        args.optJSONObject("extras")?.let { extras -> extras.keys().forEach { key ->
            when (val value = extras.get(key)) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Number -> intent.putExtra(key, value.toDouble())
                else -> error("Intent extras 只接受文字、数字和布尔值")
            }
        } }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
