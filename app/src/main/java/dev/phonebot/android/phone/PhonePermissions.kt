package dev.phonebot.android.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.phonebot.android.engine.TaskNotifications
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import androidx.core.net.toUri

data class PhonePermission(val id: String, val name: String, val purpose: String, val granted: Boolean, val required: Boolean)

object PhonePermissions {
    fun check(context: Context): List<PhonePermission> {
        val component = ComponentName(context, PhoneAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == component }
        return listOf(
            PhonePermission("accessibility", "无障碍服务", "读取页面、点击、滑动和输入文字", enabled, true),
            PhonePermission("unlock", "屏幕解锁", "在亮屏且解锁的主屏执行任务", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked, true),
            PhonePermission("write_settings", "修改系统设置", "设置屏幕亮度并读取实际结果", Settings.System.canWrite(context), false),
            PhonePermission("shizuku", "系统命令", "通过已启动的 Shizuku 执行授权系统命令", runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false), false),
            PhonePermission("notifications", "通知权限", "显示任务结果、引擎状态和停止入口", context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(), false),
            PhonePermission("task_results", "任务结果横幅", "任务结束时弹出提醒，点击返回对应任务", context.getSystemService(NotificationManager::class.java).let { manager ->
                manager.areNotificationsEnabled() && (manager.getNotificationChannel(TaskNotifications.CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH
            }, false),
        )
    }
    fun settings(context: Context, id: String): Intent = when (id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
        "shizuku" -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "task_results" -> {
            TaskNotifications.channel(context)
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, TaskNotifications.CHANNEL)
        }
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    fun notificationPermission(): String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
}
