package com.ffcrazy.cauclasschecker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    /** 登录过的账号，按最近登录时间倒序。 */
    val accounts: List<StoredAccount> = emptyList(),
    /** 当前处于登录态的账号；null 表示没有。 */
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
 * 必然要重新输入。所以这份清单的定位是：**记住用过哪些账号、当前是哪个、
 * 方便一键回到某个账号**。
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AccountStore(app)
    private val cookieJar = PersistentCookieJar(File(app.filesDir, COOKIE_FILE))
    private val client = CasClient(cookieJar)

    private val _state = MutableStateFlow(
        AccountUiState(
            accounts = emptyList(),
            // 记着上次登录的账号，但只有会话 Cookie 还在才算数
            activeUser = null,
        ),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        val remembered = store.activeUser
        val stillValid = remembered != null && client.looksLoggedIn()
        if (remembered != null && !stillValid) {
            // Cookie 没了（被清掉或过期），登录态不成立，但账号仍留在清单里
            store.activeUser = null
        }
        _state.update {
            it.copy(accounts = store.load(), activeUser = remembered.takeIf { stillValid })
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

    // ------------------------------------------------------------------ 登录 / 退出

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
                    // 同一账号后覆盖前：upsert 会顶掉旧条目并刷新时间，不会重复
                    val updated = store.upsert(result.username, System.currentTimeMillis())
                    store.activeUser = result.username
                    _state.update {
                        it.copy(
                            accounts = updated,
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

    fun logout() {
        client.logout()
        store.activeUser = null
        // 只清登录态，不清账号清单 —— 下次还能一键回来
        _state.update { it.copy(activeUser = null) }
        showMessage("已退出登录")
    }

    /**
     * 验活：探测这个账号的登录态是否还有效。
     *
     * ⚠️ 服务端对同一客户端只维持**一条**会话，所以只有当前登录的那个账号
     * 才谈得上"验活"。对其它账号，验活等于「先登录它」—— 而那需要密码，
     * 我们不存密码，所以只能如实告知。
     */
    fun verify(username: String) {
        if (username != _state.value.activeUser) {
            showMessage("$username 当前未登录。服务端一次只维持一条会话，验活需要先登录该账号。", long = true)
            return
        }
        viewModelScope.launch {
            when (val probe = client.probeSession()) {
                is CasClient.SessionProbe.Alive ->
                    showMessage("$username 的登录态仍然有效")

                is CasClient.SessionProbe.Expired -> {
                    // 服务端已经不认了，把本地状态对齐
                    client.logout()
                    store.activeUser = null
                    _state.update { it.copy(activeUser = null) }
                    showMessage("$username 的登录态已过期，需要重新登录", long = true)
                }

                is CasClient.SessionProbe.Unknown ->
                    showMessage("验活失败：${probe.reason}", long = true)
            }
        }
    }

    /** 从清单里删掉一个账号。 */
    fun removeAccount(username: String) {
        val updated = store.remove(username)
        val wasActive = _state.value.activeUser == username
        if (wasActive) {
            client.logout()
            store.activeUser = null
        }
        _state.update {
            it.copy(accounts = updated, activeUser = if (wasActive) null else it.activeUser)
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
