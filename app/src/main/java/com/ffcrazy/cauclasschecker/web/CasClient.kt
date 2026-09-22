package com.ffcrazy.cauclasschecker.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * 一次签到的完整交代：**结果**，加上**实际发出去的东西**。
 *
 * 后两项是诊断用的，起因是一个只看结果分辨不出来的情形：
 * 弹窗说「GPS：116.35…」，教师端后台那一列却是空的，而两次的「结果」
 * 一模一样 —— 都是「签到成功」。到底是坐标没进提交体、发到了不记坐标的
 * 端点、还是服务端不收，只有把发出去的原样摆出来才能分辨。
 */
data class CheckInReport(
    val result: CasClient.CheckInResult,
    /** 第二步实际 POST 到的端点文件名（`reg.php` / `casgeoreg.php`）；没走到第二步时为空。 */
    val endpoint: String = "",
    /** 提交体里实际带上的 position 值。 */
    val positionSent: String = "",
)

/**
 * 统一身份认证的无头登录 + 签到客户端。
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
                    .asBrowser("same-origin")
                    .header("Origin", originOf(entry))
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
                    .asBrowser("none")
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

    // ------------------------------------------------------------------ 签到

    /**
     * 一次签到的结果。
     *
     * 每个分支都带 [detail]：能判出来时是给用户看的中文说明，判不出来时是服务端原文。
     */
    sealed interface CheckInResult {
        val detail: String

        /** 服务端说「签到成功」。 */
        data class Success(override val detail: String) : CheckInResult

        /** 这个账号已经签过了。不算失败，但要说清楚。 */
        data class AlreadyDone(override val detail: String) : CheckInResult

        /** 登录态失效，被踢回了 CAS —— 需要重新登录这个账号。 */
        data class LoginExpired(override val detail: String) : CheckInResult

        /** 服务端明确拒绝（码过期、参数不对、签到会话超时等）。 */
        data class Rejected(override val detail: String) : CheckInResult

        /** 认不出来的响应。把原文给用户看。 */
        data class Unknown(override val detail: String) : CheckInResult

        /** 本地没存这个账号的会话，压根没发请求。 */
        data class NoSession(override val detail: String) : CheckInResult

        data class Network(override val detail: String) : CheckInResult

        /** 是否算「签到没成」，界面据此显示红/绿。 */
        val isFailure: Boolean
            get() = this is LoginExpired || this is Rejected || this is Network || this is NoSession
    }

    /**
     * 用某个账号**自己的** Cookie 完成一次签到。
     *
     * ## 签到是两步，不是一步
     * 实测（对着线上真实请求验证过）：
     *
     * ```
     * ① GET  casgeosig.php?ip&ipt&t&tt   带该账号的 Cookie
     *    ├─ 未登录       → 302 到 onecas.cau.edu.cn/tpass/login
     *    ├─ t 取值不合法 → 200 + "…请重新扫描签到的二维码…"
     *    └─ 通过         → 200 + 一张 <form>，同时服务端 $_SESSION['LastVisit'] 被种下
     *
     * ② POST 表单 action（reg.php 或 casgeoreg.php，从页面里解析，见 [RegForm]）
     *    ├─ 没有 LastVisit → "LastVisit timeout! Please Scan the QRcode again"
     *    └─ 有            → "签到结果 … 签到成功 …"
     * ```
     *
     * **只发第一步不会签上任何人** —— 它只取回一张表单。第二步必须在 120 秒内完成，
     * 那正是服务端给 LastVisit 的有效期。
     *
     * [url] 由调用方在**发请求前一刻**生成，因为 `t` 是按秒滚动的，
     * 提前批量生成会让靠后的账号拿到过期的码。
     *
     * [position] 是 `"经度,纬度"`，由调用方取真实定位后传入（见 `location/Position.kt`）。
     * 传空串表示拿不到定位 —— 签到照常进行，只是教师端后台那一列会是空的。
     */
    suspend fun checkIn(
        cookies: List<StoredCookie>,
        url: String,
        position: String = "",
    ): CheckInReport =
        withContext(Dispatchers.IO) {
            try {
                // 两步必须共用同一个罐子：LastVisit 是跟着会话走的
                val http = httpWith(AccountCookieJar(cookies))

                // ---- 第一步：取登记表单 ----
                val page = get(http, url)
                if (page.finalUrl.toHttpUrlOrNull()?.host.equals(CasLogin.CAS_HOST, true)) {
                    return@withContext CheckInReport(
                        CheckInResult.LoginExpired("被跳回统一身份认证，这个账号需要重新登录"),
                    )
                }

                val form = RegForm.parse(page.body, page.finalUrl)
                    ?: return@withContext CheckInReport(classifyCheckIn(plainText(page.body)))

                // 表单指向哪个端点本身就是线索：实测两种都出现过，
                // 而名字里带 geo 的那个（casgeoreg.php）才像是记坐标的
                val endpoint = form.action.substringBefore('?').substringAfterLast('/')

                // ---- 第二步：原样提交回 action ----
                val body = buildCheckInBody(form.fields, position)
                val request = Request.Builder()
                    .url(form.action.toHttpUrl())
                    .post(body)
                    .asBrowser("same-origin")
                    .header("Origin", originOf(form.action))
                    // 正常流程是表单页提交过来的，带上来源更像真实请求
                    .header("Referer", url)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (response.request.url.host.equals(CasLogin.CAS_HOST, ignoreCase = true)) {
                        return@use CheckInReport(
                            CheckInResult.LoginExpired("被跳回统一身份认证，这个账号需要重新登录"),
                            endpoint = endpoint,
                        )
                    }
                    CheckInReport(
                        classifyCheckIn(plainText(response.body?.string().orEmpty())),
                        endpoint = endpoint,
                        positionSent = body.valueOf("position"),
                    )
                }
            } catch (e: Exception) {
                CheckInReport(CheckInResult.Network(networkMessage(e)))
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

    /**
     * 给请求补上真实浏览器会带的那一组头。
     *
     * 起因是一条实测对比：**同一个 ipt、同一个签到窗口**，浏览器打开
     * `casgeosig.php` 拿到的是 `casgeoreg.php?…&pst=113&pict=…`，
     * 我们用 OkHttp 拿到的却是光秃秃的 `reg.php?ip=…&ipt=…`。
     * 时间相同、会话相同，差别只剩「请求长什么样」—— 而我们原来只发了
     * 一个简写 UA，`Accept` / `Accept-Language` / `Sec-Fetch-*` 一个都没有。
     *
     * 端点由服务端选，我们改不了它的逻辑，只能让它看起来是同一个浏览器。
     *
     * [fetchSite] 按浏览器的语义填：地址栏直接打开是 `none`，
     * 从页面里提交表单是 `same-origin`。
     *
     * **不设 Accept-Encoding**：OkHttp 会自己加 gzip 并自动解压，
     * 手工写 br / zstd 反而会让它拿到解不开的内容。
     */
    private fun Request.Builder.asBrowser(fetchSite: String): Request.Builder = apply {
        header("User-Agent", USER_AGENT)
        header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        header("Upgrade-Insecure-Requests", "1")
        header("Sec-Fetch-Dest", "document")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Site", fetchSite)
        header("Sec-Fetch-User", "?1")
    }

    /** `https://host` —— Origin 头只要这一段。 */
    private fun originOf(url: String): String =
        url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }.orEmpty()

    /** 响应体 + 最终 URL。最终 URL 用来判断有没有被 CAS 踢回去。 */
    private data class Page(val finalUrl: String, val body: String)

    private fun get(http: OkHttpClient, url: String): Page {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .asBrowser("none")
            .build()
        return http.newCall(request).execute().use {
            Page(it.request.url.toString(), it.body?.string().orEmpty())
        }
    }

    private companion object {
        /**
         * 真实的手机 Chrome UA。
         *
         * 之前那个是简写版，缺了设备型号、`(KHTML, like Gecko)` 和完整四段版本号。
         * 服务端要是拿 UA 判断「这是什么浏览器」，简写版很可能落到「不认识」那一支上 ——
         * 而实测同一场签到里，同样的会话换个客户端就能拿到不同的登记表单端点。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; 2211133C) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

// ---------------------------------------------------------------------- 提交体组装

/**
 * 取不到真实定位时的兜底值。
 *
 * 页面脚本要求 position 非空才允许提交，而服务端对内容照单全收 ——
 * 它只会把解析不出坐标的值存成空。所以这只是让请求形态合法，不是有效坐标。
 */
private const val NO_GEOLOCATION = "1,User denied Geolocation"

/** 同理。真实值是 FingerprintJS 的 visitorId，我们造不出来，也不需要。 */
private val NO_FINGERPRINT: String = "0".repeat(32)

/**
 * 组装签到提交体。
 *
 * 单独抽出来是为了能测「**每个参数名只出现一次**」。
 *
 * 页面上的 `position` / `browserfp` 两个 input 值是空的（真值由页面脚本在提交前填）。
 * 如果先把表单字段照抄一遍、再补一个自己的值，提交体里就会出现两个同名 `position` ——
 * 一个是空串、一个是坐标，服务端取哪个全凭它自己的解析习惯。
 * 代码上看不出问题（两个 `add` 隔了十几行），但线上就会表现成「明明拿到了坐标，
 * 后台那一列却是空的」。所以这里就地替换，绝不追加。
 */
/** 取提交体里某个参数的值（同名只会有一个，见 [buildCheckInBody]）。 */
private fun FormBody.valueOf(name: String): String =
    (0 until size).firstOrNull { name(it) == name }?.let { value(it) }.orEmpty()

internal fun buildCheckInBody(fields: Map<String, String>, position: String): FormBody {
    val builder = FormBody.Builder()

    fields.forEach { (name, value) ->
        when (name) {
            "position" -> builder.add("position", value.ifEmpty { position.ifEmpty { NO_GEOLOCATION } })
            "browserfp" -> builder.add("browserfp", value.ifEmpty { NO_FINGERPRINT })
            else -> builder.add(name, value)
        }
    }

    // 万一哪天页面上没有这两个 input，也得补上，别让请求少字段
    if (!fields.containsKey("position")) {
        builder.add("position", position.ifEmpty { NO_GEOLOCATION })
    }
    if (!fields.containsKey("browserfp")) builder.add("browserfp", NO_FINGERPRINT)

    return builder.build()
}

// ---------------------------------------------------------------------- 结果判定

/**
 * 按关键词判断签到结果。
 *
 * 下面这些字符串全部是**对着线上真实响应实测**得到的，不是猜的：
 *
 * ```
 * 成功   签到结果 学号:… 姓名:… 签到成功 课堂:…:… 时间: 2026-09-22 18:37:36
 * 拒绝   <服务端时间>, 如果您在教室，请重新扫描签到的二维码，…
 * 超时   LastVisit timeout! Please Scan the QRcode again
 * 缺参   GET[t] not set! Please Scan the QR code
 * ```
 *
 * 顺序要紧：**「已签到」必须排在「签到成功」前面**，因为重复签到时服务端很可能
 * 同时说出这两层意思，而那次其实是重复签到。
 * 认不出来的一律归为 [CasClient.CheckInResult.Unknown] 并附上原文，由人来看，不硬猜。
 */
internal fun classifyCheckIn(text: String): CasClient.CheckInResult = when {
    text.isBlank() -> CasClient.CheckInResult.Unknown("（服务端返回了空内容）")

    text.contains("已签到") || text.contains("重复签到") ->
        CasClient.CheckInResult.AlreadyDone(text)

    text.contains("签到成功") -> CasClient.CheckInResult.Success(text)

    // LastVisit 超时是**签到这一步**太慢了，跟账号登录态无关，
    // 所以归 Rejected 而不是 LoginExpired —— 不能因此把账号标成失效。
    text.contains("LastVisit timeout") ->
        CasClient.CheckInResult.Rejected("签到会话超时了（两步之间隔了太久），请重新扫码再试")

    text.contains("重新扫描") -> CasClient.CheckInResult.Rejected(text)

    text.contains("GET[t] not set") ->
        CasClient.CheckInResult.Rejected("链接里缺少 t 参数，不是有效的签到链接")

    else -> CasClient.CheckInResult.Unknown(text)
}

/** 先整块删掉 script / style —— 否则 CSS 正文会挤掉后面真正的内容。 */
private val BLOCK_REGEX = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
private val COMMENT_REGEX = Regex("(?s)<!--.*?-->")
private val TAG_REGEX = Regex("<[^>]+>")
private val WHITESPACE_REGEX = Regex("\\s+")

/** 去掉脚本样式、标签与多余空白，截断到可展示的长度。 */
internal fun plainText(html: String): String =
    html.replace(BLOCK_REGEX, " ")
        .replace(COMMENT_REGEX, " ")
        .replace(TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .take(200)
