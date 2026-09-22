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

/**
 * 「手动签到（逐个点）」的队列状态。
 *
 * 这是**托底**：无头 HTTP 那条路万一走不通（服务端改了、要人机交互、
 * 需要图形验证码），还能退回「拿这个账号的 Cookie 开一个 WebView，自己点一下」。
 * 慢，但只要能上网就能用。
 */
data class ManualCheckIn(
    /** 待逐个走的账号，按顺序。 */
    val queue: List<AccountRecord>,
    /** 当前走到第几个。等于 [queue].size 表示走完了。 */
    val index: Int = 0,
    val outcomes: List<CheckInOutcome> = emptyList(),
) {
    val current: AccountRecord? get() = queue.getOrNull(index)
    val total: Int get() = queue.size

    /** 界面上的「第几个」，从 1 起。 */
    val position: Int get() = (index + 1).coerceAtMost(total)
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
    /** 非 null 表示正在「手动签到（逐个点）」。走完会自动清空，把结果交给 [checkIn]。 */
    val manual: ManualCheckIn? = null,
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
     *
     * [photo] 是严格模式要的那张照片（`data:image/jpeg;base64,…`）。
     * 同样**所有账号共用一张** —— 同一台手机、同一个时刻、同一间教室，
     * 本来就只有一张照片可拍。普通模式传空串即可。
     */
    fun checkInAll(session: Session, photo: String = "") {
        val targets = _state.value.accounts
        if (targets.isEmpty()) {
            showMessage("还没有登录任何账号")
            return
        }

        _state.update { it.copy(checkIn = CheckInProgress(0, targets.size)) }

        viewModelScope.launch {
            // 先定位，再进循环 —— 一次定位够所有账号用
            val fix = Position.current(getApplication())
            val position = (fix as? Position.Fix.Ok)?.text.orEmpty()

            // 取不到定位不阻止签到，但要说清楚原因 —— 只讲「没取到」，
            // 用户不知道该去打开定位开关还是去改权限设置
            if (fix is Position.Fix.Unavailable) {
                showMessage("没取到定位：${fix.reason}", long = true)
            }
            _state.update { it.copy(checkIn = CheckInProgress(0, targets.size)) }

            val outcomes = mutableListOf<CheckInOutcome>()

            for ((index, account) in targets.withIndex()) {
                val result = if (account.cookies.isEmpty()) {
                    CasClient.CheckInResult.NoSession("没有可用的会话，需要重新登录")
                } else {
                    // 现算，保证用的是此刻的时间戳；模式也要带上，否则严格模式会生成普通模式的链接
                    val url = Sign.buildUrl(session.ip, session.ipt, Sign.nowSeconds(), session.mode)
                    client.checkIn(account.cookies, url, position, photo)
                }

                outcomes += CheckInOutcome(account.username, result)
                _state.update {
                    it.copy(
                        checkIn = CheckInProgress(
                            done = index + 1,
                            total = targets.size,
                            outcomes = outcomes.toList(),
                        ),
                    )
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

    // ------------------------------------------------------------------ 手动签到（逐个点）

    /**
     * 开始逐个手动签到。
     *
     * 队列里**只排除完全没有 Cookie 的账号** —— 那种连打开都没意义。
     * 已经被标记为失效的**照样排进去**：那个标记可能是旧的，真实情况让服务端说了算
     * （WebView 落在 CAS 上才算数，见 ManualCheckInScreen）。这比拿一个缓存的
     * 布尔值替用户做决定要老实。
     */
    fun startManualCheckIn() {
        val targets = _state.value.accounts.filter { it.cookies.isNotEmpty() }
        if (targets.isEmpty()) {
            showMessage("还没有登录任何账号")
            return
        }
        val skipped = _state.value.accounts.size - targets.size
        if (skipped > 0) showMessage("已跳过 $skipped 个没有会话的账号", long = true)
        _state.update { it.copy(manual = ManualCheckIn(queue = targets)) }
    }

    /** 记录当前账号的结果，然后前进；走完了就把汇总交给签到结果弹窗。 */
    fun reportManual(result: CasClient.CheckInResult) {
        val manual = _state.value.manual ?: return
        val account = manual.current ?: return

        // 登录态确实没了的，顺手把标记改掉，免得列表还显示绿色
        if (result is CasClient.CheckInResult.LoginExpired) {
            repo.setValidity(account.username, false)
        }

        val outcomes = manual.outcomes + CheckInOutcome(account.username, result)
        val next = manual.index + 1

        if (next < manual.queue.size) {
            _state.update { it.copy(manual = manual.copy(index = next, outcomes = outcomes)) }
            return
        }

        // 走完了：收摊，把汇总交给「全部签到」那套弹窗 —— 两种方式的结果长得一样，
        // 用户不必学两套
        _state.update {
            it.copy(
                accounts = repo.all(),
                manual = null,
                checkIn = CheckInProgress(manual.queue.size, manual.queue.size, outcomes),
            )
        }
        val ok = outcomes.count { !it.result.isFailure }
        showMessage("手动签到完成：$ok / ${outcomes.size} 个账号成功")
    }

    /** 用户主动跳过当前这个。 */
    fun skipManual() = reportManual(CasClient.CheckInResult.Skipped("手动跳过"))

    /** 中途退出，不看汇总。 */
    fun dismissManual() {
        _state.update { it.copy(manual = null) }
    }

    /**
     * 取某个账号的 Cookie，用于注入 WebView。
     *
     * 返回 okhttp `Cookie.toString()` 的形状（`name=value; path=/; domain=…`），
     * 正好就是 `android.webkit.CookieManager.setCookie` 认的格式。
     */
    fun webCookies(username: String): List<String> = repo.cookiesOf(username).map { it.toString() }

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
