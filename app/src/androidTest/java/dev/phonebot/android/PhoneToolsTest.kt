package dev.phonebot.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.os.Parcel
import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phonebot.android.engine.CredentialVault
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    private fun call(tool: String, params: JSONObject = JSONObject(), success: Boolean = true): JSONObject {
        val latch = CountDownLatch(1)
        var code = -1
        var output = Bundle()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) { code = resultCode; output = resultData; latch.countDown() }
        }
        val parcel = Parcel.obtain()
        receiver.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val remoteReceiver = ResultReceiver.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        context.sendBroadcast(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestReceiver"))
            .putExtra("reply", remoteReceiver).putExtra("tool", tool).putExtra("params", params.toString()))
        assertTrue("Tool response timed out", latch.await(20, TimeUnit.SECONDS))
        assertEquals(output.getString("error", tool), if (success) 0 else 1, code)
        return if (success) JSONObject(output.getString("result")!!) else JSONObject().put("error", output.getString("error"))
    }
    @Test fun keystoreEncryptsPersistsUpdatesAndDeletesCredentials() {
        val vault = CredentialVault(context)
        val provider = "phonebot-test"
        try {
            vault.write(provider, JSONObject().put("type", "api_key").put("key", "private-key-中文"))
            val file = File(context.noBackupFilesDir, "credentials/$provider.bin")
            assertFalse(file.readText().contains("private-key"))
            assertEquals("private-key-中文", CredentialVault(context).read(provider)!!.getString("key"))
            val first = file.readBytes()
            vault.write(provider, JSONObject().put("type", "api_key").put("key", "private-key-中文"))
            assertFalse(first.contentEquals(file.readBytes()))
            vault.write(provider, JSONObject().put("type", "oauth").put("access", "access-token").put("refresh", "refresh-token").put("expires", 42))
            assertEquals("oauth", vault.list().objects().find { it.getString("providerId") == provider }!!.getString("type"))
        } finally { vault.delete(provider) }
        assertNull(vault.read(provider))
    }
    @Test fun accessibilityReadsActsVerifiesChineseAndRecognizesPixels() {
        val originalServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val component = "${context.packageName}/.phone.PhoneAccessibilityService"
        try {
            shell("settings put secure enabled_accessibility_services ${if (originalServices.isEmpty()) component else "$originalServices:$component"}")
            shell("settings put secure accessibility_enabled 1")
            val activity = instrumentation.startActivitySync(Intent().setComponent(ComponentName(context.packageName, "dev.phonebot.android.testing.PhoneTestActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(2000)
            var screen = call("screen_read")
            val input = screen.getJSONArray("elements").objects().first { it.optBoolean("editable") }.getString("id")
            val typed = call("input_text", JSONObject().put("element", input).put("text", "你好，PhoneBot"))
            assertTrue(typed.getBoolean("inputVerified"))
            assertTrue(typed.getJSONArray("elements").objects().any { it.optString("text") == "你好，PhoneBot" })
            assertTrue(call("tap", JSONObject().put("element", input), false).getString("error").contains("过期"))
            screen = call("tap", JSONObject().put("text", "确认点击"))
            assertTrue(screen.getJSONArray("elements").objects().any { it.optString("text") == "点击成功" })
            val image = call("screen_read", JSONObject().put("ocr", true).put("image", true))
            assertTrue(File(image.getString("image")).length() > 1000)
            val ocr = image.getJSONArray("ocr").objects().joinToString { it.getString("text") }
            assertTrue(ocr, ocr.contains("OCR") && (ocr.contains("识别") || ocr.contains("屏幕")))
            assertTrue(call("app_list").getJSONArray("apps").objects().any { it.getString("package") == context.packageName })
            call("tap", JSONObject().put("x", -1).put("y", 1), false)
            val height = screen.getInt("height"); val width = screen.getInt("width")
            call("swipe", JSONObject().put("x", width / 2).put("y", height * 3 / 4).put("endX", width / 2).put("endY", height / 2))
            call("home")
            assertEquals(context.packageName, call("app_launch", JSONObject().put("package", context.packageName)).getString("package"))
            instrumentation.runOnMainSync { activity.finish() }
        } finally {
            shell("settings put secure enabled_accessibility_services '$originalServices'")
            if (originalServices.isEmpty()) shell("settings put secure accessibility_enabled 0")
        }
    }
}
