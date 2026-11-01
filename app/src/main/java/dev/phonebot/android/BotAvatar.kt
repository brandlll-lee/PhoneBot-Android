package dev.phonebot.android

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.blur.*

@Composable internal fun BotAvatar(color: Long, modifier: Modifier = Modifier, working: Boolean = false, interactionSource: InteractionSource? = null) {
    val background = Color(color)
    Box(modifier.background(background, CircleShape), contentAlignment = Alignment.Center) {
        PhoneBotLogo(Modifier.fillMaxSize(.76f), working, interactionSource, if (background.luminance() > .179f) Color.Black else Color.White)
    }
}

internal fun Modifier.edgeBlur(state: HazeState, top: Boolean): Modifier = hazeBlur(
    input = HazeInput.Sources(state),
    style = HazeBlurStyle {
        blurRadius(PhoneUi.blurRadius)
        noiseFactor(0f)
        progressive(HazeProgressive.verticalGradient(startIntensity = if (top) 1f else 0f, endIntensity = if (top) 0f else 1f))
    },
)

@Composable internal fun BotEditor(model: PhoneBotModel, enabled: Boolean) {
    val bot = model.session ?: return
    var name by remember(bot.id, bot.title) { mutableStateOf(bot.title) }
    var hex by remember(bot.id, bot.avatarColor) { mutableStateOf("%06X".format(bot.avatarColor and 0xFFFFFF)) }
    val hsv = remember(bot.avatarColor) { FloatArray(3).also { android.graphics.Color.colorToHSV(bot.avatarColor.toInt(), it) } }
    Column(verticalArrangement = Arrangement.spacedBy(PhoneUi.small)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneUi.space)) {
            BotAvatar(bot.avatarColor, Modifier.size(PhoneUi.touch))
            OutlinedTextField(name, { name = it }, label = { Text("Bot 名字") }, enabled = enabled, singleLine = true, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = { model.editBot(name) }, enabled = enabled && name.isNotBlank() && name.trim() != bot.title) { Text("保存名字") }
        Row(horizontalArrangement = Arrangement.spacedBy(PhoneUi.small)) {
            listOf(0xFF3985FF, 0xFF8B5CF6, 0xFFF59E0B, 0xFF10B981, 0xFFEC4899, 0xFF555555).forEach { color ->
                IconButton(onClick = { model.editBot(color = color) }, enabled = enabled) { BotAvatar(color, Modifier.size(PhoneUi.send)) }
            }
        }
        listOf("色相" to 360f, "饱和度" to 1f, "明度" to 1f).forEachIndexed { index, (label, limit) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Slider(hsv[index], { value -> val changed = hsv.copyOf(); changed[index] = value; model.editBot(color = android.graphics.Color.HSVToColor(changed).toLong() and 0xFFFFFFFF) },
                    valueRange = 0f..limit, enabled = enabled, modifier = Modifier.weight(1f))
            }
        }
        OutlinedTextField(hex, { value ->
            hex = value.removePrefix("#").take(6)
            if (hex.length == 6) hex.toLongOrNull(16)?.let { model.editBot(color = it or 0xFF000000) }
        }, label = { Text("头像颜色 HEX") }, prefix = { Text("#") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}
