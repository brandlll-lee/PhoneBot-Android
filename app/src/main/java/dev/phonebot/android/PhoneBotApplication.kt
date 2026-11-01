package dev.phonebot.android

import android.app.Application
import android.content.Context
import com.google.mlkit.common.MlKit
import rikka.shizuku.ShizukuProvider

class PhoneBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The SDK provider initializes the main process. OCR also runs in :engine.
        if (getProcessName() == "$packageName:engine") MlKit.initialize(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val providerProcess = getProcessName() == base.packageName
        ShizukuProvider.enableMultiProcessSupport(providerProcess)
        if (!providerProcess) ShizukuProvider.requestBinderForNonProviderProcess(base)
    }
}
