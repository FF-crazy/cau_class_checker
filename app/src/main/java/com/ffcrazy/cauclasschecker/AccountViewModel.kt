package com.ffcrazy.cauclasschecker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ffcrazy.cauclasschecker.web.AccountList
import com.ffcrazy.cauclasschecker.web.AccountStore
import com.ffcrazy.cauclasschecker.web.CasClient
import com.ffcrazy.cauclasschecker.web.LoginResult
import com.ffcrazy.cauclasschecker.web.PersistentCookieJar
import com.ffcrazy.cauclasschecker.web.StoredAccount
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AccountUiState(
    /** 登录过的账号，按最近登录时间倒序。每条的 [StoredAccount.valid] 决定界面样式。 */
    val accounts: List<StoredAccount> = emptyList(),
    /** 当前持有会话的账号；null 表示没有。 */
    val activeUser: String? = null,
    val busy: Boolean = false,
    /** true 表示正在二级登录页。 */
    val onLoginScreen: Boolean = false,
    /** 二级登录页预填的账号（从列表点进来时带上）。 */
    val prefillUsername: String = "",
)

/**
 * 账号管理。
 *
 * ## 为什么是「账号清单」而不是「多账号同时在线」
 * `class.cau.edu.cn` 只有一个 `PHPSESSID`，服务端对同一客户端只维持一条会话 ——
 * 任何时刻只可能有一个账号处于登录态。加上我们不保存密码，切换到另一个账号
 * 必然要重新输入。
 *
 * 所以清单里的 [StoredAccount.valid] 本质上是「**这个账号现在有没有可用会话**」：
 * 新登录的账号是有效的，之前那个会被标成已失效（它的会话确实被顶掉了）。
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AccountStore(app)
    private val cookieJar = PersistentCookieJar(File(app.filesDir, COOKIE_FILE))
    private val client = CasClient(cookieJar)

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        val remembered = store.activeUser
        // 只检查本地 Cookie 还在不在 —— 服务端认不认要等「验活」才知道，
        // 启动时不该为了这个多发一次网络请求。
        val hasSession = remembered != null && client.looksLoggedIn()
        if (remembered != null && !hasSession) {
            store.activeUser = null
        }

        val saved = store.load()
        val accounts = if (hasSession) {
            AccountList.invalidateAllExcept(saved, remembered!!)
        } else {
            // 本地没有会话，那么所有账号都不可能是有效的
            saved.map { it.copy(valid = false) }
        }
        store.save(accounts)
        _state.update {
            it.copy(accounts = accounts, activeUser = if (hasSession) remembered else null)
        }
    }

    // ------------------------------------------------------------------ 页面流转

    /** 打开二级登录页。[prefill] 用于从列表点某个账号时带上用户名。 */
    fun openLogin(prefill: String = "") {
        _state.update { it.copy(onLoginScreen = true, prefillUsername = prefill) }
    }

    fun closeLogin() {
        _state.update { it.copy(onLoginScreen = false, prefillUsername = "") }
    }

    // ------------------------------------------------------------------ 登录

    fun login(username: String, password: String) {
        if (_state.value.busy) return
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) {
            showMessage("请先填写账号和密码")
            return
        }

        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = client.login(name, password)) {
                is LoginResult.Success -> {
                    // 同一账号后覆盖前；服务端只有一条会话，
                    // 所以其余账号的会话确实被顶掉了，标记为失效是事实而非猜测
                    val upserted = store.upsert(result.username, System.currentTimeMillis())
                    val accounts = AccountList.invalidateAllExcept(upserted, result.username)
                    store.save(accounts)
                    store.activeUser = result.username

                    _state.update {
                        it.copy(
                            accounts = accounts,
                            activeUser = result.username,
                            busy = false,
                            onLoginScreen = false,
                            prefillUsername = "",
                        )
                    }
                    showMessage("登录成功，账户为：${result.username}")
                }

                is LoginResult.Failure -> {
                    // 停在二级页面，让用户直接改了重试
                    _state.update { it.copy(busy = false) }
                    showMessage(result.message, long = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 验活

    /**
     * 验活：确认这个账号的 Cookie 还有没有效。
     *
     * 只有持有会话的那个账号才谈得上验活 —— 服务端一次只维持一条会话，
     * 其它账号本来就没有会话可用。对它们直接判失效，而不是拿当前会话的
     * 探测结果去张冠李戴。
     */
    fun verify(username: String) {
        if (username != _state.value.activeUser) {
            setValidity(username, false)
            showMessage(
                "$username 没有可用会话（服务端一次只维持一条），已标记为失效",
                long = true,
            )
            return
        }

        viewModelScope.launch {
            when (val probe = client.probeSession()) {
                is CasClient.SessionProbe.Alive -> {
                    setValidity(username, true)
                    showMessage("$username 的 Cookie 仍然有效")
                }

                is CasClient.SessionProbe.Expired -> {
                    setValidity(username, false)
                    client.logout()
                    store.activeUser = null
                    _state.update { it.copy(activeUser = null) }
                    showMessage("$username 的 Cookie 已失效，需要重新登录", long = true)
                }

                is CasClient.SessionProbe.Unknown ->
                    showMessage("验活失败：${probe.reason}", long = true)
            }
        }
    }

    private fun setValidity(username: String, valid: Boolean) {
        val accounts = AccountList.withValidity(_state.value.accounts, username, valid)
        store.save(accounts)
        _state.update { it.copy(accounts = accounts) }
    }

    // ------------------------------------------------------------------ 退出 / 删除

    fun logout() {
        client.logout()
        store.activeUser = null
        val accounts = _state.value.accounts.map { it.copy(valid = false) }
        store.save(accounts)
        _state.update { it.copy(activeUser = null, accounts = accounts) }
        showMessage("已退出登录")
    }

    /** 从清单里删掉一个账号。 */
    fun removeAccount(username: String) {
        val wasActive = _state.value.activeUser == username
        if (wasActive) {
            client.logout()
            store.activeUser = null
        }
        val accounts = store.remove(username)
        _state.update {
            it.copy(accounts = accounts, activeUser = if (wasActive) null else it.activeUser)
        }
        showMessage("已删除账号 $username")
    }

    private fun showMessage(text: String, long: Boolean = false) {
        viewModelScope.launch { _messages.send(UiMessage(text, long)) }
    }

    private companion object {
        const val COOKIE_FILE = "cas-cookies.txt"
    }
}
