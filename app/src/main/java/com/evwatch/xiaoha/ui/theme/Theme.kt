package com.evwatch.xiaoha.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// —— 参考截图的深色科技风配色 ——
object EvColors {
    val Bg = Color(0xFF0F172A)          // 页面背景
    val Card = Color(0xFF1E293B)        // 卡片
    val CellBg = Color(0xFF222D3F)      // 单体格子
    val CellBorder = Color(0xFF344152)  // 格子描边
    val Accent = Color(0xFF00E5FF)      // 强调青
    val Green = Color(0xFF0EBA82)       // 满电绿 / 大数字
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF95A2B5) // 次要文字
    val TextMuted = Color(0xFF64748B)
    val Warn = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
}

private val DarkColors = darkColorScheme(
    primary = EvColors.Accent,
    onPrimary = Color(0xFF001B21),
    primaryContainer = Color(0xFF0B3A44),
    onPrimaryContainer = EvColors.Accent,
    secondary = EvColors.Green,
    onSecondary = Color(0xFF00271D),
    background = EvColors.Bg,
    onBackground = EvColors.TextPrimary,
    surface = EvColors.Card,
    onSurface = EvColors.TextPrimary,
    surfaceVariant = EvColors.CellBg,
    onSurfaceVariant = EvColors.TextSecondary,
    outline = EvColors.CellBorder
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E8F5),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF0EBA82),
    onSecondary = Color.White,
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE5EBF0),
    onSurfaceVariant = Color(0xFF56636E),
    outline = Color(0xFFCBD5E1)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun EvWatchTheme(
    darkTheme: Boolean = true,   // 默认深色，贴合参考图
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
