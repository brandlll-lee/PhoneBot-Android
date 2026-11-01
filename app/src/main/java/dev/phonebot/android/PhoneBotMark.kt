package dev.phonebot.android

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope

private val PhoneFrame = Path().apply {
    fillType = PathFillType.EvenOdd
    addRoundRect(RoundRect(48f, 28f, 208f, 228f, CornerRadius(40f)))
    addRoundRect(RoundRect(58f, 38f, 198f, 218f, CornerRadius(30f)))
}

internal fun DrawScope.drawPhoneBot(ink: Color, blink: Float, gaze: Float, wink: Float) {
    drawPath(PhoneFrame, ink)
    drawRoundRect(ink, Offset(104f, 48f), Size(48f, 10f), CornerRadius(5f))
    drawRoundRect(ink, Offset(110f, 200f), Size(36f, 5f), CornerRadius(2.5f))
    drawRoundRect(ink, Offset(140f + gaze, 106f + 18f * (1 - blink)), Size(28f, 36f * blink), CornerRadius(14f))
    val leftBlink = blink * (1 - .92f * wink)
    drawRoundRect(ink, Offset(88f + gaze, 106f + 18f * (1 - leftBlink)), Size(28f, 36f * leftBlink), CornerRadius(14f))
}
