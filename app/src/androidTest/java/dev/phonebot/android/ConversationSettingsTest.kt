package dev.phonebot.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class ConversationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appearanceChangesPersistAndUpdateSystemBars() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        val original = model.appearance
        try {
            compose.onNodeWithContentDescription("更多选项").performClick()
            compose.onNodeWithText("设置").performClick()
            compose.onNode(hasText("外观") and hasClickAction()).performClick()
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK, Appearance.SYSTEM)) {
                compose.onNode(hasText(mode.label) and hasClickAction()).performClick()
                compose.runOnIdle {
                    assertEquals(mode, model.appearance)
                    assertEquals(mode.name, compose.activity.getSharedPreferences("phonebot", 0).getString("appearance", null))
                    if (mode != Appearance.SYSTEM) assertEquals(mode == Appearance.LIGHT,
                        androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        } finally { compose.runOnIdle { model.chooseAppearance(original) } }
    }

    @Test fun settingsRowsChangeTheSessionHelperAndScreen() {
        lateinit var model: PhoneBotModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhoneBotModel::class.java] }
        compose.waitUntil(10000) { model.session != null }
        val original = checkNotNull(model.session)
        assertEquals("", model.activeTask)
        try {
            compose.onNodeWithContentDescription("选择屏幕").performClick()
            compose.onNodeWithText("会话设置").performClick()
            for ((label, id) in listOf("内置助手" to "builtin", "Shizuku" to "shizuku")) {
                compose.onNode(hasText(label) and hasClickAction()).assertIsEnabled().performClick()
                compose.onNode(hasText(label) and hasText("已选择")).assertIsDisplayed()
                compose.runOnIdle { assertEquals(id, model.session?.helper) }
            }
            for ((label, id) in listOf("主屏幕" to "main", "虚拟屏幕" to "virtual")) {
                compose.onNode(hasText(label) and hasClickAction() and !hasContentDescription("选择屏幕"))
                    .assertIsEnabled().performClick()
                compose.onNode(hasText(label) and hasText("已选择")).assertIsDisplayed()
                compose.runOnIdle { assertEquals(id, model.session?.screen) }
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("版本").assertHasNoClickAction()
            compose.onNodeWithText("引擎").assertHasNoClickAction()
        } finally {
            compose.runOnIdle { model.choosePhone(original.helper, original.screen) }
            compose.waitUntil(5000) {
                runCatching {
                    JSONArray(File(compose.activity.filesDir, "chats.json").readText()).objects()
                        .first { it.getString("id") == original.id }
                        .let { it.getString("helper") == original.helper && it.getString("screen") == original.screen }
                }.getOrDefault(false)
            }
        }
    }
}
