package dev.phonebot.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.phone.PhoneIntents
import dev.phonebot.android.phone.PhoneShell
import dev.phonebot.android.phone.PhoneSystem
import dev.phonebot.android.phone.ScreenState
import dev.phonebot.android.phone.SystemShellService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class ScreenService(context: Context) : AccessibilityService() {
        init { attachBaseContext(context) }
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? = null
        override fun getWindows(): List<AccessibilityWindowInfo> = emptyList()
    }

    @Test fun emptyTreeIsAnObservationWithUsableDisplayGeometry() {
        val screen = ScreenState(ScreenService(context))
        screen.taskId = "current-task"
        val result = screen.scan()
        assertFalse(result.getBoolean("treeReadable"))
        assertTrue(result.isNull("package"))
        assertEquals("current-task", result.getString("taskId"))
        assertTrue(result.getLong("observedAt") > 0)
        assertEquals(0, result.getJSONArray("elements").length())
        assertTrue(result.getInt("height") > 0)
        screen.geometry.point(0.0, 0.0)
        assertFalse(screen.condition(JSONObject().put("text", "absent").put("absent", true)))
        assertEquals("full", screen.present(true).getString("kind"))
    }

    @Test fun nativeSettingsReadBackAndCommandsReportActualResults() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val previous = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val system = PhoneSystem(context, PhoneShell(context))
        try {
            val status = system.execute("device_status", JSONObject())
            assertEquals(previous, status.getJSONObject("volume").getInt("value"))
            val result = system.execute("volume_set", JSONObject().put("percent", 50))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(audio.getStreamVolume(AudioManager.STREAM_MUSIC), result.getInt("value"))
            assertTrue(result.getBoolean("verified"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { system.execute("volume_set", JSONObject().put("percent", 101)) } }
            val shell = JSONObject(SystemShellService().execute("printf 'hello中文'; exit 3"))
            assertEquals(3, shell.getInt("exitCode"))
            assertFalse(shell.getBoolean("ok"))
            assertEquals("hello中文", shell.getString("output"))
            val capped = JSONObject(SystemShellService().execute("yes x | head -c 40000"))
            assertEquals(32768, capped.getString("output").length)
            assertTrue(capped.getBoolean("truncated"))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
    }

    @Test fun settingsEntryChecksActionsAndLinksBeforeLaunching() {
        assertTrue(PhoneIntents.settings.contains("android.settings.SOUND_SETTINGS"))
        assertThrows(IllegalArgumentException::class.java) {
            PhoneIntents.start(context, "phone_open_link", JSONObject().put("action", "android.settings.UNKNOWN"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneIntents.start(context, "phone_open_link", JSONObject().put("action", "android.settings.APPLICATION_DETAILS_SETTINGS"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneIntents.start(context, "phone_open_link", JSONObject().put("uri", "javascript:alert(1)"))
        }
    }

    @Test fun systemSettingsRespectAuthorizationAndReadBackChanges() = runBlocking {
        val system = PhoneSystem(context, PhoneShell(context))
        val resolver = context.contentResolver
        if (Settings.System.canWrite(context)) {
            val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            try {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { system.execute("brightness_set", JSONObject().put("percent", 101)) } }
                assertEquals(mode, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
                val result = system.execute("brightness_set", JSONObject().put("percent", 50))
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals(128, result.getInt("value"))
                assertFalse(result.getBoolean("automatic"))
            } finally {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
        } else assertEquals("write_settings", system.execute("brightness_set", JSONObject().put("percent", 50)).getString("permission"))
        val shell = PhoneShell(context)
        if (shell.ready()) {
            val result = shell.execute("id -u")
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.getInt("uid") in listOf(0, 2000))
            assertEquals(result.getInt("uid").toString(), result.getString("output").trim())
            android.util.Log.i("PhoneBotTest", "Authorized system shell UID: ${result.getInt("uid")}")
            val timeout = shell.execute("sleep 12")
            assertTrue(timeout.toString(), timeout.getBoolean("timedOut"))
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val enabled = wifi.isWifiEnabled
            try {
                val changed = system.execute("wifi_toggle", JSONObject().put("on", !enabled))
                assertTrue(changed.toString(), changed.getBoolean("verified"))
                assertEquals(!enabled, wifi.isWifiEnabled)
            } finally { system.execute("wifi_toggle", JSONObject().put("on", enabled)) }
        } else assertEquals("shizuku", shell.execute("id -u").getString("permission"))
    }

    @Test fun clockAndCalendarDispatchKeepTheirUnitsAndRequireVerification() = runBlocking {
        lateinit var launched: Intent
        val editor = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        val system = PhoneSystem(editor, PhoneShell(context))
        val alarm = system.execute("phone_set_alarm", JSONObject().put("hour", 8).put("minute", 30).put("label", "起床"))
        assertFalse(alarm.getBoolean("verified"))
        assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, launched.action)
        assertEquals(30, launched.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(system.execute("phone_set_timer", JSONObject().put("seconds", 3600)).getBoolean("verified"))
        assertEquals(3600, launched.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, -1))
        val begin = 1800000000000L
        assertFalse(system.execute("phone_add_calendar_event", JSONObject().put("title", "开会").put("beginTime", begin).put("endTime", begin + 3600000)).getBoolean("verified"))
        assertEquals(Intent.ACTION_INSERT, launched.action)
        assertEquals(begin, launched.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals("开会", launched.getStringExtra(android.provider.CalendarContract.Events.TITLE))
    }
}
