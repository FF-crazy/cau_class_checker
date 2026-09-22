package com.ffcrazy.cauclasschecker.web

import android.content.Context

/** 一个登录过的账号。 */
data class StoredAccount(
    val username: String,
    /** 上次登录成功的时刻（毫秒）。 */
    val loginAt: Long,
    /**
     * 会话是否验证有效。
     *
     * 刚登录成功时为 true；「验活」探到服务端不认了、或这条会话被别的账号
     * 顶掉之后为 false。界面据此决定是绿色描边还是灰化 + 「已失效」。
     */
    val valid: Boolean = true,
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
        sorted(accounts.filterNot { it.username == username } + StoredAccount(username, at, valid = true))

    fun remove(accounts: List<StoredAccount>, username: String): List<StoredAccount> =
        accounts.filterNot { it.username == username }

    /** 只改某个账号的有效标记，其余原样返回。 */
    fun withValidity(accounts: List<StoredAccount>, username: String, valid: Boolean): List<StoredAccount> =
        accounts.map { if (it.username == username) it.copy(valid = valid) else it }

    /**
     * 把 [keepUsername] 之外的账号全部标记为失效。
     *
     * 服务端对同一客户端只维持**一条**会话，所以一个账号登录成功就意味着
     * 之前那条被顶掉了 —— 这是事实，界面上不该继续显示成绿色。
     */
    fun invalidateAllExcept(accounts: List<StoredAccount>, keepUsername: String): List<StoredAccount> =
        accounts.map { if (it.username == keepUsername) it.copy(valid = true) else it.copy(valid = false) }

    /**
     * 序列化成一行一条 `用户名\t登录时间\t是否有效`。
     *
     * 用户名是学号/工号，不会含制表符；解析时按制表符从右往左切，
     * 所以即便将来用户名里混进了别的东西也只会切错时间戳，不会串账号。
     *
     * 第三段是后加的。老格式（只有两段）解析时**默认判为失效** ——
     * 宁可保守：有效性由 App 启动时的会话检查重新判定，不靠猜。
     */
    fun serialize(accounts: List<StoredAccount>): String =
        accounts.joinToString("\n") { "${it.username}\t${it.loginAt}\t${if (it.valid) 1 else 0}" }

    fun parse(text: String?): List<StoredAccount> =
        text.orEmpty()
            .lineSequence()
            .mapNotNull { parseLine(it) }
            .toList()
            .let(::sorted)

    private fun parseLine(line: String): StoredAccount? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val username = parts[0]
        if (username.isEmpty()) return null
        val at = parts[1].toLongOrNull() ?: return null
        val valid = parts.getOrNull(2) == "1"
        return StoredAccount(username, at, valid)
    }
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

    /** 整体覆盖保存。改有效标记时用。 */
    fun save(accounts: List<StoredAccount>) {
        prefs.edit().putString(KEY_ACCOUNTS, AccountList.serialize(accounts)).apply()
    }

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

    private companion object {
        const val PREFS = "account"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE = "active_user"
    }
}
