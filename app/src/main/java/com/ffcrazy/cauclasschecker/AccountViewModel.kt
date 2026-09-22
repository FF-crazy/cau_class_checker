package com.ffcrazy.cauclasschecker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ffcrazy.cauclasschecker.domain.Session
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.location.Position
import com.ffcrazy.cauclasschecker.web.AccountRecord
import com.ffcrazy.cauclasschecker.web.AccountRepository
import com.ffcrazy.cauclasschecker.web.CasClient
import com.ffcrazy.cauclasschecker.web.LoginResult
import com.ffcrazy.cauclasschecker.web.SecureAccountFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 批量签到时，单个账号的结果。 */
data class CheckInOutcome(
    val username: String,
    val result: CasClient.CheckInResult,
)

/** 批量签到的进度与结果。[done] < [total] 表示还在跑。 */
data class CheckInProgress(
    val done: Int,
    val total: Int,
    val outcomes: List<CheckInOutcome> = emptyList(),
) {
    val running: Boolean get() = done < total
}

data class AccountUiState(
    /** 登录过的账号。每条的 [AccountRecord.valid] 决定界面样式。 */
    val accounts: List<AccountRecord> = emptyList(),
    val busy: Boolean = false,
    /** true 表示正在二级登录页。 */
    val onLoginScreen: Boolean = false,
    /** 二级登录页预填的账号（从列表点进来时带上）。 */
    val prefillUsername: String = "",
    /** 非 null 表示正在批量签到或已有结果待查看。 */
    val checkIn: CheckInProgress? = null,
)

/**
 * 账号管理。
 *
 * ## 多账号同时有效
 * 每个账号保存**自己的**一份会话 Cookie。`class.cau.edu.cn` 用 PHP 的
 * `PHPSESSID`，服务端按它区分会话，所以多个账号可以同时处于登录态 ——
 * 各账号互不干扰，这也是「多账号一起签到」的前提。
 *
 * 账号清单连同 Cookie 一起加密落盘（Keystore 的 AES-GCM 密钥），不引数据库。
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AccountRepository(SecureAccountFile(File(app.filesDir, ACCOUNT_FILE)))

    private val client = CasClient()

    private val _state = MutableStateFlow(AccountUiState(accounts = repo.all()))
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    // ------------------------------------------------------------------ 页面流转

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
                    // 只写入这个账号自己的会话，其它账号纹丝不动
                    val accounts = repo.saveSession(
                        result.username,
                        result.cookies,
                        System.currentTimeMillis(),
                    )
                    _state.update {
                        it.copy(
                            accounts = accounts,
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
     * 验活：用这个账号**自己的** Cookie 探测会话是否还有效。
     *
     * 每个账号独立验证，互不影响 —— 这正是分开存 Cookie 换来的能力。
     */
    fun verify(username: String) {
        val record = repo.find(username) ?: return

        if (record.cookies.isEmpty()) {
            updateValidity(username, false)
            showMessage("$username 没有可用的会话，需要重新登录", long = true)
            return
        }

        viewModelScope.launch {
            when (val probe = client.probeSession(record.cookies)) {
                is CasClient.SessionProbe.Alive -> {
                    updateValidity(username, true)
                    showMessage("$username 的 Cookie 仍然有效")
                }

                is CasClient.SessionProbe.Expired -> {
                    updateValidity(username, false)
                    showMessage("$username 的 Cookie 已失效，需要重新登录", long = true)
                }

                is CasClient.SessionProbe.Unknown ->
                    showMessage("验活失败：${probe.reason}", long = true)
            }
        }
    }

    private fun updateValidity(username: String, valid: Boolean) {
        _state.update { it.copy(accounts = repo.setValidity(username, valid)) }
    }

    // ------------------------------------------------------------------ 批量签到

    /**
     * 用所有账号各签一次到。
     *
     * 关键点：**每个账号发请求前才现算 URL**。`t` 是按秒滚动的，
     * 若先把 15 条 URL 一次性生成好再逐个发，靠后的账号拿到的码可能已经过期。
     *
     * 串行发送而不是并发：账号数量很少（不超过 15），串行更温和，
     * 也不容易触发服务端的频率限制；界面有进度可看，不会显得卡住。
     *
     * 定位**只取一次**，所有账号共用：本来就是同一台手机待在同一个位置。
     * 取不到也照签 —— 教师端后台那一列会是空的，但不该因此放弃签到。
     */
    fun checkInAll(session: Session) {
        val targets = _state.value.accounts
        if (targets.isEmpty()) {
            showMessage("还没有登录任何账号")
            return
        }

        _state.update { it.copy(checkIn = CheckInProgress(0, targets.size)) }

        viewModelScope.launch {
            // 先定位，再进循环 —— 一次定位够所有账号用
            val position = Position.current(getApplication())
            if (position == null) {
                showMessage("没取到定位，本次签到不带 GPS 坐标", long = true)
            }

            val outcomes = mutableListOf<CheckInOutcome>()

            for ((index, account) in targets.withIndex()) {
                val result = if (account.cookies.isEmpty()) {
                    CasClient.CheckInResult.NoSession("没有可用的会话，需要重新登录")
                } else {
                    // 现算，保证用的是此刻的时间戳
                    val url = Sign.buildUrl(session.ip, session.ipt, Sign.nowSeconds())
                    client.checkIn(account.cookies, url, position.orEmpty())
                }

                outcomes += CheckInOutcome(account.username, result)
                _state.update {
                    it.copy(checkIn = CheckInProgress(index + 1, targets.size, outcomes.toList()))
                }
            }

            // 登录态确实没了的，顺手把标记改掉，免得列表还显示绿色
            outcomes.forEach { outcome ->
                val r = outcome.result
                if (r is CasClient.CheckInResult.LoginExpired || r is CasClient.CheckInResult.NoSession) {
                    repo.setValidity(outcome.username, false)
                }
            }

            val succeeded = outcomes.count { !it.result.isFailure }
            _state.update {
                it.copy(
                    accounts = repo.all(),
                    checkIn = CheckInProgress(targets.size, targets.size, outcomes),
                )
            }
            showMessage("签到完成：$succeeded / ${outcomes.size} 个账号成功")
        }
    }

    fun dismissCheckIn() {
        _state.update { it.copy(checkIn = null) }
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 从清单里删掉一个账号 —— 连同它自己的会话 Cookie 一起删除。
     * 其它账号完全不受影响。
     */
    fun removeAccount(username: String) {
        val accounts = repo.remove(username)
        _state.update { it.copy(accounts = accounts) }
        showMessage("已删除账号 $username")
    }

    private fun showMessage(text: String, long: Boolean = false) {
        viewModelScope.launch { _messages.send(UiMessage(text, long)) }
    }

    private companion object {
        const val ACCOUNT_FILE = "accounts.enc"
    }
}
