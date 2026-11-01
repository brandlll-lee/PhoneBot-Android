package dev.phonebot.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import dev.phonebot.android.MainActivity
import dev.phonebot.android.MarkdownAnswer
import dev.phonebot.android.R
import java.io.File
import org.json.JSONObject

internal object TaskNotifications {
    const val CHANNEL = "task_results"
    fun channel(context: Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "任务结果", NotificationManager.IMPORTANCE_HIGH))

    fun open(context: Context, start: JSONObject): Intent = Intent(context, MainActivity::class.java)
        .setData("phonebot://task/${start.getString("taskId")}".toUri())
        .putExtra("session", start.getString("session")).putExtra("task", start.getString("taskId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun post(context: Context, journal: TaskJournal, start: JSONObject, event: JSONObject): Boolean {
        val title = when (event.optString("type")) {
            "result" -> "执行结束"
            "error", "interrupted" -> "执行失败"
            "cancelled" -> "已停止"
            else -> return false
        }
        val id = start.getString("taskId")
        val file = File(context.filesDir, "task-events/$id.jsonl")
        if (journal.records(file).any { it.optString("type") == "notification" }) return false
        channel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val allowed = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        journal.append(JSONObject().put("type", "notification").put("taskId", id).put("allowed", allowed))
        if (!allowed) return false
        val pending = PendingIntent.getActivity(context, 0, open(context, start), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val summary = MarkdownAnswer.plain(event.optString("text", event.optString("message"))).take(240)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_phonebot_mark)
            .setLargeIcon(avatar(context, start.optLong("avatarColor", 0xFF3985FF).toInt()))
            .setContentTitle("${start.optString("botName", "PhoneBot")} · $title")
            .setContentText(summary).setStyle(Notification.BigTextStyle().bigText(summary))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        return try { manager.notify(id, 2, notification); true } catch (_: SecurityException) { false }
    }

    private fun avatar(context: Context, color: Int) = createBitmap(96, 96).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawCircle(48f, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        AppCompatResources.getDrawable(context, R.drawable.ic_phonebot_mark)?.mutate()?.apply {
            setTint(if (ColorUtils.calculateLuminance(color) > .179) Color.BLACK else Color.WHITE)
            setBounds(12, 12, 84, 84)
            draw(canvas)
        }
    }
}
