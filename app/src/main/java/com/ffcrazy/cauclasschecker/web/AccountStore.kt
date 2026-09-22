package com.ffcrazy.cauclasschecker.web

import android.content.Context

/** 一个登录过的账号。 */
data class StoredAccount(
    val username: String,
    /** 上次登录成功的时刻（毫秒）。 */
    val loginAt: Long,
)

/**
 * 账号清单的纯逻辑：增删、去重、序列化。
 *
 * 不依赖 Android，所以「同一账号后覆盖前」这类行为能在单测里钉死 ——
 * 它是用户明确要求的行为，不该靠手点验证。
 */
object AccountList {

    /** 按最近登录时间倒序。 */
    fun sorted(accounts: List<StoredAccount>): List<StoredAccount> =
        accounts.sortedByDescending { it.loginAt }

    /**
     * 记一次登录成功。
     *
     * **同一账号后覆盖前**：已存在同名的先剔除，再插入新的，所以反复登录
     * 同一账号只会留一条（时间刷新、位置提到最前），不会堆积重复条目。
     */
    fun upsert(accounts: List<StoredAccount>, username: String, at: Long): List<StoredAccount> =
        sorted(accounts.filterNot { it.username == username } + StoredAccount(username, at))

    fun remove(accounts: List<StoredAccount>, username: String): List<StoredAccount> =
        accounts.filterNot { it.username == username }

    /**
     * 序列化成一行一条 `用户名\t登录时间`。
     *
     * 用户名是学号/工号，不会含制表符；解析用 `lastIndexOf('\t')`，
     * 所以即便将来用户名里混进了别的东西也只会切错时间戳，不会串账号。
     */
    fun serialize(accounts: List<StoredAccount>): String =
        accounts.joinToString("\n") { "${it.username}\t${it.loginAt}" }

    fun parse(text: String?): List<StoredAccount> =
        text.orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val at = line.substring(tab + 1).toLongOrNull() ?: return@mapNotNull null
                StoredAccount(line.substring(0, tab), at)
            }
            .toList()
            .let(::sorted)
}

/**
 * 账号清单的持久化。
 *
 * 注意这里存的是**「登录过哪些账号」**，不是「同时登录多个账号」——
 * 服务端一个客户端只维持一条会话（`class.cau.edu.cn` 只有一个 `PHPSESSID`），
 * 同一时刻只可能有一个账号处于登录态。这份清单的用途是：记住用过哪些账号、
 * 哪个是当前登录的、方便一键重新登录。
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<StoredAccount> = AccountList.parse(prefs.getString(KEY_ACCOUNTS, null))

    fun upsert(username: String, at: Long): List<StoredAccount> =
        AccountList.upsert(load(), username, at).also { save(it) }

    fun remove(username: String): List<StoredAccount> =
        AccountList.remove(load(), username).also { save(it) }

    fun clear() {
        prefs.edit().remove(KEY_ACCOUNTS).remove(KEY_ACTIVE).apply()
    }

    /** 当前登录的账号。仅在会话 Cookie 还在时才有意义。 */
    var activeUser: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value)
            }.apply()
        }

    private fun save(accounts: List<StoredAccount>) {
        prefs.edit().putString(KEY_ACCOUNTS, AccountList.serialize(accounts)).apply()
    }

    private companion object {
        const val PREFS = "account"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE = "active_user"
    }
}
