package com.yoruc.mangacracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// AniList's dark navy palette with atsu-style soft cards.
object Mc {
    val Bg = Color(0xFF0B1622)
    val Card = Color(0xFF151F2E)
    val Card2 = Color(0xFF1C2838)
    val Line = Color(0xFF243246)
    val Blue = Color(0xFF3DB4F2)
    val BlueSoft = Color(0xFF2B6E94)
    val Pink = Color(0xFFFF5C8A)
    val Green = Color(0xFF7BD555)
    val Orange = Color(0xFFF79A63)
    val Text = Color(0xFFEDF1F5)
    val Text2 = Color(0xFF9FADBD)
    val Text3 = Color(0xFF6B7C8F)

    /** Pastel pill colour for a genre name (stable per name), like AniList's genre tags. */
    fun genreColor(name: String): Color {
        val hues = listOf(0xFF3DB4F2, 0xFFC063FF, 0xFFFF5C8A, 0xFFF79A63, 0xFF7BD555, 0xFF4CCFC4, 0xFFE8B649, 0xFF8C9EFF)
        return Color(hues[(name.lowercase().hashCode() and 0x7fffffff) % hues.size])
    }
}

private val scheme = darkColorScheme(
    primary = Mc.Blue,
    onPrimary = Color(0xFF06121C),
    primaryContainer = Mc.BlueSoft,
    onPrimaryContainer = Mc.Text,
    secondary = Mc.Pink,
    onSecondary = Color.White,
    secondaryContainer = Mc.Card2,
    onSecondaryContainer = Mc.Text,
    tertiary = Mc.Green,
    background = Mc.Bg,
    onBackground = Mc.Text,
    surface = Mc.Bg,
    onSurface = Mc.Text,
    surfaceVariant = Mc.Card,
    onSurfaceVariant = Mc.Text2,
    surfaceContainer = Mc.Card,
    surfaceContainerLow = Mc.Card,
    surfaceContainerHigh = Mc.Card2,
    surfaceContainerHighest = Mc.Card2,
    surfaceContainerLowest = Mc.Bg,
    outline = Mc.Line,
    outlineVariant = Mc.Line,
    error = Color(0xFFFF6B6B),
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MangaCrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
