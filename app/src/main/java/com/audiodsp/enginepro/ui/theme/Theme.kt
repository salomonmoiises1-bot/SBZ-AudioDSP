package com.audiodsp.enginepro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = DspPrimary,
    secondary = DspAccent,
    background = DspBackground,
    surface = DspSurface,
    surfaceVariant = DspSurfaceVariant,
    onPrimary = DspBackground,
    onSecondary = DspBackground,
    onBackground = DspTextPrimary,
    onSurface = DspTextPrimary,
    error = DspRed
)

@Composable
fun AudioDSPEngineProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = DspTypography,
        content = content
    )
}
