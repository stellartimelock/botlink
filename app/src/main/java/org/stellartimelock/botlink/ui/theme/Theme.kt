package org.stellartimelock.botlink.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Aurora Glass — one dark glass theme. No theme picker. */
val DeepSpace = Color(0xFF0B1020)
val Cream = Color(0xFFE8EEF9)
val Gold = Color(0xFF5EEAD4)
val GoldInk = Color(0xFF06221C)
val Teal = Color(0xFF5EEAD4)
val Violet = Color(0xFFC4B5FD)
val Muted = Color(0xFFC5D0E6)
val Stamp = Color(0xFF8EA0C0)

private val Panel = Color(0xFF141A2E)
private val Danger = Color(0xFFE07A6A)

private val Colors = darkColorScheme(
    primary = Gold,
    onPrimary = GoldInk,
    secondary = Teal,
    onSecondary = DeepSpace,
    background = DeepSpace,
    onBackground = Cream,
    surface = Panel,
    onSurface = Cream,
    surfaceVariant = Color(0xFF1C1A26),
    onSurfaceVariant = Muted,
    error = Danger,
    onError = DeepSpace,
    outline = Color(0xFF3A342C),
)

private val Type = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
)

private val BotLinkShapes = Shapes(
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(18.dp),
)

@Suppress("DEPRECATION")
private fun paintSystemBars(window: android.view.Window) {
    window.statusBarColor = DeepSpace.toArgb()
    window.navigationBarColor = DeepSpace.toArgb()
}

@Composable
fun BotLinkTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            paintSystemBars(window)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = Colors,
        typography = Type,
        shapes = BotLinkShapes,
        content = content,
    )
}
