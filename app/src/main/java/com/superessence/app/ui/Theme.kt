package com.superessence.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object SE {
    val Bg = Color(0xFF050A1E)
    val Surface = Color(0xFF0D1634)
    val SurfaceHi = Color(0xFF16224A)
    val Cyan = Color(0xFF38BDF8)
    val Blue = Color(0xFF2563EB)
    val Purple = Color(0xFFA855F7)
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF4444)
    val Orange = Color(0xFFF59E0B)
    val Text = Color(0xFFE8EEFF)
    val Muted = Color(0xFF94A3C8)
    val glow = Brush.linearGradient(listOf(Cyan, Blue, Purple))
}

private val scheme = darkColorScheme(
    primary = SE.Cyan,
    onPrimary = Color(0xFF00121F),
    secondary = SE.Purple,
    onSecondary = Color.White,
    tertiary = SE.Orange,
    background = SE.Bg,
    onBackground = SE.Text,
    surface = SE.Surface,
    onSurface = SE.Text,
    surfaceVariant = SE.SurfaceHi,
    onSurfaceVariant = SE.Muted,
    surfaceContainer = SE.Surface,
    surfaceContainerHigh = SE.SurfaceHi,
    error = SE.Red,
)

@Composable
fun SuperEssenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
