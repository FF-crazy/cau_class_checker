package com.ffcrazy.cauclasschecker.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 登录结果。[Failure.message] 是可直接展示给用户的中文说明。 */
sealed interface LoginResult {
    data class Success(val username: String) : LoginResult
    data class Failure(val message: String) : LoginResult
}

/**
 * 统一身份认证的无头登录客户端。
 *
 * ```
 * 1. GET  /tpass/login?service=<站点根>   → 抓 lt 与 execution
 * 2. rsa = strEnc(用户名 + 密码 + lt, "1","2","3")
 * 3. POST /tpass/login?service=<站点根>   → 表单见 [CasLogin.buildForm]
 * 4. OkHttp 跟随 302，class.cau.edu.cn 种下已登录的 PHPSESSID
 * ```
 *
 * service 指向**站点根**而不是签到页：登录要几十秒，而 `t` 是按秒滚动的，
 * 带上时间戳的话等 CAS 送回来时早过期了。服务器自己跳转时用的也是根地址。
 *
 * 登录成功后 Cookie 由 [PersistentCookieJar] 落盘，**密码不写入任何存储**。
 */
class CasClient(private val cookieJar: PersistentCookieJar) {

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun login(username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val entry = CasLogin.loginUrl()

                // 0) 先清掉 CAS 域的会话凭据 —— 这一步是必须的。
                //
                //    登录成功后 CAS 会种下 CASTGC（已认证票据）。带着它再去请求
                //    登录页时，CAS 会**直接把你送回业务站点**（单点登录的正常行为），
                //    于是拿到的不是登录表单，而是业务页面，自然解析不出 lt / execution。
                //
                //    只清 CAS 域、保留业务站点域，这样「换账号登录失败」不会顺手
                //    把当前已登录的账号也踢掉。
                cookieJar.clearHost(CasLogin.CAS_HOST)

                // 1) 取登录页，抓一次性令牌
                val page = get(entry)
                val hidden = CasLogin.parseHiddenFields(page.body)
                val lt = hidden["lt"]
                val execution = hidden["execution"]

                if (lt.isNullOrEmpty() || execution.isNullOrEmpty()) {
                    return@withContext LoginResult.Failure(describeUnexpectedPage(page))
                }

                // 2)+3) 构造并提交
                val form = CasLogin.buildForm(username, password, lt, execution)
                val body = FormBody.Builder().apply {
                    form.forEach { (k, v) -> add(k, v) }
                }.build()

                val request = Request.Builder()
                    .url(entry.toHttpUrl())
                    .post(body)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", entry)
                    .build()

                client.newCall(request).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    val landedOnService = response.request.url.host
                        .equals(CasLogin.SERVICE_HOST, ignoreCase = true)

                    if (landedOnService) {
                        if (cookieJar.hasCookiesFor(CasLogin.SERVICE_HOST)) {
                            LoginResult.Success(username)
                        } else {
                            LoginResult.Failure(
                                "服务器接受了登录，但没下发会话 Cookie。" +
                                    "请重试；若反复出现，可能是学校侧策略调整。",
                            )
                        }
                    } else {
                        // 还停在 CAS：认证没通过
                        LoginResult.Failure(diagnoseFailure(response.code, html))
                    }
                }
            } catch (e: Exception) {
                LoginResult.Failure(networkMessage(e))
            }
        }

    /** 会话探测结果。 */
    sealed interface SessionProbe {
        data object Alive : SessionProbe
        data object Expired : SessionProbe
        data class Unknown(val reason: String) : SessionProbe
    }

    /**
     * 探测当前会话是否还有效。
     *
     * 访问业务站点的**根路径**：已登录返回 200，未登录会 302 回 CAS。
     * 根路径没有副作用 —— 不能用 `casgeosig.php` 当探针，那个一请求就是一次签到。
     */
    suspend fun probeSession(): SessionProbe = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CasLogin.SERVICE_ROOT.toHttpUrl())
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                val finalHost = response.request.url.host
                if (finalHost.equals(CasLogin.CAS_HOST, ignoreCase = true)) {
                    SessionProbe.Expired
                } else {
                    SessionProbe.Alive
                }
            }
        } catch (e: Exception) {
            SessionProbe.Unknown(networkMessage(e))
        }
    }

    /** 退出登录：只清本地 Cookie，不去服务端注销那条 session。 */
    fun logout() = cookieJar.clear()

    /** 本地是否还留着会话 Cookie（不代表服务端一定认）。 */
    fun looksLoggedIn(): Boolean = cookieJar.hasCookiesFor(CasLogin.SERVICE_HOST)

    // ------------------------------------------------------------------ 错误诊断

    /**
     * 认证失败的具体原因。
     *
     * 优先用服务端自己写在 `#errormsg` 里的原话 —— 它能区分「密码错」「账号锁定」
     * 「需要验证码」这些我们猜不出来的情况。抓不到才退回到推断。
     */
    private fun diagnoseFailure(code: Int, html: String): String {
        CasLogin.parseErrorMessage(html)?.let { return it }

        return when {
            code == 401 || code == 403 ->
                "认证被拒绝（HTTP $code）。可能需要先在浏览器里完成一次验证。"

            code in 500..599 ->
                "认证服务器出错了（HTTP $code），这通常不是你的问题，稍后再试。"

            code == 200 ->
                // 回到登录页但没有错误文案，最常见的就是账号密码不对
                "账号或密码不正确。"

            else -> "登录失败（HTTP $code），请稍后重试。"
        }
    }

    private fun networkMessage(e: Exception): String = when (e) {
        is UnknownHostException ->
            "连不上认证服务器。请检查网络，校园网可能需要先连 VPN 或认证上网。"

        is SocketTimeoutException ->
            "认证服务器响应超时，可能是网络慢或服务器繁忙，请稍后重试。"

        is ConnectException ->
            "无法连接到认证服务器（连接被拒绝），请检查网络设置。"

        is SSLException ->
            "安全连接建立失败。如果有代理或 VPN，试着关掉再试。"

        is InterruptedIOException ->
            "网络中断，登录没有完成，请重试。"

        is IOException ->
            "网络异常：${e.message ?: "连接失败"}"

        else ->
            "登录出错：${e.message ?: e::class.java.simpleName}"
    }

    /**
     * GET 拿到了响应，但它不是我们认识的登录页。
     *
     * 带上**实际字节数** —— 这是最有诊断价值的一个数字：
     * 0 字节说明请求根本没到服务器；几千字节但没表单，多半是被代理或
     * 校园网认证页拦截了；接近 15KB 才是正常的登录页大小。
     */
    private fun describeUnexpectedPage(page: Page): String {
        val size = page.body.length

        // GET 直接落到了业务站点 —— 说明 CAS 认为我们已认证，没让登录就放行了
        if (page.finalUrl.contains(CasLogin.SERVICE_HOST, ignoreCase = true)) {
            return "当前已经是登录状态，CAS 没有要求重新登录。\n" +
                "如果要换账号，请先「退出登录」再添加。"
        }

        val looksLikeForm = page.body.contains("<input", ignoreCase = true)
        val detail = when {
            size == 0 -> "服务器返回了空响应（0 字节）"
            !looksLikeForm -> "返回的内容不是登录页（$size 字节，里面没有表单元素）"
            else -> "登录页里找不到 lt / execution（$size 字节）"
        }
        return "$detail。\n常见原因：手机上开着 VPN / 代理，或校园网需要先认证上网。"
    }

    /** 响应体 + 最终 URL。最终 URL 用来判断有没有被 CAS 直接放行。 */
    private data class Page(val finalUrl: String, val body: String)

    private fun get(url: String): Page {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use {
            Page(it.request.url.toString(), it.body?.string().orEmpty())
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
