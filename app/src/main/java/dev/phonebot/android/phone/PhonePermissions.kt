package dev.phonebot.android.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class PhonePermission(val id: String, val name: String, val purpose: String, val granted: Boolean, val required: Boolean)

object PhonePermissions {
    fun check(context: Context): List<PhonePermission> {
        val component = ComponentName(context, PhoneAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == component }
        return listOf(
            PhonePermission("accessibility", "无障碍服务", "读取页面、点击、滑动和输入文字", enabled, true),
            PhonePermission("unlock", "屏幕解锁", "在亮屏且解锁的主屏执行任务", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked, true),
            PhonePermission("notifications", "运行通知", "显示引擎状态和停止入口", context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(), false),
        )
    }
    fun settings(context: Context, id: String): Intent = when (id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    fun notificationPermission(): String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
}
