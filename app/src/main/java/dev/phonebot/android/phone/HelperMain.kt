package dev.phonebot.android.phone

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import kotlin.system.exitProcess

object HelperMain {
    @JvmStatic
    @RequiresApi(31)
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun main(args: Array<String>) {
        require(args.size == 3 && Process.myUid() == 2000)
        val (packageName, uidText, pin) = args
        val appUid = uidText.toInt()
        val nonce = checkNotNull(System.`in`.bufferedReader().readLine())
        System.`in`.close()
        Looper.prepareMainLooper()
        val context = ShellContext.create()
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(info.applicationInfo?.uid == appUid && certificatePin(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }) == pin)
        val authority = "$packageName.helper"
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val user = appUid / 100000
        val holder = checkNotNull(am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            android.os.IBinder::class.java, String::class.java).invoke(am, authority, user, token, "PhoneBot"))
        val service = SystemShellService(appUid)
        try {
            val providerInfo = holder.javaClass.getField("info").get(holder) as ProviderInfo
            require(providerInfo.packageName == packageName && providerInfo.applicationInfo.uid == appUid)
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val extras = Bundle().apply {
                putString("nonce", nonce); putBinder("binder", service)
                putInt("uid", Process.myUid()); putInt("pid", Process.myPid())
                putString("apk", System.getProperty("java.class.path"))
            }
            val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java }
            val source = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
            val response = call.invoke(provider, source, authority, "attach", null, extras) as Bundle
            val owner = checkNotNull(response.getBinder("owner"))
            owner.linkToDeath({ service.destroy() }, 0)
        } finally {
            am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, android.os.IBinder::class.java,
                Int::class.javaPrimitiveType).invoke(am, authority, token, user)
        }
        Looper.loop()
        exitProcess(0)
    }
}

internal fun certificatePin(certificates: List<ByteArray>?): String = certificates.orEmpty().map {
    MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
}.sorted().joinToString(",").also { require(it.isNotEmpty()) }
