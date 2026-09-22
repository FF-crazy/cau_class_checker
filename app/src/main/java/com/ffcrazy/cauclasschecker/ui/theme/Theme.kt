package com.ffcrazy.cauclasschecker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 刻意**关闭动态取色**（Studio 模板默认开着）——这个 App 的配色是固定的，
 * 跟着系统壁纸变就没有统一观感了。也刻意只做浅色主题。
 */
private val CheckInColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenTint,
    onPrimaryContainer = GreenDeep,
    secondary = GreenDeep,
    onSecondary = Color.White,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = BoxBg,
    onSurfaceVariant = Muted,
    outline = Border,
    outlineVariant = Border,
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun CauCheckInTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CheckInColors,
        typography = Typography,
        content = content,
    )
}
