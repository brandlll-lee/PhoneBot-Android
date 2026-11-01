package dev.phonebot.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Appearance(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}

internal object PhoneAppearance { var mode by mutableStateOf(Appearance.SYSTEM) }

internal object PhoneUi {
    val tiny = 4.dp
    val small = 8.dp
    val inset = 12.dp
    val space = 16.dp
    val gap = 24.dp
    val touch = 48.dp
    val icon = 22.dp
    val logo = 24.dp
    val bubble = 40.dp
    val activityDot = 7.dp
    val hairline = .5.dp
    val blurRadius = 20.dp
    val floatingElevation = 1.dp
    const val glassAlpha = .68f
    val frameLine = 1.dp
    val glowRadius = 8.dp
    const val glowAlpha = .4f
    val previewWidth = 280.dp
    const val previewHeightFraction = .70f
    const val bubbleWidthFraction = .88f
    val composerEnd = 64.dp
    val send = 32.dp
    val attachmentHeight = 240.dp
    val rowHeight = 64.dp
    val rowValueWidth = 110.dp
    val rowLeadingInset = 58.dp
    const val disabledAlpha = .38f
    const val hintAlpha = .7f
    val hint = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
    val answerBody = TextStyle(fontSize = 17.sp, lineHeight = 28.sp)
    val answerHeading1 = answerBody.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    val answerHeading2 = answerBody.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    val answerHeading3 = answerBody.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val code = answerBody.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    val traceHeading = TextStyle(fontSize = 15.sp)
    val traceBody = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val traceCode = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val traceImageShape = RoundedCornerShape(12.dp)
    val frameShape = RoundedCornerShape(20.dp)
    val frameContentShape = RoundedCornerShape(19.dp)
}

private val Blue = Color(0xFF3985FF)
private val DarkColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black, primaryContainer = Color(0xFF1C1C1C), onPrimaryContainer = Color.White,
    secondary = Color(0xFF77C69B), onSecondary = Color.Black, secondaryContainer = Color(0xFF1C1C1C), onSecondaryContainer = Color.White,
    tertiary = Blue, onTertiary = Color.White, tertiaryContainer = Color(0xFF102442), onTertiaryContainer = Color(0xFFA9CCFF),
    background = Color.Black, onBackground = Color(0xFFF4F4F4), surface = Color(0xFF080808), onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF151515), onSurfaceVariant = Color(0xFF999999), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF111111), surfaceContainer = Color(0xFF151515),
    surfaceContainerHigh = Color(0xFF1A1A1A), surfaceContainerHighest = Color(0xFF222222),
    outline = Color(0xFF555555), outlineVariant = Color(0xFF292929),
)
private val LightColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White, primaryContainer = Color(0xFFEAEAEA), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF287B52), onSecondary = Color.White, secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Color.Black,
    tertiary = Blue, onTertiary = Color.White, tertiaryContainer = Color(0xFFE4EEFF), onTertiaryContainer = Color(0xFF17457F),
    background = Color.White, onBackground = Color(0xFF111111), surface = Color(0xFFFCFCFC), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF686868), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F5F5), surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED), surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFF999999), outlineVariant = Color(0xFFE0E0E0),
)
private val PhoneTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = PhoneUi.hint,
)

@Composable internal fun PhoneBotTheme(appearance: Appearance = PhoneAppearance.mode, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.DARK -> true; Appearance.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = PhoneTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
