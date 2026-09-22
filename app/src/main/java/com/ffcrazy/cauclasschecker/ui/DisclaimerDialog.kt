package com.ffcrazy.cauclasschecker.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

/**
 * 免责声明全文。
 *
 * 启动弹框和「关于 → 用户协议」**共用这一份**。两处各写一遍的话，
 * 迟早会改了一处忘了另一处 —— 而这两处对用户说的必须是同一件事。
 */
internal const val DISCLAIMER_TEXT =
    "此软件由一只猴子不断打字生成，与本人无关，本人对此软件作用毫不知情，" +
        "使用此软件造成的一切后果与本人无关，本人不对此软件的一切负责，" +
        "使用此软件代表您认同以上所有条款。"

/**
 * 免责声明正文排版。启动弹框和关于页共用，保证两处看起来也是同一段话。
 *
 * 加 `verticalScroll` 是给大字体的：固定高度会在系统字号开大时把末尾的
 * 「代表您认同以上所有条款」裁掉 —— 偏偏那是唯一一句有实际含义的话。
 */
@Composable
internal fun DisclaimerBody() {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(DISCLAIMER_TEXT, fontSize = 13.sp, color = Muted, lineHeight = 20.sp)
    }
}

/**
 * 启动时的免责声明。**每次打开软件都会出现**，不是只有第一次。
 *
 * 「每次都弹」靠的是**不把它落盘**：这里没有 SharedPreferences / DataStore，
 * 状态只活在这一次进程里。用 `rememberSaveable` 而不是 `remember`，
 * 是为了转屏时不重复弹 —— 转屏是同一场会话，不是「又开了一次软件」。
 *
 * 两个按钮就是仅有的两个出口：点框外、按返回键都**不作数**
 * （见 `DialogProperties`）。否则这个框按一下返回就没了，
 * 等于给了个白嫖的口子，「必须认同才能用」也就不成立了。
 */
@Composable
fun DisclaimerDialog(onAgree: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("免责声明", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        },
        text = { DisclaimerBody() },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text("同意", color = GreenDeep, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = { context.findActivity()?.finishAffinity() }) {
                Text("拒绝", color = Muted)
            }
        },
    )
}

/**
 * 顺着一层层 `ContextWrapper` 找到宿主 Activity。
 *
 * `LocalContext.current` 拿到的**不一定**就是 Activity —— 主题、`LocalContext`
 * 的提供方都可能在中间套一层 wrapper。直接 `as Activity` 一旦不成立就是
 * `ClassCastException`；用 `as?` 则是「拒绝」按钮默默失效，用户点了没反应，
 * 比崩溃还难查。所以老老实实往上找。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
