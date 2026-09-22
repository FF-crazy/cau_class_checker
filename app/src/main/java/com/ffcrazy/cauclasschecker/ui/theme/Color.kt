package com.ffcrazy.cauclasschecker.ui.theme

import androidx.compose.ui.graphics.Color

// 低对比度的绿色系。主色取 Material 基准绿 #4CAF50，
// 其余用中性灰白托底，避免大面积高饱和色块造成的刺眼感。
val Green = Color(0xFF4CAF50)        // 主色：主要按钮、强调
val GreenDark = Color(0xFF388E3C)    // 按下态 / 深色变体
val GreenDeep = Color(0xFF2E7D32)    // 图标、需要更高对比的文字
val GreenTint = Color(0xFFE8F5E9)    // 极淡绿，用作轻微底色

val Bg = Color(0xFFF6F7F6)           // 页面背景
val Ink = Color(0xFF212121)          // 正文
val Muted = Color(0xFF757575)        // 次要文字
val Border = Color(0xFFE2E4E2)       // 分隔线 / 描边
val BoxBg = Color(0xFFF2F3F2)        // URL、元信息底色
val ErrorRed = Color(0xFFD32F2F)
