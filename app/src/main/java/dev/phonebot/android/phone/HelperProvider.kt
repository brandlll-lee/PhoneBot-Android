package dev.phonebot.android.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

internal object HelperHub {
    val shell = MutableStateFlow<ISystemShell?>(null)
    val connection = Mutex()
    private val owner = Binder()
    private var nonce: String? = null
    private var issued = 0L

    @Synchronized fun issue(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }.also { nonce = it; issued = SystemClock.elapsedRealtime() }

    @Synchronized fun clear() { nonce = null; shell.value = null }
    @Synchronized fun cancelLaunch() { nonce = null }

    @Synchronized fun accept(extras: Bundle, apk: String): Bundle {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        require(uid == 2000 && extras.getInt("uid") == uid && extras.getInt("pid") == pid) { "助手身份无效" }
        require(extras.getString("apk") == apk) { "助手版本已变化" }
        val expected = checkNotNull(nonce) { "助手启动请求已结束" }
        require(SystemClock.elapsedRealtime() - issued in 0..30000 && MessageDigest.isEqual(
            expected.toByteArray(), extras.getString("nonce").orEmpty().toByteArray())) { "助手凭据无效" }
        val binder = checkNotNull(extras.getBinder("binder"))
        val service = ISystemShell.Stub.asInterface(binder)
        require(service.identity().contentEquals(intArrayOf(uid, pid))) { "助手进程不匹配" }
        binder.linkToDeath({ shell.compareAndSet(service, null) }, 0)
        nonce = null
        shell.value = service
        return Bundle().apply { putBinder("owner", owner) }
    }
}

class HelperProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        if (method == "attach" && extras != null) HelperHub.accept(extras, checkNotNull(context).applicationInfo.sourceDir) else null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
