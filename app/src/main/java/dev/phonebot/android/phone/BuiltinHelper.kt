package dev.phonebot.android.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import dev.phonebot.android.phone.adb.AdbConnection
import dev.phonebot.android.phone.adb.AdbConnectionException
import dev.phonebot.android.phone.adb.AdbPairing
import dev.phonebot.android.phone.adb.AndroidAdb
import java.io.Closeable
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BuiltinHelper(private val context: Context, private val changed: () -> Unit) {
    private val prefs = context.getSharedPreferences("phone_helper", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networks = context.getSystemService(ConnectivityManager::class.java)
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    private var retryJob: Job? = null
    @Volatile private var operation: Job? = null
    @Volatile private var closed = false
    @Volatile private var connecting = false
    @Volatile var problem = ""; private set
    val paired get() = prefs.getBoolean("paired", false)
    val autoRestore get() = prefs.getBoolean("auto_restore", false)
    val wireless get() = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    val ready get() = HelperHub.shell.value?.asBinder()?.isBinderAlive == true
    private val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { changed(); retry() }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { retry() }
    }

    init {
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
        networks.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        scope.launch { HelperHub.shell.collect { changed(); if (it == null) retry() } }
    }

    fun status(): String = when {
        Build.VERSION.SDK_INT < 31 -> "unsupported"
        ready -> "ready"
        connecting -> "connecting"
        !paired -> "not_paired"
        !wireless -> "wireless_debugging_off"
        !networkAllowed() -> "permission_required"
        problem.isNotEmpty() -> "error"
        else -> "disconnected"
    }

    private fun networkAllowed() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED

    @Synchronized fun retry() {
        if (closed || retryJob?.isActive == true || connecting || !paired || ready || Build.VERSION.SDK_INT < 31) return
        retryJob = scope.launch {
            for (wait in listOf(0L, 500L, 1500L)) {
                delay(wait)
                if (closed || ready) break
                if (runCatching { connect() }.isSuccess) break
            }
        }
    }

    suspend fun pair(port: Int, code: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 31) error("内置助手需要 Android 12 以上")
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位配对码" }
        require(port in 0..65535 && networkAllowed()) { "请允许本地网络访问，并检查端口" }
        HelperHub.connection.withLock {
            operation = currentCoroutineContext().job
            try {
                val target = if (port > 0) port else discover("_adb-tls-pairing._tcp.")
                socketCall { register -> AdbPairing(AndroidAdb.tls(AndroidAdb.identity(context))).pair("127.0.0.1", target, code, onSocket = register) }
                currentCoroutineContext().ensureActive()
                prefs.edit { putBoolean("paired", true) }
            } finally { operation = null }
        }
        problem = ""
        changed()
        connect()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    suspend fun connect() = withContext(Dispatchers.IO) {
        HelperHub.connection.withLock {
            if (ready) return@withLock
            if (Build.VERSION.SDK_INT < 31) error("内置助手需要 Android 12 以上")
            check(paired) { "请先配对内置助手" }
            if (!wireless && autoRestore && context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            }
            check(wireless) { "请打开无线调试" }
            check(networkAllowed()) { "请允许本地网络访问" }
            operation = currentCoroutineContext().job
            connecting = true; changed()
            try {
                val tls = AndroidAdb.tls(AndroidAdb.identity(context))
                val pin = certificatePin(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.map { it.toByteArray() })
                val propertyPort = runCatching {
                    Class.forName("android.os.SystemProperties").getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
                        .invoke(null, "service.adb.tls.port", 0) as Int
                }.getOrDefault(0)
                var connection: AdbConnection? = null
                for (port in listOf(propertyPort, prefs.getInt("port", 0)).filter { it in 1..65535 }.distinct()) {
                    connection = try { socketCall { register -> AdbConnection.open("127.0.0.1", port, tls, 2500, register) } }
                    catch (error: Exception) { currentCoroutineContext().ensureActive(); null }
                    if (connection != null) break
                }
                if (connection == null) {
                    val port = discover("_adb-tls-connect._tcp.")
                    connection = socketCall { register -> AdbConnection.open("127.0.0.1", port, tls, 5000, register) }
                    prefs.edit { putInt("port", port) }
                }
                val command = "read n; (echo \"\$n\" | CLASSPATH=${quote(context.applicationInfo.sourceDir)} setsid app_process /system/bin " +
                    "--nice-name=${context.packageName}:helper ${HelperMain::class.java.name} ${context.packageName} ${Process.myUid()} $pin >/dev/null 2>&1 &); read _"
                checkNotNull(connection).use {
                    socketCall { register -> register(it); it.exec(command).write((HelperHub.issue() + "\n").toByteArray()) }
                    withTimeout(10000) { HelperHub.shell.first { service -> service != null } }
                }
                problem = ""
            } catch (error: Exception) {
                HelperHub.cancelLaunch()
                if (error is CancellationException) throw error
                if (error is AdbConnectionException && error.reason == AdbConnectionException.Reason.UNAUTHORIZED) prefs.edit { putBoolean("paired", false) }
                problem = error.message ?: "助手连接失败"
                throw error
            } finally { operation = null; connecting = false; changed() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun discover(type: String): Int {
        val manager = context.getSystemService(NsdManager::class.java)
        val addresses = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
        var listener: NsdManager.DiscoveryListener? = null
        val port = try { withTimeoutOrNull(7000) {
            suspendCancellableCoroutine { continuation ->
                val discovery = object : NsdManager.DiscoveryListener {
                    val settled = AtomicBoolean()
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(0) }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host ?: return
                                if ((host.isLoopbackAddress || host.hostAddress in addresses) && settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(serviceInfo.port)
                            }
                        })
                    }
                }
                listener = discovery
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
            }
        } } finally { listener?.let { runCatching { manager.stopServiceDiscovery(it) } } }
        return port?.takeIf { it in 1..65535 } ?: error("未找到本机无线调试端口，请确认无线调试已开启")
    }

    private suspend fun <T> socketCall(block: ((Closeable) -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        val resource = AtomicReference<Closeable>()
        val job = scope.launch {
            try {
                val value = block { opened ->
                    resource.getAndSet(opened)?.let(resources::remove)
                    resources.add(opened)
                    if (!continuation.isActive || closed) opened.close()
                }
                continuation.resume(value) { _, result, _ -> (result as? Closeable)?.close() }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { resource.get()?.let(resources::remove) }
        }
        continuation.invokeOnCancellation { runCatching { resource.get()?.close() }; job.cancel() }
        job.invokeOnCompletion { if (it != null) continuation.cancel(it) }
    }

    suspend fun setAutoRestore(enabled: Boolean, service: ISystemShell?) {
        if (enabled && context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            checkNotNull(service) { "请先连接助手" }.execute(java.util.UUID.randomUUID().toString(), "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) { "系统未允许自动恢复" }
        }
        prefs.edit { putBoolean("auto_restore", enabled) }
        changed()
    }

    fun forget() {
        prefs.edit { remove("paired"); remove("port"); remove("auto_restore") }
        retryJob?.cancel()
        operation?.cancel()
        resources.forEach { runCatching { it.close() } }
        runCatching { HelperHub.shell.value?.destroy() }
        HelperHub.clear()
        if (Build.VERSION.SDK_INT >= 31) AndroidAdb.forget(context)
        problem = ""; changed()
    }

    fun close() {
        closed = true
        operation?.cancel()
        scope.cancel()
        resources.forEach { runCatching { it.close() } }
        HelperHub.cancelLaunch()
        context.contentResolver.unregisterContentObserver(observer)
        runCatching { networks.unregisterNetworkCallback(callback) }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

class HelperBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || Build.VERSION.SDK_INT < 31) return
        val prefs = context.getSharedPreferences("phone_helper", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_restore", false)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val helper = BuiltinHelper(context.applicationContext) {}
            try { withTimeoutOrNull(25000) { runCatching { helper.connect() } } }
            finally { helper.close(); pending.finish() }
        }
    }
}
