package com.ffcrazy.cauclasschecker.domain

import java.net.URI
import java.security.MessageDigest

/**
 * 易签到 URL 的签名与解析。
 *
 * 移植自网页版 `index.html` 的 `ticket` / `buildUrl` / `validateSession` / `extractIpIpt`
 * （原文件 342-398 行）。这是整个 App 唯一的领域契约——任何一个字符对不上都会导致签到失败。
 *
 * 因此本文件**刻意不引用任何 Android API**，纯 JVM 逻辑，全部能在单元测试里验证。
 */
object Sign {

    const val SALT = "caulvchunli"
    const val BASE = "https://class.cau.edu.cn/casgeosig.php"

    private const val HEX = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"

    private val IP_REGEX = Regex("^[\\d.a-fA-F:]+$")
    private val IPT_REGEX = Regex("^[0-9A-Za-z_-]+$")

    private const val IP_MAX = 45
    private const val IPT_MAX = 40

    /** 当前 Unix 秒。对应原 JS 的 `Math.floor(Date.now() / 1000)`。 */
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * 签发票据：`md5(t + ip + SALT)` 的小写十六进制前 4 位。
     *
     * 拼接**没有分隔符**，顺序固定为 t、ip、SALT。对应 `index.html:342-344`。
     */
    fun ticket(t: Long, ip: String): String = md5Hex(t.toString() + ip + SALT).take(4)

    /**
     * 拼出签到 URL。参数顺序固定为 `ip, ipt, t, tt`（`index.html:346-354`）。
     *
     * 手写拼接而非用 URLBuilder，就是为了保证这个顺序——顺序变了眼睛看不出来，但服务端会拒绝。
     */
    fun buildUrl(ip: String, ipt: String, t: Long): String = buildString {
        append(BASE)
        append("?ip=").append(percentEncode(ip))
        append("&ipt=").append(percentEncode(ipt))
        append("&t=").append(t)
        append("&tt=").append(ticket(t, ip))
    }

    /**
     * 校验 ip / ipt。对应 `index.html:356-363`。
     *
     * 顺序很关键：**先判空，再 trim，再正则，最后长度**。
     * 所以纯空格的字符串会在正则那步失败（trim 后为空，不匹配 `+` 量词）。
     */
    fun validateSession(ip: String?, ipt: String?): Session? {
        if (ip.isNullOrEmpty() || ipt.isNullOrEmpty()) return null
        val vIp = ip.trim()
        val vIpt = ipt.trim()
        if (!IP_REGEX.matches(vIp) || vIp.length > IP_MAX) return null
        if (!IPT_REGEX.matches(vIpt) || vIpt.length > IPT_MAX) return null
        return Session(vIp, vIpt)
    }

    /**
     * 从任意文本里抠出 ip / ipt。对应 `index.html:365-398`，三级回退：
     *
     *  1. 以 http(s):// 开头 → 当完整 URL 解析
     *  2. 含 `=` → 当查询串解析（有 `?` 就取问号之后的部分）
     *  3. 正则兜底 `(?:^|[?&])ip=([^&]+)`
     *
     * 三级都取**第一个**匹配值（对齐 JS `URLSearchParams.get`）。
     *
     * 与网页版的一处**有意分歧**：第 2 级我们会在解析前剥掉 `#fragment`。
     * JS 不剥，所以 `?ip=1&ipt=2#frag` 在那边会得到 `ipt="2#frag"` 然后校验失败；
     * 我们剥掉后得到干净的 `ipt="2"`。这是严格更优的行为，且第 1 级本来就正确处理片段。
     */
    fun extractIpIpt(raw: String?): Pair<String?, String?> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null to null

        // 第 1~3 级：直接在文本里找 ip / ipt
        val direct = extractDirect(text)
        if (!direct.first.isNullOrEmpty() && !direct.second.isNullOrEmpty()) return direct

        // 第 4 级：CAS 统一身份认证登录链接。
        //   https://onecas.cau.edu.cn/tpass/login?service=<编码后的签到URL>
        // service 里嵌套着真正的签到地址，但它被整个百分号编码了
        // （`ip=` 变成 `ip%3D`），前三级都匹配不到，只能解开再找。
        return extractFromNestedUrl(text) ?: (null to null)
    }

    /** 前三级回退：完整 URL → 查询串 → 正则。 */
    private fun extractDirect(text: String): Pair<String?, String?> {
        // 第 1 级：完整 URL
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            val query = runCatching { URI(text).rawQuery }.getOrNull()
            if (query != null) {
                val params = parseQuery(query)
                val ip = params.firstOrNull { it.first == "ip" }?.second
                val ipt = params.firstOrNull { it.first == "ipt" }?.second
                if (!ip.isNullOrEmpty() && !ipt.isNullOrEmpty()) return ip to ipt
            }
        }

        // 第 2 级：查询串
        val afterQuestion = text.substringAfter('?', text)
        val withoutFragment = afterQuestion.substringBefore('#')
        if (withoutFragment.contains('=')) {
            val params = parseQuery(withoutFragment)
            val ip = params.firstOrNull { it.first == "ip" }?.second
            val ipt = params.firstOrNull { it.first == "ipt" }?.second
            if (!ip.isNullOrEmpty() && !ipt.isNullOrEmpty()) return ip to ipt
        }

        // 第 3 级：正则兜底
        val mIp = IP_IN_TEXT.find(text)
        val mIpt = IPT_IN_TEXT.find(text)
        if (mIp != null && mIpt != null) {
            return decodeComponent(mIp.groupValues[1]) to decodeComponent(mIpt.groupValues[1])
        }

        return null to null
    }

    /**
     * 在所有参数值里找「本身就是个 URL」的那个，进去再找一层。
     *
     * 只下钻**一层**，不递归调用 [extractIpIpt]——畸形或恶意的深层嵌套
     * 不该让解析器一直往里钻。
     */
    private fun extractFromNestedUrl(text: String): Pair<String?, String?>? {
        for ((_, value) in collectParams(text)) {
            if (value.length < 12) continue
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) continue
            val inner = extractDirect(value)
            if (!inner.first.isNullOrEmpty() && !inner.second.isNullOrEmpty()) return inner
        }
        return null
    }

    /** 把文本里能解析出的参数汇总起来（完整 URL 的查询串 + 裸查询串），保持顺序。 */
    private fun collectParams(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            runCatching { URI(text).rawQuery }.getOrNull()?.let { out += parseQuery(it) }
        }
        val afterQuestion = text.substringAfter('?', text).substringBefore('#')
        if (afterQuestion.contains('=')) out += parseQuery(afterQuestion)
        return out
    }

    /** 抠出并校验。任一环节失败返回 null，调用方据此走错误分支。 */
    fun parseSignUrl(raw: String?): Session? {
        val (ip, ipt) = extractIpIpt(raw)
        return validateSession(ip, ipt)
    }

    // ---------------------------------------------------------------- 内部实现

    private val IP_IN_TEXT = Regex("(?:^|[?&])ip=([^&]+)", RegexOption.IGNORE_CASE)
    private val IPT_IN_TEXT = Regex("(?:^|[?&])ipt=([^&]+)", RegexOption.IGNORE_CASE)

    /**
     * 按 `application/x-www-form-urlencoded` 规则解析查询串，保持出现顺序。
     *
     * 顺序有意义：重复键要取**第一个**，所以返回 List 而不是 Map。
     */
    private fun parseQuery(query: String): List<Pair<String, String>> =
        query.split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) {
                    decodeComponent(pair) to ""
                } else {
                    decodeComponent(pair.substring(0, idx)) to decodeComponent(pair.substring(idx + 1))
                }
            }

    /**
     * 百分号解码，`+` 视为空格。
     *
     * JS 的 `decodeURIComponent` 遇到畸形 `%` 会抛异常，网页版靠外层 catch 兜住；
     * 这里直接吞掉异常返回原文，效果等价且不会把异常抛到调用方。
     */
    private fun decodeComponent(s: String): String {
        if (!s.contains('%') && !s.contains('+')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '+' -> { out.append(' '); i++ }
                '%' -> {
                    if (i + 2 < s.length) {
                        val hex = s.substring(i + 1, i + 3)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 3
                        } else {
                            out.append(c); i++
                        }
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * 复刻 JS `URLSearchParams` 的编码规则（application/x-www-form-urlencoded）：
     * 字母数字和 `* - . _` 原样输出，空格转 `+`，其余转 **大写** 十六进制的 `%XX`。
     *
     * 对校验过的输入来说只有 `:` 会被编码（IPv6 地址），即 `%3A`。
     */
    private fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> append(c)
                c == '*' || c == '-' || c == '.' || c == '_' -> append(c)
                c == ' ' -> append('+')
                else -> {
                    append('%')
                    append(HEX_UPPER[v ushr 4])
                    append(HEX_UPPER[v and 0x0F])
                }
            }
        }
    }

    /** MD5 小写十六进制。逐字节手工格式化，避免 `%02x` 或 `Integer.toHexString` 的前导零/大小写陷阱。 */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(32) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }
}
