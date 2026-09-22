package com.ffcrazy.cauclasschecker.web

/**
 * tpass 统一身份认证的登录表单解析与构造。
 *
 * 这一层**不碰网络**，纯字符串处理，所以能在单测里把各种边界钉死 ——
 * 尤其是隐藏域的抓取：登录页的属性顺序、引号风格、大小写都可能变，
 * 正则写窄一点（比如假设 `name` 一定在 `value` 前面）就会在真机上静默失败。
 */
object CasLogin {

    const val LOGIN_ENDPOINT = "https://onecas.cau.edu.cn/tpass/login"
    const val CAS_HOST = "onecas.cau.edu.cn"

    /**
     * 登录阶段用的 service 指向站点根。
     *
     * 不能指向 `casgeosig.php?…&t=…` —— 登录要花几十秒，而 `t` 是按秒滚动的，
     * 等 CAS 把你送回去时那个时间戳早过期了。服务器自己跳转时用的也是根地址。
     */
    const val SERVICE_ROOT = "https://class.cau.edu.cn/"
    const val SERVICE_HOST = "class.cau.edu.cn"

    /** 登录入口地址。 */
    fun loginUrl(): String = "$LOGIN_ENDPOINT?service=" + urlEncode(SERVICE_ROOT)

    /**
     * 抓登录页里的隐藏域。
     *
     * 不假设属性顺序，也不假设大小写：先扫出所有 `<input>` 标签，
     * 逐个解析属性，再按 `name`（缺失时退回 `id`）归类。
     */
    fun parseHiddenFields(html: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (tag in INPUT_TAG.findAll(html)) {
            val attrs = ATTR.findAll(tag.value)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (!attrs["type"].equals("hidden", ignoreCase = true)) continue
            val name = attrs["name"]?.takeIf { it.isNotEmpty() }
                ?: attrs["id"]?.takeIf { it.isNotEmpty() }
                ?: continue
            out[name] = attrs["value"] ?: ""
        }
        return out
    }

    /**
     * 构造 POST 表单。
     *
     * 对应 `login6.js` 里这几行：
     * ```
     * $("#ul").val(u.length);
     * $("#pl").val(p.length);
     * $("#rsa").val(strEnc(u + p + lt, '1', '2', '3'));
     * $("#sl").val(0);
     * ```
     *
     * 登录页上那两个可见输入框 `#un` / `#pd` **故意没有 name 属性**，
     * 所以它们不会被提交 —— 真正提交的是这里的六个字段。
     */
    fun buildForm(
        username: String,
        password: String,
        lt: String,
        execution: String,
    ): Map<String, String> = linkedMapOf(
        "ul" to username.length.toString(),
        "pl" to password.length.toString(),
        "rsa" to TpassCrypto.strEnc(username + password + lt, "1", "2", "3"),
        "sl" to "0", // 0 = 账号密码登录；1 = 手机验证码登录
        "lt" to lt,
        "execution" to execution,
        "_eventId" to "submit",
    )

    /**
     * 从失败页面里捞错误提示。
     *
     * 认证失败时 CAS 会重新渲染登录页并把原因写进 `#errormsg`。
     * 抓不到就返回 null，由调用方给一句兜底文案。
     */
    fun parseErrorMessage(html: String): String? {
        // ⚠️ 服务端的失败原因**不在** #errormsg 里 —— 那个元素始终为空，
        // 它只被页面自己的 JS 用来显示「账号不能为空」这类本地校验。
        //
        // 真正的原因写在 #errormsghide 里，例如：
        //   「您还剩20次机会！若密码连续输错60次，账号将被锁定19分钟。」
        // 而且这个元素**只在失败响应里出现**，首次 GET 时根本不存在。
        //
        // 所以先找 errormsghide，再退回 errormsg（本地校验文案）。
        return messageById(html, "errormsghide") ?: messageById(html, "errormsg")
    }

    /** 取某个 id 的元素内部文本；元素不存在或内容为空则返回 null。 */
    private fun messageById(html: String, id: String): String? {
        for (m in spanById(id).findAll(html)) {
            val text = m.groupValues[1]
                .replace(TAG, " ")
                .replace(WHITESPACE, " ")
                .trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun spanById(id: String) = Regex(
        """id\s*=\s*["']""" + Regex.escape(id) + """["'][^>]*>(.*?)</(?:span|div|p|label|td|li)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `application/x-www-form-urlencoded` 编码，空格用 `+`（与网页表单一致）。 */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private val INPUT_TAG = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_][\w:-]*)\s*=\s*["']([^"']*)["']""")
    private val TAG = Regex("""<[^>]+>""")
    private val WHITESPACE = Regex("""\s+""")
}
