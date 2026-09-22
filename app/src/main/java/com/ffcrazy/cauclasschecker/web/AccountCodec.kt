package com.ffcrazy.cauclasschecker.web

/**
 * 一条 Cookie 及其来源 URL。
 *
 * 存来源 URL 是为了还原时能交给 `Cookie.parse(url, string)`；
 * OkHttp 的 `Cookie.toString()` 本身带 domain / path，URL 只作兜底。
 */
data class StoredCookie(
    val origin: String,
    /** `Cookie.toString()` 的结果，形如 `CASTGC=TGT-xxx; path=/tpass; domain=…`。 */
    val value: String,
)

/**
 * 一个账号的完整记录 —— **含它自己的会话 Cookie**。
 *
 * 每个账号一份独立的 Cookie，这是「多账号同时签到」的基础：
 * 服务端按 PHPSESSID 区分会话，各账号互不影响。
 */
data class AccountRecord(
    val username: String,
    val loginAt: Long,
    val valid: Boolean,
    val cookies: List<StoredCookie> = emptyList(),
)

/**
 * 账号清单的文本编解码。
 *
 * 纯字符串处理，不碰加密也不碰文件，所以能在单测里把边界钉死。
 * 格式是**逐行**的，一行一条记录：
 *
 * ```
 * V1
 * A 2023000000000 1758523200000 1
 * C 2023000000000 https://onecas.cau.edu.cn/tpass CASTGC=TGT-xxx; path=/tpass; domain=…
 * C 2023000000000 https://class.cau.edu.cn/     PHPSESSID=abc; path=/; domain=…
 * A 2023999999 1758520000000 0
 * ```
 *
 * 选逐行文本而不是二进制，是因为出问题时可以直接把解密后的内容打出来看。
 * 学号不含空格、URL 不含空格，Cookie 串含空格但永远是**最后一个字段**，
 * 所以按空格切分并限制段数是安全的。
 */
object AccountCodec {

    const val VERSION = "V1"

    private const val TAG_ACCOUNT = "A"
    private const val TAG_COOKIE = "C"

    fun encode(accounts: List<AccountRecord>): String = buildString {
        append(VERSION).append('\n')
        for (a in accounts) {
            append(TAG_ACCOUNT).append(' ')
                .append(a.username).append(' ')
                .append(a.loginAt).append(' ')
                .append(if (a.valid) "1" else "0")
                .append('\n')
            for (c in a.cookies) {
                // limit=4：Cookie 串里的空格不能被切掉
                append(TAG_COOKIE).append(' ')
                    .append(a.username).append(' ')
                    .append(c.origin).append(' ')
                    .append(c.value)
                    .append('\n')
            }
        }
    }

    /**
     * 解析。格式不对的行直接跳过而不是抛异常 —— 半损坏的文件也应该
     * 尽量救回能用的账号，而不是整个清单作废。
     */
    fun decode(text: String): List<AccountRecord> {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()

        val accounts = LinkedHashMap<String, AccountRecord>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            when (line.substringBefore(' ')) {
                TAG_ACCOUNT -> parseAccount(line)?.let { accounts[it.username] = it }
                TAG_COOKIE -> parseCookie(line)?.let { (user, cookie) ->
                    val existing = accounts[user] ?: return@let
                    accounts[user] = existing.copy(cookies = existing.cookies + cookie)
                }
            }
        }
        return accounts.values.sortedByDescending { it.loginAt }
    }

    private fun parseAccount(line: String): AccountRecord? {
        val parts = line.split(' ', limit = 4)
        if (parts.size < 4) return null
        val username = parts[1]
        if (username.isEmpty()) return null
        val at = parts[2].toLongOrNull() ?: return null
        return AccountRecord(username, at, valid = parts[3] == "1")
    }

    /** @return (用户名, Cookie)；用户名对应的账号还不存在时返回 null。 */
    private fun parseCookie(line: String): Pair<String, StoredCookie>? {
        val parts = line.split(' ', limit = 4)
        if (parts.size < 4) return null
        val username = parts[1]
        val origin = parts[2]
        val value = parts[3]
        if (username.isEmpty() || origin.isEmpty() || value.isEmpty()) return null
        return username to StoredCookie(origin, value)
    }
}
