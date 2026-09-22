package com.ffcrazy.cauclasschecker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.qr.QrEncoder
import com.ffcrazy.cauclasschecker.scan.QrImageDecoder
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.ErrorRed
import com.ffcrazy.cauclasschecker.ui.theme.Green
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted
import com.ffcrazy.cauclasschecker.ui.theme.StatusGreen
import com.ffcrazy.cauclasschecker.ui.theme.UrlBoxBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CheckInScreen(
    vm: CheckInViewModel,
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    // 教室扫码时屏幕不能熄——网页版靠手机系统设置，这里直接锁住
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var linkText by rememberSaveable { mutableStateOf("") }

    // 相册路径。PickVisualMedia 不需要任何存储权限——系统相册自己负责授权。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 解码大图可能要几百毫秒，别卡住主线程
            val text = withContext(Dispatchers.IO) { QrImageDecoder.decode(context, uri) }
            when {
                text == null -> vm.showError(CheckInViewModel.MSG_NO_QR)
                else -> {
                    val session = Sign.parseSignUrl(text)
                    if (session == null) {
                        vm.showError(CheckInViewModel.MSG_BAD_QR + text)
                    } else {
                        vm.startSession(session, "识别成功：相册图片")
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { HeaderBar() },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border, RoundedCornerShape(12.dp)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "扫大屏二维码，或粘贴扫到的签到链接，无论有没有过期，都可以生成新鲜的二维码和签到链接",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenScanner,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Green,
                                contentColor = Ink,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("扫码", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = Ink,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("相册", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    LinkField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        onSubmit = { vm.applyLink(linkText) },
                    )

                    Spacer(Modifier.height(18.dp))

                    QrOutput(state = state, density = density, vm = vm)

                    Spacer(Modifier.height(12.dp))

                    UrlBox(state.url)

                    if (state.hasSession) {
                        Spacer(Modifier.height(10.dp))
                        SessionMeta(state)
                    }

                    Spacer(Modifier.height(14.dp))

                    Controls(state = state, vm = vm, context = context)

                    state.status?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = StatusGreen, fontSize = 14.sp)
                    }
                    state.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = ErrorRed, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "无论来自图片还是粘贴的链接，只会保留 ip、ipt，按当前时间重算 t、tt 并生成新二维码（旧链接里的时间戳不会沿用）。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun HeaderBar() {
    Surface(color = Green, shadowElevation = 0.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("我爱易签到", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
private fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        Text("签到链接（微信/相机扫码后可粘贴）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp),
            placeholder = {
                Text(
                    "https://class.cau.edu.cn/casgeosig.php?ip=…&ipt=…",
                    fontSize = 12.sp,
                    color = Muted,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Ink,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("根据链接生成当前二维码", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QrOutput(state: CheckInUiState, density: Float, vm: CheckInViewModel) {
    Column {
        Text("当前时间二维码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val widthDp = maxWidth.value
            // 宽度或密度一变就得重画，保证位图分辨率始终匹配显示尺寸
            LaunchedEffect(widthDp, density) {
                vm.onQrSizeChanged(QrEncoder.pixelSize(widthDp, density))
            }
            val bmp = state.qr
            if (bmp != null) {
                val displayDp = (widthDp - 24f).coerceIn(180f, 280f)
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "当前时间签到二维码",
                    modifier = Modifier
                        .padding(10.dp)
                        .size(displayDp.dp)
                        .background(Color.White),
                    // 二维码绝不能插值——模糊的边缘会让扫码器读不出来
                    filterQuality = FilterQuality.None,
                )
            } else {
                Text("—", color = Muted, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun UrlBox(url: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(UrlBoxBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            url.ifEmpty { "—" },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Ink,
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
            .background(UrlBoxBg)
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
            fontWeight = FontWeight.Bold,
            color = Ink,
            modifier = Modifier.width(40.dp),
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun Controls(state: CheckInUiState, vm: CheckInViewModel, context: Context) {
    val enabled = state.hasSession
    Column {
        Button(
            onClick = { openUrl(context, state.url) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Ink,
                disabledContainerColor = Green.copy(alpha = 0.4f),
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("打开签到链接", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedAction("立即刷新", enabled, Modifier.weight(1f)) { vm.forceRefresh() }
            OutlinedAction("复制签到链接", enabled, Modifier.weight(1f)) {
                copyToClipboard(context, state.url)
                vm.dismissStatus()
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedAction("分享签到链接", enabled, Modifier.fillMaxWidth()) {
            shareUrl(context, state.url)
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.autoRefresh,
                onCheckedChange = { vm.setAutoRefresh(it) },
                enabled = enabled,
            )
            Spacer(Modifier.width(8.dp))
            Text("每 0.5 秒自动刷新（与大屏一致）", fontSize = 14.sp, color = Muted)
        }
    }
}

@Composable
private fun OutlinedAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Ink,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
