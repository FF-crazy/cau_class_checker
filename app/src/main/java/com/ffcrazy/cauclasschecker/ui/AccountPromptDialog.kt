package com.ffcrazy.cauclasschecker.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted
import com.ffcrazy.cauclasschecker.web.AccountRecord

/**
 * 手上有没有**能拿去签到**的账号。
 *
 * 「有账号」还不够 —— 一条记录要真的能用，得同时满足：
 *
 *  1. [AccountRecord.valid]：没被验活或签到标记为失效；
 *  2. **带着 Cookie**：`valid` 只是个缓存的标记，空 Cookie 的记录点下去也签不上。
 *
 * 只判断「列表空不空」会漏掉最该提醒的那种情况：**账号都还在，但会话全过期了**。
 * 那时用户打开 App 看见列表里有东西，以为没事，点签到却一个都签不上。
 *
 * 抽成纯函数是为了能单测 —— 这里是几条布尔分支的组合，正是最容易写错、
 * 又最不值得为它开一次真机验证的地方。
 */
internal fun hasUsableAccount(accounts: List<AccountRecord>): Boolean =
    accounts.any { it.valid && it.cookies.isNotEmpty() }

/**
 * 启动时一个能用的账号都没有 —— 提醒去「账号管理」加一个。
 *
 * 只提醒，不拦路：用户点「稍后」照常能用二维码页（扫码、粘贴链接、
 * 生成分享链接这些都不需要账号）。
 */
@Composable
fun AccountPromptDialog(
    onGoToAccounts: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("还没有可用的账号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        },
        text = {
            Text(
                "签到要用你自己的统一身份认证账号。去「账号管理」登录一个，" +
                    "会话会加密存在本机，之后签到就不用再登了。",
                fontSize = 13.sp,
                color = Muted,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToAccounts) {
                Text("去添加", color = GreenDeep, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后", color = Muted)
            }
        },
    )
}
