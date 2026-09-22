package com.ffcrazy.cauclasschecker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 刻意**关闭动态取色**（Studio 模板默认开着）。
 *
 * 这个 App 的视觉标识就是页头那条荧光绿，跟着系统壁纸变色就没意义了。
 * 也刻意只做浅色主题——网页版就只有一套配色，没有深色模式。
 */
private val CheckInColors = lightColorScheme(
    primary = GreenDark,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Ink,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = UrlBoxBg,
    onSurfaceVariant = Muted,
    outline = Border,
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
