package com.ffcrazy.cauclasschecker.web

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 某一个账号自己的 Cookie 罐。
 *
 * 这是「多账号同时签到」的关键：**每个账号一个罐子**，彼此完全隔离。
 * 服务端按 `PHPSESSID` 区分会话，所以各账号的会话可以同时有效。
 *
 * 登录时用一个空的罐子跑流程，跑完把 [snapshot] 的结果存进该账号；
 * 之后要用这个账号做事，就用它的 Cookie 重建一个罐子。
 */
class AccountCookieJar(initial: List<StoredCookie> = emptyList()) : CookieJar {

    /** key -> (来源URL, Cookie)。按 domain|path|name 去重，与浏览器行为一致。 */
    private val store = LinkedHashMap<String, Pair<String, Cookie>>()

    init {
        initial.forEach { stored ->
            runCatching {
                val url = stored.origin.toHttpUrl()
                Cookie.parse(url, stored.value)?.let { store[keyOf(it)] = stored.origin to it }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (c in cookies) store[keyOf(c)] = url.toString() to c
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.entries.removeAll { it.value.second.expiresAt < now }
        return store.values.map { it.second }.filter { it.matches(url) }
    }

    /** 导出成可持久化的形式。 */
    fun snapshot(): List<StoredCookie> =
        store.values.map { (origin, cookie) -> StoredCookie(origin, cookie.toString()) }

    fun isEmpty(): Boolean = store.isEmpty()

    private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}"
}

/**
 * 账号清单的落盘方式。
 *
 * 抽成接口是为了让 [AccountRepository] 能在普通 JVM 单测里跑 ——
 * 真实的实现走 Android Keystore，那是 Android 专有的，测不了。
 */
interface AccountStorage {
    fun readText(): String?
    fun writeText(text: String)
}

/**
 * 账号清单 + 每个账号的会话 Cookie，加密落盘。
 *
 * 账号数量很少（不超过 15），所以整份清单一次读写即可，不需要数据库。
 * 全部操作在内存里完成，改完整体覆盖写文件。
 */
class AccountRepository(private val storage: AccountStorage) {

    private val lock = Any()

    private var records: List<AccountRecord> = loadFromDisk()

    private fun loadFromDisk(): List<AccountRecord> =
        storage.readText()?.let { AccountCodec.decode(it) }.orEmpty()

    fun all(): List<AccountRecord> = synchronized(lock) { records }

    fun find(username: String): AccountRecord? =
        synchronized(lock) { records.firstOrNull { it.username == username } }

    fun hasAny(): Boolean = synchronized(lock) { records.isNotEmpty() }

    /**
     * 登录成功后写入该账号的会话。
     *
     * **不动其它账号** —— 这正是多账号方案与之前「共用一条会话」的根本区别。
     * 同一账号重复登录则覆盖它自己（后覆盖前）。
     */
    fun saveSession(
        username: String,
        cookies: List<StoredCookie>,
        at: Long,
    ): List<AccountRecord> = update { list ->
        val merged = AccountRecord(username, at, valid = true, cookies = cookies)
        (list.filterNot { it.username == username } + merged)
            .sortedByDescending { it.loginAt }
    }

    fun setValidity(username: String, valid: Boolean): List<AccountRecord> =
        update { list ->
            list.map { if (it.username == username) it.copy(valid = valid) else it }
        }

    fun remove(username: String): List<AccountRecord> =
        update { list -> list.filterNot { it.username == username } }

    fun clearAll(): List<AccountRecord> = update { emptyList() }

    /** 取某个账号的 Cookie，还原成 OkHttp 的 Cookie 对象。 */
    fun cookiesOf(username: String): List<Cookie> =
        find(username)?.cookies.orEmpty().mapNotNull { stored ->
            runCatching { Cookie.parse(stored.origin.toHttpUrl(), stored.value) }.getOrNull()
        }

    /** 为某个账号开一个罐子，用于发起请求。 */
    fun jarFor(username: String): AccountCookieJar =
        AccountCookieJar(find(username)?.cookies.orEmpty())

    private fun update(block: (List<AccountRecord>) -> List<AccountRecord>): List<AccountRecord> =
        synchronized(lock) {
            records = block(records)
            // 落盘失败不该让内存里的状态回滚 —— 用户这次操作是成功的，
            // 下次启动重新登录即可。所以这里吞掉异常。
            runCatching { storage.writeText(AccountCodec.encode(records)) }
            records
        }
}
