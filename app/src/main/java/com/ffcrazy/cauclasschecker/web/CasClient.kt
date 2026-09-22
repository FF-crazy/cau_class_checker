package com.ffcrazy.cauclasschecker.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
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
    /**
     * 登录成功。[cookies] 是这个账号**自己的**会话 Cookie，由调用方持久化到该账号名下。
     * 每个账号一份，互不覆盖 —— 这是多账号同时有效的基础。
     */
    data class Success(val username: String, val cookies: List<StoredCookie>) : LoginResult

    data class Failure(val message: String) : LoginResult
}

/**
 * 统一身份认证的无头登录客户端。
 *
 * ```
 * 1. GET  /tpass/login?service=<站点根>   → 抓 lt 与 execution
 * 2. rsa = strEnc(用户名 + 密码 + lt, "1","2","3")
 * 3. POST /tpass/login?service=<站点根>   → 表单见 [CasLogin.buildForm]
 * 4. OkHttp 跟随 302，class.cau.edu.cn 为这个账号种下一份新的 PHPSESSID
 * ```
 *
 * ## 每个账号一个 Cookie 罐
 * 登录时用一个**全新的空罐子**，拿到什么就存什么。由此带来两个好处：
 *
 *  - 不会被别的账号残留的 `CASTGC` 干扰（那个票据会让 CAS 直接放行、
 *    不显示登录表单，从而解析不出 `lt` / `execution`）
 *  - 各账号的会话天然隔离 —— 服务端按 `PHPSESSID` 区分，可以同时有效
 */
class CasClient {

    private val base = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 给指定的罐子造一个客户端。
     * `newBuilder()` 复用连接池与线程池，所以代价很小，不必缓存。
     */
    private fun httpWith(jar: CookieJar): OkHttpClient = base.newBuilder().cookieJar(jar).build()

    // ------------------------------------------------------------------ 登录

    suspend fun login(username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                // 全新空罐子：与任何已有账号的会话完全无关
                val jar = AccountCookieJar()
                val http = httpWith(jar)
                val entry = CasLogin.loginUrl()

                // 1) 取登录页，抓一次性令牌
                val page = get(http, entry)
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

                http.newCall(request).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    val landedOnService = response.request.url.host
                        .equals(CasLogin.SERVICE_HOST, ignoreCase = true)

                    if (!landedOnService) {
                        return@withContext LoginResult.Failure(diagnoseFailure(response.code, html))
                    }

                    val cookies = jar.snapshot()
                    val serviceCookies = jar.loadForRequest(CasLogin.SERVICE_ROOT.toHttpUrl())
                    if (serviceCookies.isEmpty()) {
                        return@withContext LoginResult.Failure(
                            "服务器接受了登录，但没下发业务站点的会话 Cookie。请重试。",
                        )
                    }
                    LoginResult.Success(username, cookies)
                }
            } catch (e: Exception) {
                LoginResult.Failure(networkMessage(e))
            }
        }

    // ------------------------------------------------------------------ 验活

    /** 会话探测结果。 */
    sealed interface SessionProbe {
        data object Alive : SessionProbe
        data object Expired : SessionProbe
        data class Unknown(val reason: String) : SessionProbe
    }

    /**
     * 用某个账号**自己的** Cookie 探测会话是否还有效。
     *
     * 访问业务站点根路径：已登录返回 200，未登录会 302 回 CAS。
     * 根路径没有副作用 —— 不能用 `casgeosig.php` 当探针，那个一请求就是一次签到。
     */
    suspend fun probeSession(cookies: List<StoredCookie>): SessionProbe =
        withContext(Dispatchers.IO) {
            try {
                val http = httpWith(AccountCookieJar(cookies))
                val request = Request.Builder()
                    .url(CasLogin.SERVICE_ROOT.toHttpUrl())
                    .header("User-Agent", USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.request.url.host.equals(CasLogin.CAS_HOST, ignoreCase = true)) {
                        SessionProbe.Expired
                    } else {
                        SessionProbe.Alive
                    }
                }
            } catch (e: Exception) {
                SessionProbe.Unknown(networkMessage(e))
            }
        }

    // ------------------------------------------------------------------ 错误诊断

    /**
     * 认证失败的具体原因。优先用服务端写在 `#errormsghide` 里的原话 ——
     * 它能区分「密码错」「账号锁定」这些我们猜不出来的情况。
     */
    private fun diagnoseFailure(code: Int, html: String): String {
        CasLogin.parseErrorMessage(html)?.let { return it }

        return when {
            code == 401 || code == 403 -> "认证被拒绝（HTTP $code）。可能需要先在浏览器里完成一次验证。"
            code in 500..599 -> "认证服务器出错了（HTTP $code），这通常不是你的问题，稍后再试。"
            code == 200 -> "账号或密码不正确。"
            else -> "登录失败（HTTP $code），请稍后重试。"
        }
    }

    private fun describeUnexpectedPage(page: Page): String {
        if (page.finalUrl.contains(CasLogin.SERVICE_HOST, ignoreCase = true)) {
            return "当前已经是登录状态，CAS 没有要求重新登录。"
        }
        val size = page.body.length
        val looksLikeForm = page.body.contains("<input", ignoreCase = true)
        val detail = when {
            size == 0 -> "服务器返回了空响应（0 字节）"
            !looksLikeForm -> "返回的内容不是登录页（$size 字节，里面没有表单元素）"
            else -> "登录页里找不到 lt / execution（$size 字节）"
        }
        return "$detail。\n常见原因：手机上开着 VPN / 代理，或校园网需要先认证上网。"
    }

    private fun networkMessage(e: Exception): String = when (e) {
        is UnknownHostException -> "连不上认证服务器。请检查网络，校园网可能需要先连 VPN 或认证上网。"
        is SocketTimeoutException -> "认证服务器响应超时，可能是网络慢或服务器繁忙，请稍后重试。"
        is ConnectException -> "无法连接到认证服务器（连接被拒绝），请检查网络设置。"
        is SSLException -> "安全连接建立失败。如果有代理或 VPN，试着关掉再试。"
        is InterruptedIOException -> "网络中断，登录没有完成，请重试。"
        is IOException -> "网络异常：${e.message ?: "连接失败"}"
        else -> "登录出错：${e.message ?: e::class.java.simpleName}"
    }

    /** 响应体 + 最终 URL。最终 URL 用来判断有没有被 CAS 直接放行。 */
    private data class Page(val finalUrl: String, val body: String)

    private fun get(http: OkHttpClient, url: String): Page {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        return http.newCall(request).execute().use {
            Page(it.request.url.toString(), it.body?.string().orEmpty())
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
