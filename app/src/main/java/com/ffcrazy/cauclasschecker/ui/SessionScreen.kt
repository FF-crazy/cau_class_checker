package com.ffcrazy.cauclasschecker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ffcrazy.cauclasschecker.CheckInUiState
import com.ffcrazy.cauclasschecker.AccountViewModel
import com.ffcrazy.cauclasschecker.CheckInProgress
import com.ffcrazy.cauclasschecker.CheckInViewModel
import com.ffcrazy.cauclasschecker.qr.QrEncoder
import com.ffcrazy.cauclasschecker.web.CasClient
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.BoxBg
import com.ffcrazy.cauclasschecker.ui.theme.ErrorRed
import com.ffcrazy.cauclasschecker.ui.theme.Green
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

/**
 * 二级页面：拿到会话之后才有这一页。
 *
 * 二维码是绝对主角，其余信息都退到背景里——这一页在教室里是举着给扫码器看的，
 * 屏幕亮度和二维码清晰度比任何文案都重要。
 */
@Composable
fun SessionScreen(
    vm: CheckInViewModel,
    accountVm: AccountViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val accountState by accountVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current.density

    // 举着给扫码器看的页面，屏幕绝不能熄
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        SessionHeader(onBack = onBack)
        HorizontalDivider(color = Border, thickness = 1.dp)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            QrCode(state = state, density = density, vm = vm)

            Spacer(Modifier.height(14.dp))

            UrlBox(state.url)

            Spacer(Modifier.height(10.dp))

            SessionMeta(state)

            Spacer(Modifier.height(18.dp))

            Controls(
                state = state,
                vm = vm,
                context = context,
                accountCount = accountState.accounts.size,
                onCheckInAll = { state.session?.let(accountVm::checkInAll) },
            )
        }
    }

    // 批量签到的进度与结果
    accountState.checkIn?.let { progress ->
        CheckInResultDialog(progress = progress, onDismiss = { accountVm.dismissCheckIn() })
    }
}

/** 批量签到的结果弹窗。跑的过程中显示进度，不可关闭。 */
@Composable
private fun CheckInResultDialog(progress: CheckInProgress, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!progress.running) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                if (progress.running) "正在签到…（${progress.done}/${progress.total}）"
                else "签到结果（${progress.outcomes.count { !it.result.isFailure }}/${progress.total} 成功）",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                progress.outcomes.forEach { outcome ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            outcome.username,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            statusLabel(outcome.result),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (outcome.result.isFailure) ErrorRed else GreenDeep,
                        )
                    }
                    // 认不出来或被拒绝时，把服务端原文放出来 —— 不然用户不知道该找谁
                    if (outcome.result is CasClient.CheckInResult.Unknown ||
                        outcome.result is CasClient.CheckInResult.Rejected
                    ) {
                        Text(
                            outcome.result.detail,
                            fontSize = 11.sp,
                            color = Muted,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!progress.running) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = GreenDeep, fontWeight = FontWeight.Medium)
                }
            }
        },
    )
}

private fun statusLabel(result: CasClient.CheckInResult): String = when (result) {
    is CasClient.CheckInResult.Success -> "成功"
    is CasClient.CheckInResult.AlreadyDone -> "已签到"
    is CasClient.CheckInResult.LoginExpired -> "登录已失效"
    is CasClient.CheckInResult.Rejected -> "被拒绝"
    is CasClient.CheckInResult.Unknown -> "未识别"
    is CasClient.CheckInResult.NoSession -> "无会话"
    is CasClient.CheckInResult.Network -> "网络错误"
}

@Composable
private fun SessionHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("← 返回", fontSize = 15.sp, color = GreenDeep, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun QrCode(state: CheckInUiState, density: Float, vm: CheckInViewModel) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp),
        contentAlignment = Alignment.Center,
    ) {
        val widthDp = maxWidth.value
        // 宽度或密度一变就得重画，保证位图分辨率始终匹配显示尺寸
        LaunchedEffect(widthDp, density) {
            vm.onQrSizeChanged(QrEncoder.pixelSize(widthDp, density))
        }
        val bmp = state.qr
        if (bmp != null) {
            val displayDp = (widthDp - 24f).coerceIn(180f, 320f)
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "签到二维码",
                modifier = Modifier
                    .size(displayDp.dp)
                    .background(Color.White)
                    .padding(8.dp),
                // 二维码绝不能插值——模糊的边缘会让扫码器读不出来
                filterQuality = FilterQuality.None,
            )
        } else {
            Text("—", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun UrlBox(url: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BoxBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            url.ifEmpty { "—" },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Muted,
        )
    }
}

@Composable
private fun SessionMeta(state: CheckInUiState) {
    val session = state.session ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BoxBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        MetaRow("ip", session.ip)
        MetaRow("ipt", session.ipt)
        MetaRow("t", state.t.toString())
        MetaRow("tt", state.tt)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Muted,
            modifier = Modifier.width(36.dp),
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink)
    }
}

@Composable
private fun Controls(
    state: CheckInUiState,
    vm: CheckInViewModel,
    context: Context,
    accountCount: Int,
    onCheckInAll: () -> Unit,
) {
    val enabled = state.hasSession
    Column {
        Button(
            onClick = { openUrl(context, state.url) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Color.White,
                disabledContainerColor = Green.copy(alpha = 0.4f),
            ),
        ) {
            Text("打开签到链接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("立即刷新", enabled, Modifier.weight(1f)) { vm.forceRefresh() }
            SecondaryButton("复制链接", enabled, Modifier.weight(1f)) {
                copyToClipboard(context, state.url)
                vm.showMessage("签到链接已复制")
            }
        }

        Spacer(Modifier.height(10.dp))

        SecondaryButton("分享链接", enabled, Modifier.fillMaxWidth()) {
            shareUrl(context, state.url)
        }

        if (accountCount > 0) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("全部签到（$accountCount 个账号）", enabled, Modifier.fillMaxWidth()) {
                onCheckInAll()
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.autoRefresh,
                onCheckedChange = { vm.setAutoRefresh(it) },
                enabled = enabled,
            )
            Spacer(Modifier.width(10.dp))
            Text("每 0.5 秒自动刷新", fontSize = 14.sp, color = Muted)
        }
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------ 平台动作

private fun copyToClipboard(context: Context, url: String) {
    if (url.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("签到链接", url))
}

private fun shareUrl(context: Context, url: String) {
    if (url.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, "分享签到链接"))
}

/**
 * 用系统浏览器打开签到链接。
 *
 * 注意这条**不会**带上你在 App 里登录的会话 —— 浏览器有自己的 Cookie。
 * 要真正完成签到请用「全部签到」，那个走的是 App 内已保存的账号凭证。
 */
private fun openUrl(context: Context, url: String) {
    if (url.isEmpty()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
