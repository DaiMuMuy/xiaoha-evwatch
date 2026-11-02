package com.evwatch.xiaoha.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Cyan = Color(0xFF0E7490)
private val CyanLight = Color(0xFF22D3EE)

private val LightColors = lightColorScheme(
    primary = Cyan,
    secondary = CyanLight
)

private val DarkColors = darkColorScheme(
    primary = CyanLight,
    secondary = Cyan
)

@Composable
fun EvWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
