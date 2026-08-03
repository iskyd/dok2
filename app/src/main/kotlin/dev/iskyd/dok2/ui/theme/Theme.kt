package dev.iskyd.dok2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's Material 3 light color scheme: a muted outdoor palette. Deliberately minimal — no
 * dynamic color, no dark scheme — matching the "battery and speed first" priorities (a fixed light
 * theme avoids system-ui re-renders and keeps the theme deterministic).
 */
private val Dok2ColorScheme =
    lightColorScheme(
        primary = Color(0xFF3A6B4F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC4EBD2),
        onPrimaryContainer = Color(0xFF07371F),
        secondary = Color(0xFF52634F),
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFFF4F1E8),
        onBackground = Color(0xFF1B1C18),
        surface = Color(0xFFF4F1E8),
        onSurface = Color(0xFF1B1C18),
        surfaceVariant = Color(0xFFE3E4DB),
        onSurfaceVariant = Color(0xFF46483F),
        error = Color(0xFFBA1A1A),
    )

@Composable
fun Dok2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dok2ColorScheme, content = content)
}
