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
import com.ffcrazy.cauclasschecker.CheckInViewModel
import com.ffcrazy.cauclasschecker.qr.QrEncoder
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
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

            Controls(state = state, vm = vm, context = context)

            state.status?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = GreenDeep, fontSize = 13.sp)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = ErrorRed, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
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
private fun Controls(state: CheckInUiState, vm: CheckInViewModel, context: Context) {
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
                vm.dismissStatus()
            }
        }

        Spacer(Modifier.height(10.dp))

        SecondaryButton("分享链接", enabled, Modifier.fillMaxWidth()) {
            shareUrl(context, state.url)
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
 * 暂时用系统浏览器打开。
 *
 * 下一步换成 App 内 WebView —— 那样才能和统一身份认证共享 Cookie，
 * 现在这样跳出去是拿不到登录态的。
 */
private fun openUrl(context: Context, url: String) {
    if (url.isEmpty()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
