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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PhoneShell(private val context: Context, private val onChange: () -> Unit = {}) {
    private val builtinDelegate = lazy { BuiltinHelper(context.applicationContext, onChange) }
    private val builtin by builtinDelegate
    private val lock = Mutex()
    private var shizukuService: ISystemShell? = null
    private var shizukuConnection: ServiceConnection? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, SystemShellService::class.java.name))
        .daemon(false).processNameSuffix("shell").version(3)
    private val received = Shizuku.OnBinderReceivedListener { onChange() }
    private val died = Shizuku.OnBinderDeadListener { shizukuService = null; onChange() }
    private val granted = Shizuku.OnRequestPermissionResultListener { _, _ -> onChange() }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(granted)
    }

    fun ready(backend: String = "shizuku"): Boolean = if (backend == "builtin") builtin.ready else
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun status(backend: String = "shizuku"): String = if (backend == "builtin") builtin.status() else
        if (ready()) "ready" else if (Shizuku.pingBinder()) "permission_required" else "disconnected"

    fun state(backend: String): JSONObject = JSONObject().put("helper", backend).put("state", status(backend))
        .put("paired", builtin.paired).put("wirelessDebugging", builtin.wireless).put("autoRestore", builtin.autoRestore)
        .put("message", if (backend == "builtin") builtin.problem else "")

    suspend fun pair(port: Int, code: String) = builtin.pair(port, code)
    suspend fun connect() = builtin.connect()
    fun forget() = builtin.forget()
    suspend fun setAutoRestore(enabled: Boolean, backend: String = "builtin") = builtin.setAutoRestore(enabled, if (enabled) service(backend) else null)

    suspend fun service(backend: String = "shizuku"): ISystemShell {
        require(backend in setOf("builtin", "shizuku")) { "助手方式无效" }
        if (backend == "builtin") {
            builtin.connect()
            return checkNotNull(HelperHub.shell.value) { "内置助手尚未连接" }
        }
        return lock.withLock {
            shizukuService?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
            check(ready()) { "请启动 Shizuku，并授权 PhoneBot" }
            val connected = CompletableDeferred<ISystemShell>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null) connected.complete(ISystemShell.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: ComponentName?) { shizukuService = null; onChange() }
            }
            withContext(Dispatchers.Main) {
                shizukuConnection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
                Shizuku.bindUserService(args, connection)
                shizukuConnection = connection
            }
            try { withTimeout(10000) { connected.await() }.also { shizukuService = it } }
            catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, false) } }
                shizukuConnection = null
                throw error
            }
        }
    }

    suspend fun execute(command: String, backend: String = "shizuku"): JSONObject {
        require(command.isNotBlank() && command.length <= 4000 && '\u0000' !in command) { "系统命令无效" }
        if (backend == "shizuku" && !ready(backend)) return JSONObject().put("ok", false).put("error", status(backend)).put("permission", backend)
            .put("message", "请启动 Shizuku，并在 PhoneBot 权限页授权系统命令")
        val service = service(backend)
        val requestId = java.util.UUID.randomUUID().toString()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { Thread({ runCatching { service.cancel(requestId) } }, "shell-cancel").start() }
            Thread({
                try {
                    val result = JSONObject(service.execute(requestId, command))
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }, "shell-request").start()
        }
    }

    fun close() {
        if (builtinDelegate.isInitialized()) builtin.close()
        runCatching { HelperHub.shell.value?.destroy() }
        HelperHub.clear()
        shizukuConnection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        shizukuConnection = null; shizukuService = null
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(granted)
    }
}
