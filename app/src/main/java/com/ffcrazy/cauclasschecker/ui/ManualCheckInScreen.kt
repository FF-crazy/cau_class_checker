package com.ffcrazy.cauclasschecker.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ffcrazy.cauclasschecker.AccountViewModel
import com.ffcrazy.cauclasschecker.CheckInViewModel
import com.ffcrazy.cauclasschecker.domain.Session
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.web.CasClient
import com.ffcrazy.cauclasschecker.web.CasLogin
import com.ffcrazy.cauclasschecker.web.classifyCheckIn
import com.ffcrazy.cauclasschecker.web.decodeJsResult
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

/** 注入 Cookie 时用的地址，只要站点根 —— CookieManager 会按 domain / path 自己匹配。 */
private const val SITE_ROOT = "https://class.cau.edu.cn"

/**
 * 问页面上有没有出现已知的结果文案。
 *
 * 返回的文本**先 `encodeURIComponent` 过一道**：`evaluateJavascript` 的返回值是
 * JSON 字符串字面量，里头的中文、换行、引号都带着转义。编码成 ASCII 之后，
 * Kotlin 那边只要去引号 + URL 解码就行，没有转义规则要猜 —— 见 `decodeJsResult`。
 */
private const val PROBE_JS = """
(function() {
  try {
    var t = document.body ? (document.body.innerText || '') : '';
    return encodeURIComponent(t.slice(0, 4000));
  } catch (e) { return ''; }
})()
"""

/**
 * 手动签到（逐个点）—— **托底方案**。
 *
 * 无头 HTTP 那条路万一走不通（服务端改了、要人机交互、要图形验证码），
 * 还能退回「拿这个账号的 Cookie 开一个 WebView，自己点一下」。
 * 慢，但只要能上网就能用。
 *
 * ## 每个账号一幅全新的 WebView
 * `android.webkit.CookieManager` 是**全应用共享的单例**，没有「每个账号一个罐子」这回事。
 * 所以每换一个账号就整幅重建，重建前先 `removeAllCookies` 再注入这一个账号的 Cookie。
 * 复用同一幅 WebView 会让上一个账号的会话残留下来 —— 那正好毁掉多账号的前提。
 *
 * ## 什么时候自动前进
 * 只在**这条路确实走完了**的时候：签到成功、已签到、会话失效。
 * 「码不对」「LastVisit 超时」这类是**可以重来的**（页面上的「重新加载」就是干这个的），
 * 自动跳过等于替用户放弃一次可能成功的签到。
 */
@Composable
fun ManualCheckInScreen(
    vm: CheckInViewModel,
    accountVm: AccountViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val accountState by accountVm.state.collectAsStateWithLifecycle()

    val manual = accountState.manual
    val session = state.session
    val account = manual?.current

    // 队列走完（或会话没了）时这个页面就该消失，由上层切回二维码页
    LaunchedEffect(manual, session, account) {
        if (manual == null || session == null || account == null) onExit()
    }
    if (manual == null || session == null || account == null) return

    // 换账号 = 重建 WebView；点「重新加载」也一样（顺便重算一个新鲜的 t）
    var reloads by remember(account.username) { mutableIntStateOf(0) }

    // 离开时把 WebView 的 Cookie 也清掉 —— 那是全局存储，
    // 不该把最后一个账号的会话留在里面
    DisposableEffect(Unit) {
        onDispose { clearWebCookies() }
    }

    Column(modifier.fillMaxSize()) {
        ManualHeader(
            position = manual.position,
            total = manual.total,
            onExit = onExit,
        )
        HorizontalDivider(color = Border, thickness = 1.dp)

        key(account.username, reloads) {
            SessionWebView(
                username = account.username,
                session = session,
                accountVm = accountVm,
                onResult = accountVm::reportManual,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = Border, thickness = 1.dp)
        ManualFooter(
            username = account.username,
            onReload = { reloads++ },
            onSkip = accountVm::skipManual,
        )
    }
}

@Composable
private fun ManualHeader(position: Int, total: Int, onExit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onExit) {
            Text("← 退出", fontSize = 15.sp, color = GreenDeep, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        Text("手动签到 $position/$total", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        // 右侧占位，让标题真正居中
        Spacer(Modifier.width(56.dp))
    }
}

@Composable
private fun ManualFooter(username: String, onReload: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("当前账号", fontSize = 12.sp, color = Muted)
        Text(username, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Ink)

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ManualButton("重新加载", Modifier.weight(1f), onReload)
            ManualButton("跳过这个", Modifier.weight(1f), onSkip)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "在下面的页面里完成签到，成功后会自动跳到下一个账号",
            fontSize = 11.sp,
            color = Muted.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ManualButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Ink,
        ),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SessionWebView(
    username: String,
    session: Session,
    accountVm: AccountViewModel,
    onResult: (CasClient.CheckInResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 每个账号一份新鲜的时间戳。页面开着太久会撞上 LastVisit 的 120 秒窗口，
    // 那时点「重新加载」重建一幅即可。
    val url = remember(username) {
        Sign.buildUrl(session.ip, session.ipt, Sign.nowSeconds(), session.mode)
    }

    // 页面里可能有 <input type="file">（严格模式要拍照）。不接管的话点上去毫无反应，
    // 用户会以为卡死了 —— 所以这个回调必须接。
    val fileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileCallback.value?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        fileCallback.value = null
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            // 一次签到只上报一次：onPageFinished 会随每次导航反复触发，
            // 不挡一下会把后面的账号整个跳过去
            var reported = false

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loaded: String) {
                        if (reported) return

                        // 落在统一身份认证上 = 这个账号的会话没了，不必让用户白等
                        if (loaded.contains(CasLogin.CAS_HOST, ignoreCase = true)) {
                            reported = true
                            onResult(CasClient.CheckInResult.LoginExpired("会话已失效，跳过"))
                            return
                        }

                        view.evaluateJavascript(PROBE_JS) { raw ->
                            if (!reported) {
                                val result = classifyCheckIn(decodeJsResult(raw))
                                if (shouldAdvance(result)) {
                                    reported = true
                                    onResult(result)
                                }
                            }
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        view: WebView,
                        callback: ValueCallback<Array<Uri>>,
                        params: FileChooserParams,
                    ): Boolean {
                        fileCallback.value?.onReceiveValue(null)
                        fileCallback.value = callback
                        return runCatching { fileLauncher.launch(params.createIntent()) }
                            .fold(
                                onSuccess = { true },
                                onFailure = {
                                    fileCallback.value = null
                                    false
                                },
                            )
                    }
                }

                // CookieManager 是全应用共享的：先清干净，再注入这一个账号自己的
                clearWebCookies()
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    accountVm.webCookies(username).forEach { setCookie(SITE_ROOT, it) }
                    flush()
                }

                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

/**
 * 只有「这条路确实走完了」才自动前进。
 *
 * 「码不对」「LastVisit 超时」是**可以重来的** —— 自动跳过等于替用户
 * 放弃一次可能成功的签到，而用户未必注意到发生过什么。
 */
private fun shouldAdvance(result: CasClient.CheckInResult): Boolean = when (result) {
    is CasClient.CheckInResult.Success,
    is CasClient.CheckInResult.AlreadyDone,
    is CasClient.CheckInResult.LoginExpired,
    -> true

    else -> false
}

private fun clearWebCookies() {
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
}
