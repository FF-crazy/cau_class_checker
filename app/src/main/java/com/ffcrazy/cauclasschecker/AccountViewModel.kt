package com.ffcrazy.cauclasschecker

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ffcrazy.cauclasschecker.web.CasClient
import com.ffcrazy.cauclasschecker.web.LoginResult
import com.ffcrazy.cauclasschecker.web.PersistentCookieJar
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
    /** 已登录的账号；null 表示未登录。 */
    val loggedInUser: String? = null,
    val busy: Boolean = false,
) {
    val loggedIn: Boolean get() = loggedInUser != null
}

/**
 * 账号管理。
 *
 * 无头登录：整个流程走 HTTP，不用 WebView。登录成功后会话 Cookie 落盘，
 * **密码只在这一刻存在于内存里，不写入任何存储** —— 所以服务端 session
 * 过期后需要重新输入。
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val cookieJar = PersistentCookieJar(File(app.filesDir, COOKIE_FILE))

    private val client = CasClient(cookieJar)

    private val _state = MutableStateFlow(
        AccountUiState(
            // 记住的账号只有在 Cookie 还在时才认为处于登录态
            loggedInUser = prefs.getString(KEY_USER, null)?.takeIf { client.looksLoggedIn() },
        ),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    fun login(username: String, password: String) {
        if (_state.value.busy) return
        if (username.isBlank() || password.isEmpty()) {
            showMessage("请先填写账号和密码")
            return
        }

        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = client.login(username.trim(), password)) {
                is LoginResult.Success -> {
                    prefs.edit().putString(KEY_USER, result.username).apply()
                    _state.update { it.copy(busy = false, loggedInUser = result.username) }
                    showMessage("登录成功")
                }

                is LoginResult.Failure -> {
                    _state.update { it.copy(busy = false) }
                    showMessage(result.message, long = true)
                }
            }
        }
    }

    fun logout() {
        client.logout()
        prefs.edit().remove(KEY_USER).apply()
        _state.update { it.copy(loggedInUser = null) }
        showMessage("已退出登录")
    }

    private fun showMessage(text: String, long: Boolean = false) {
        viewModelScope.launch { _messages.send(UiMessage(text, long)) }
    }

    private companion object {
        const val PREFS = "account"
        const val KEY_USER = "logged_in_user"
        const val COOKIE_FILE = "cas-cookies.txt"
    }
}
