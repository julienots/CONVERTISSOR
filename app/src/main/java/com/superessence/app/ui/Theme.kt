package com.superessence.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.superessence.app.core.Prefs

/** Une palette sobre : fond neutre, une seule couleur d'accent. */
data class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val dark: Boolean,
)

/** Couleurs d'accent proposées dans les réglages (nom, clair, sombre). */
val Accents = listOf(
    Triple("Bleu", Color(0xFF2563EB), Color(0xFF7AA7FF)),
    Triple("Vert", Color(0xFF15803D), Color(0xFF6BCB8B)),
    Triple("Orange", Color(0xFFC2410C), Color(0xFFF5A35C)),
    Triple("Violet", Color(0xFF6D28D9), Color(0xFFB79CFF)),
    Triple("Rose", Color(0xFFBE185D), Color(0xFFF38BB8)),
    Triple("Gris", Color(0xFF374151), Color(0xFFC9CED6)),
)

private fun lightPalette(accent: Color) = Palette(
    bg = Color(0xFFF5F5F4), surface = Color(0xFFFFFFFF), surfaceHi = Color(0xFFE9E8E6),
    text = Color(0xFF1C1B1A), muted = Color(0xFF6B6966), accent = accent, onAccent = Color.White, dark = false,
)

private fun darkPalette(accent: Color) = Palette(
    bg = Color(0xFF151515), surface = Color(0xFF1F1F1F), surfaceHi = Color(0xFF2C2C2C),
    text = Color(0xFFECECEC), muted = Color(0xFFA3A3A3), accent = accent, onAccent = Color(0xFF111111), dark = true,
)

/**
 * Couleurs utilisées par tous les écrans. Les valeurs suivent le thème choisi
 * (clair / sombre / système) et la couleur d'accent.
 */
object SE {
    internal var p by mutableStateOf(darkPalette(Accents[0].third))

    val Bg get() = p.bg
    val Surface get() = p.surface
    val SurfaceHi get() = p.surfaceHi
    val Text get() = p.text
    val Muted get() = p.muted
    /** Couleur principale (anciennement cyan). */
    val Cyan get() = p.accent
    val Accent get() = p.accent
    val OnAccent get() = p.onAccent
    val Dark get() = p.dark

    // Couleurs « fonctionnelles », un peu atténuées pour rester lisibles sur les deux thèmes.
    val Blue get() = if (p.dark) Color(0xFF7AA7FF) else Color(0xFF2563EB)
    val Purple get() = if (p.dark) Color(0xFFB79CFF) else Color(0xFF6D28D9)
    val Green get() = if (p.dark) Color(0xFF6BCB8B) else Color(0xFF15803D)
    val Red get() = if (p.dark) Color(0xFFFF8A80) else Color(0xFFC62828)
    val Orange get() = if (p.dark) Color(0xFFF5B35C) else Color(0xFFB45309)
    val Teal get() = if (p.dark) Color(0xFF5FD4C4) else Color(0xFF0F766E)
}

@Composable
fun SuperEssenceTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val a = Accents.getOrElse(Prefs.accent) { Accents[0] }
    val palette = if (dark) darkPalette(a.third) else lightPalette(a.second)
    if (SE.p != palette) SE.p = palette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            secondary = palette.accent, onSecondary = palette.onAccent,
            background = palette.bg, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surfaceHi, onSurfaceVariant = palette.muted,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceHi,
            surfaceContainerHighest = palette.surfaceHi, surfaceContainerLow = palette.surface,
            outline = palette.muted, outlineVariant = palette.surfaceHi,
            error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            secondary = palette.accent, onSecondary = palette.onAccent,
            background = palette.bg, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surfaceHi, onSurfaceVariant = palette.muted,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceHi,
            surfaceContainerHighest = palette.surfaceHi, surfaceContainerLow = palette.surface,
            outline = palette.muted, outlineVariant = palette.surfaceHi,
            error = Color(0xFFC62828),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
