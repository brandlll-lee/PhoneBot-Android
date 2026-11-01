package dev.phonebot.android

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class ParallelPhoneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    private fun call(owner: String, tool: String, params: JSONObject = JSONObject()): JSONObject {
        val latch = CountDownLatch(1)
        var code = -1
        var result = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                code = resultCode; result = resultData; latch.countDown()
            }
        }
        val parcel = Parcel.obtain()
        receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
        val reply = ResultReceiver.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        context.sendBroadcast(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestReceiver"))
            .putExtra("reply", reply).putExtra("owner", owner).putExtra("tool", tool).putExtra("params", params.toString()))
        assertTrue("$owner/$tool timed out", latch.await(50, TimeUnit.SECONDS))
        assertEquals("$owner/$tool: ${result.getString("error")}", 0, code)
        return JSONObject(result.getString("result")!!)
    }

    private fun withPhone(block: () -> Unit) {
        val services = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val enabled = shell("settings get secure accessibility_enabled").trim()
        val component = "${context.packageName}/.phone.PhoneAccessibilityService"
        val others = services.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(component) }.joinToString(":")
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -f 0x14000000 -n ${context.packageName}/.MainActivity")
            shell("input keyevent 3")
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
            shell("settings put secure enabled_accessibility_services ${if (others.isEmpty()) component else "$others:$component"}")
            shell("settings put secure accessibility_enabled 1")
            SystemClock.sleep(1500)
            val deadline = SystemClock.uptimeMillis() + 12000
            while (call("a", "helper_status", JSONObject().put("helper", "builtin")).optString("state") != "ready" && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(250)
            assumeTrue("Built-in helper is not connected", call("a", "helper_status", JSONObject().put("helper", "builtin")).optString("state") == "ready")
            block()
        } finally {
            for (owner in listOf("a", "b")) runCatching { call(owner, "test_release") }
            shell("input keyevent 3")
            shell(if (services.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $services")
            shell(if (enabled == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $enabled")
        }
    }

    private fun target(owner: String, screen: String = "virtual") = call(owner, "test_target", JSONObject().put("session", owner).put("helper", "builtin").put("screen", screen)).getInt("displayId")
    private fun input(owner: String, value: String): JSONObject = call(owner, "input_text", JSONObject().put("resourceId", "android:id/edit").put("text", value))
    private fun read(owner: String): JSONObject = call(owner, "screen_read")
    private fun fixture(owner: String, displayId: Int, packageName: String): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 10000
        var last = JSONObject()
        while (SystemClock.uptimeMillis() < deadline) {
            last = read(owner)
            val elements = last.getJSONArray("elements")
            if (last.optString("package") == packageName && (0 until elements.length()).any { elements.getJSONObject(it).optBoolean("editable") }) {
                assertEquals(last.toString(), displayId, last.getJSONObject("geometry").getInt("displayId"))
                return last
            }
            SystemClock.sleep(120)
        }
        throw AssertionError("$owner fixture not ready on $displayId for $packageName: $last")
    }
    private fun JSONObject.editableText(): String {
        val elements = getJSONArray("elements")
        return (0 until elements.length()).map(elements::getJSONObject).single { it.optBoolean("editable") }.getString("text")
    }

    @Test fun virtualBotsKeepChineseInputAndDisplayAfterTheOtherStops() = withPhone {
        val a = target("a")
        val b = target("b")
        assertTrue(a > 0 && b > 0 && a != b)
        call("a", "test_launch", JSONObject().put("pasteInput", true))
        fixture("a", a, context.packageName)
        call("b", "test_launch", JSONObject().put("package", instrumentation.context.packageName)
            .put("activity", ParallelFixtureActivity::class.java.name).put("pasteInput", true))
        fixture("b", b, instrumentation.context.packageName)
        fixture("a", a, context.packageName)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit<JSONObject> { input("a", "甲号 Bot 粤语音乐") }
            val second = threads.submit<JSONObject> { input("b", "乙号 Bot 独立备忘录") }
            assertTrue(first.get(40, TimeUnit.SECONDS).getBoolean("inputVerified"))
            assertTrue(second.get(40, TimeUnit.SECONDS).getBoolean("inputVerified"))
            assertEquals("甲号 Bot 粤语音乐", read("a").editableText())
            assertEquals("乙号 Bot 独立备忘录", read("b").editableText())
            call("a", "test_release")
            assertTrue(input("b", "乙号任务继续运行").getBoolean("inputVerified"))
            assertEquals(b, read("b").getJSONObject("geometry").getInt("displayId"))
            assertEquals("乙号任务继续运行", read("b").editableText())
        } finally { threads.shutdownNow() }
    }

    @Test fun sharedAppWaitsUntilItsOwnerReleasesIt() = withPhone {
        val a = target("a"); val b = target("b")
        call("a", "test_launch")
        fixture("a", a, context.packageName)
        val threads = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val waiting = threads.submit<JSONObject> { started.countDown(); call("b", "test_launch") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            assertFalse(waiting.isDone)
            call("a", "test_release")
            val result = waiting.get(20, TimeUnit.SECONDS)
            assertEquals(b, result.getJSONObject("geometry").getInt("displayId"))
            fixture("b", b, context.packageName)
            assertTrue(input("b", "接管后独立输入").getBoolean("inputVerified"))
        } finally { threads.shutdownNow() }
    }

    @Test fun mainScreenWaitsUntilTheRunningBotReleasesIt() = withPhone {
        assertEquals(0, target("a", "main"))
        val threads = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val waiting = threads.submit<Int> { started.countDown(); target("b", "main") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            assertFalse(waiting.isDone)
            call("a", "test_release")
            assertEquals(0, waiting.get(20, TimeUnit.SECONDS).toInt())
        } finally { threads.shutdownNow() }
    }
}
