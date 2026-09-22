package com.ffcrazy.cauclasschecker.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 登录结果。 */
sealed interface LoginResult {
    data class Success(val username: String) : LoginResult

    /** [message] 是可以直接给用户看的中文说明。 */
    data class Failure(val message: String) : LoginResult
}

/**
 * 统一身份认证的无头登录客户端。
 *
 * 全流程不用 WebView：
 *
 * ```
 * 1. GET  /tpass/login?service=<站点根>        → 抓 lt 与 execution
 * 2. rsa = strEnc(用户名 + 密码 + lt, "1","2","3")
 * 3. POST /tpass/login?service=<站点根>        → 表单见 [CasLogin.buildForm]
 * 4. OkHttp 自动跟随 302，class.cau.edu.cn 种下已登录的 PHPSESSID
 * ```
 *
 * 登录成功后 Cookie 由 [PersistentCookieJar] 落盘，后续签到请求复用，
 * **密码本身不写入任何存储**。
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

                // 1) 取登录页，抓隐藏域
                val loginPage = get(entry)
                val hidden = CasLogin.parseHiddenFields(loginPage)
                val lt = hidden["lt"]
                val execution = hidden["execution"]

                if (lt.isNullOrEmpty() || execution.isNullOrEmpty()) {
                    return@withContext LoginResult.Failure(
                        "没能从登录页取到 lt / execution，可能是认证服务改版了。" +
                            "请把这条信息反馈给开发者。",
                    )
                }

                // 2)+3) 构造并提交表单
                val form = CasLogin.buildForm(username, password, lt, execution)
                val body = FormBody.Builder().apply {
                    form.forEach { (k, v) -> add(k, v) }
                }.build()

                val request = Request.Builder()
                    .url(entry.toHttpUrl())
                    .post(body)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "Chrome/120 Mobile Safari/537.36",
                    )
                    .header("Referer", entry)
                    .build()

                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url
                    val html = response.body?.string().orEmpty()

                    // 4) 判断成败：被送回业务站点就是成功，还停在 CAS 就是失败
                    val landedOnService = finalUrl.host.equals(
                        CasLogin.SERVICE_HOST,
                        ignoreCase = true,
                    )

                    when {
                        landedOnService -> {
                            if (!cookieJar.hasCookiesFor(CasLogin.SERVICE_HOST)) {
                                LoginResult.Failure("登录似乎通过了，但没拿到会话 Cookie，请重试。")
                            } else {
                                LoginResult.Success(username)
                            }
                        }

                        else -> {
                            val reason = CasLogin.parseErrorMessage(html)
                            LoginResult.Failure(reason ?: defaultFailureMessage(response.code))
                        }
                    }
                }
            } catch (e: Exception) {
                LoginResult.Failure(networkMessage(e))
            }
        }

    /** 退出登录：清掉本地 Cookie。服务端那条 session 不去主动注销。 */
    fun logout() = cookieJar.clear()

    /** 看起来是否已登录（仅依据本地是否还有该域的 Cookie，不代表服务端一定认）。 */
    fun looksLoggedIn(): Boolean = cookieJar.hasCookiesFor(CasLogin.SERVICE_HOST)

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "Chrome/120 Mobile Safari/537.36",
            )
            .build()
        return client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    private fun defaultFailureMessage(code: Int): String = when (code) {
        200 -> "账号或密码不正确。"
        in 500..599 -> "认证服务暂时不可用（HTTP $code），请稍后再试。"
        else -> "登录失败（HTTP $code）。"
    }

    private fun networkMessage(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "连不上认证服务器，请检查网络。"
        is java.net.SocketTimeoutException -> "认证服务器响应超时，请稍后再试。"
        else -> "登录出错：${e.message ?: e::class.java.simpleName}"
    }
}
