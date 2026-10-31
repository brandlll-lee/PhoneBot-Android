package dev.phonebot.android.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PhoneShell(private val context: Context) {
    fun ready(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun status(): String = if (ready()) "ready" else if (Shizuku.pingBinder()) "permission_required" else "disconnected"

    suspend fun execute(command: String): JSONObject {
        require(command.isNotBlank() && command.length <= 4000 && '\u0000' !in command) { "系统命令无效" }
        if (!ready()) return JSONObject().put("ok", false).put("error", status()).put("permission", "shizuku")
            .put("message", "请启动 Shizuku，并在 PhoneBot 权限页授权系统命令")
        val connected = CompletableDeferred<ISystemShell>()
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, SystemShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").version(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) connected.complete(ISystemShell.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { connected.completeExceptionally(IllegalStateException("系统命令连接已断开")) }
        }
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val service = withTimeout(10000) { connected.await() }
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { Thread({ runCatching { service.cancel() } }, "shell-cancel").start() }
                Thread({
                    try {
                        val result = JSONObject(service.execute(command))
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }, "shell-request").start()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, false) } }
        }
    }
}
