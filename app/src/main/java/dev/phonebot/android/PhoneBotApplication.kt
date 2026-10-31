package dev.phonebot.android

import android.app.Application
import android.content.Context
import rikka.shizuku.ShizukuProvider

class PhoneBotApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val providerProcess = getProcessName() == base.packageName
        ShizukuProvider.enableMultiProcessSupport(providerProcess)
        if (!providerProcess) ShizukuProvider.requestBinderForNonProviderProcess(base)
    }
}
