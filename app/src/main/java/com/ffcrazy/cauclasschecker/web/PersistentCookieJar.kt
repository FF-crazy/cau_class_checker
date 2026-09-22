package com.ffcrazy.cauclasschecker.web

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * 落盘的 Cookie 罐。
 *
 * 登录态（`class.cau.edu.cn` 的 `PHPSESSID`）就是靠它跨进程存活的 ——
 * App 被杀掉重开还在。服务端不认了再说，客户端这侧没必要主动丢。
 *
 * 两个域各存各的：CAS 的 `JSESSIONID` 只在 `/tpass` 路径下有效，
 * 靠 [Cookie.matches] 自动按域和路径过滤。
 *
 * 存储格式是每行一条 `来源URL \t Cookie.toString()`。选这个格式是因为
 * OkHttp 的 `Cookie.toString()` 与 `Cookie.parse()` 正好能往返，
 * 不必自己定义序列化格式（也就不会漏掉 secure / httponly / 过期时间这些属性）。
 */
class PersistentCookieJar(private val file: File) : CookieJar {

    private val lock = Any()
    private val store = LinkedHashMap<String, Cookie>()

    init {
        runCatching {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val url = line.substring(0, tab).toHttpUrlOrNull() ?: return@forEach
                    val cookie = Cookie.parse(url, line.substring(tab + 1)) ?: return@forEach
                    store[keyOf(cookie)] = cookie
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (c in cookies) store[keyOf(c)] = c
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            purgeExpired()
            return store.values.filter { it.matches(url) }
        }
    }

    /** 退出登录 / 换账号时清空。 */
    fun clear() {
        synchronized(lock) {
            store.clear()
            runCatching { file.delete() }
        }
    }

    /**
     * 只清掉某个域的 Cookie，其它域不动。
     *
     * 用途：**发起一次新登录之前，必须先清掉 CAS 域的会话凭据**。
     * 带着上次登录留下的 `CASTGC`（CAS 的已认证票据）去请求登录页时，
     * CAS 会直接把你送回业务站点 —— 单点登录本就是这个行为 ——
     * 于是拿到的是业务页面而不是登录表单，自然解析不出 `lt` / `execution`。
     *
     * 只清 CAS 域而保留业务站点域，是为了让「换个账号登录失败」不至于
     * 顺手把当前已登录的账号也踢掉。
     */
    fun clearHost(host: String): Boolean = synchronized(lock) {
        val before = store.size
        store.keys.removeAll { key ->
            val domain = key.substringBefore('|').removePrefix(".")
            domain.equals(host, ignoreCase = true) || domain.endsWith(".$host", ignoreCase = true)
        }
        if (store.size != before) {
            persist()
            true
        } else {
            false
        }
    }

    /** 是否持有某个域的未过期 Cookie。用来判断「看起来登录过」。 */
    fun hasCookiesFor(host: String): Boolean = synchronized(lock) {
        purgeExpired()
        store.values.any { c ->
            val d = c.domain.removePrefix(".")
            host.equals(d, ignoreCase = true) || host.endsWith(".$d", ignoreCase = true)
        }
    }

    /** 当前持有的 Cookie 摘要，仅供界面展示与排错，不带值。 */
    fun summary(): List<String> = synchronized(lock) {
        purgeExpired()
        store.values.map { "${it.domain}${it.path}  ${it.name}" }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val dead = store.filterValues { it.expiresAt < now }.keys
        if (dead.isNotEmpty()) {
            store.keys.removeAll(dead)
            persist()
        }
    }

    private fun persist() {
        runCatching {
            if (store.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(store.values.joinToString("\n") { "${originOf(it)}\t$it" })
        }
    }

    private fun originOf(c: Cookie): String =
        (if (c.secure) "https://" else "http://") +
            c.domain.removePrefix(".") + c.path

    private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}"
}
