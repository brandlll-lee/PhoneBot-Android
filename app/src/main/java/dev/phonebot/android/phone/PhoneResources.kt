package dev.phonebot.android.phone

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

internal class PhoneResources {
    val main = Mutex()
    val clipboard = Mutex()
    private val apps = ConcurrentHashMap<String, Mutex>()
    private val held = ConcurrentHashMap<String, String>()

    suspend fun app(owner: String, name: String) {
        if (held[owner] == name) return
        releaseApp(owner)
        apps.computeIfAbsent(name) { Mutex() }.lock(owner)
        held[owner] = name
    }

    private fun releaseApp(owner: String) {
        held.remove(owner)?.let { apps.getValue(it).unlock(owner) }
    }

    fun release(owner: String) {
        releaseApp(owner)
        if (main.holdsLock(owner)) main.unlock(owner)
    }
}
