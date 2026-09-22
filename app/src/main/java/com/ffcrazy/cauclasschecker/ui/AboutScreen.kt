package com.ffcrazy.cauclasschecker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.R
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

private const val REPO_URL = "https://github.com/FF-crazy/cau_class_checker"

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    // 开源协议是二级界面，在这里就地切；用户协议是个浮层，不切页面
    var showLicense by rememberSaveable { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }

    if (showLicense) {
        BackHandler { showLicense = false }
        LicenseScreen(onBack = { showLicense = false }, modifier = modifier)
    } else {
        AboutContent(
            onOpenAgreement = { showAgreement = true },
            onOpenLicense = { showLicense = true },
            modifier = modifier,
        )
        if (showAgreement) {
            UserAgreementDialog(onDismiss = { showAgreement = false })
        }
    }
}

@Composable
private fun AboutContent(
    onOpenAgreement: () -> Unit,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(52.dp))

        AppIcon()

        Spacer(Modifier.height(18.dp))

        // 跟着 app_name 走，不写死：改应用名时这里会自动跟上
        Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)

        Spacer(Modifier.height(48.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            AboutRow(R.drawable.ic_agreement, "用户协议", onOpenAgreement)
            RowDivider()
            AboutRow(R.drawable.ic_license, "开源协议", onOpenLicense)
            RowDivider()
            AboutRow(R.drawable.ic_repo, "开源仓库") { openUrl(context, REPO_URL) }
        }

        // 把版本号顶到最下面
        Spacer(Modifier.weight(1f))

        Text(
            "V$version",
            fontSize = 12.sp,
            color = Muted.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 应用图标。
 *
 * 用的是 `drawable-nodpi/ic_launcher_cat` —— 启动器图标那张位图**本体**。
 * 不能写 `@mipmap/ic_launcher`：那是自适应图标的 XML，里面是 `<adaptive-icon>`，
 * `painterResource` 解不了（它不是可绘制的路径）。位图本体放在 nodpi 下，
 * 正好也是给这种场景用的。
 */
@Composable
private fun AppIcon() {
    val shape = RoundedCornerShape(20.dp)
    Image(
        painter = painterResource(R.drawable.ic_launcher_cat),
        contentDescription = null,
        modifier = Modifier
            .size(88.dp)
            .clip(shape)
            .border(1.dp, Border, shape),
    )
}

@Composable
private fun AboutRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = GreenDeep,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = Border.copy(alpha = 0.6f), thickness = 1.dp)
}

/**
 * 用户协议浮层。
 *
 * 正文就是启动时那个免责声明 —— 同一个 [DISCLAIMER_TEXT]、同一套排版，
 * 用户在哪儿点开看到的都是同一段话。
 */
@Composable
private fun UserAgreementDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("用户协议", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        },
        text = { DisclaimerBody() },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = GreenDeep, fontWeight = FontWeight.Medium)
            }
        },
    )
}

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
