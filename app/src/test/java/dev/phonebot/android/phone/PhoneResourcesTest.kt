package dev.phonebot.android.phone

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneResourcesTest {
    @Test(timeout = 5000) fun appsTransferAndReleaseBeforeWaitingForAnotherApp() = runBlocking {
        val resources = PhoneResources()
        resources.app("a", "music")
        resources.app("b", "notes")
        var transferred = false
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            resources.app("a", "notes")
            transferred = true
        }
        assertFalse(transferred)
        resources.app("c", "music")
        resources.release("b")
        waiting.join()
        assertTrue(transferred)
        resources.release("a"); resources.release("c")
    }

    @Test(timeout = 5000) fun mainAndClipboardQueueAndCancelledWaitersKeepTheOwner() = runBlocking {
        val resources = PhoneResources()
        resources.main.lock("a")
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { resources.main.lock("b") }
        cancelled.cancelAndJoin()
        resources.release("b")
        assertTrue(resources.main.holdsLock("a"))
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { resources.main.lock("c") }
        assertTrue(waiting.isActive)
        resources.release("a")
        waiting.join()
        assertTrue(resources.main.holdsLock("c"))
        resources.clipboard.lock("c")
        val paste = launch(start = CoroutineStart.UNDISPATCHED) { resources.clipboard.lock("d") }
        assertTrue(paste.isActive)
        resources.clipboard.unlock("c")
        paste.join()
        assertTrue(resources.clipboard.holdsLock("d"))
        resources.clipboard.unlock("d"); resources.release("c")
    }
}
