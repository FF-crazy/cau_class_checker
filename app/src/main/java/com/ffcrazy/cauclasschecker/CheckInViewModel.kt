package com.ffcrazy.cauclasschecker

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ffcrazy.cauclasschecker.domain.Session
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.qr.QrEncoder
import com.ffcrazy.cauclasschecker.qr.toBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 一条一次性提示。
 *
 * @param long 需要用户读完再消失（比如识别失败的引导语）用 true，
 *             「已刷新」这种扫一眼就够的用 false。
 */
data class UiMessage(val text: String, val long: Boolean = false)

/** 签到页的全部状态。 [qr] 为 null 表示还没有会话。 */
data class CheckInUiState(
    val session: Session? = null,
    val url: String = "",
    val qr: Bitmap? = null,
    val t: Long = 0L,
    val tt: String = "",
    val autoRefresh: Boolean = true,
) {
    val hasSession: Boolean get() = session != null
}

/**
 * 签到页状态机。
 *
 * 三条输入路径（扫码 / 相册 / 粘贴链接）最终都汇入 [startSession]，
 * 失败都汇入 [showError] —— 对应网页版的 `onSessionReady` / `showError`。
 */
class CheckInViewModel : ViewModel() {

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    /**
     * 提示走 Channel 而不是塞进 StateFlow。
     *
     * 因为「弹一条提示」是**一次性事件**，不是状态：放进 state 的话，
     * 转屏、切后台回来都会因为重新订阅而重放一遍，用户会看到同一条提示弹两次。
     * Channel 保证每条提示只被消费一次。
     */
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    /** 当前二维码的像素边长，由界面按实际宽度和屏幕密度算好回填。 */
    private var qrSizePx = 720

    /**
     * 上一次真正渲染的 URL。
     *
     * 用来复刻网页版的记忆化（`index.html:485` 的 `lastQrText`）：
     * 定时器每 500ms 跑一次，但 `t` 只在秒边界变化，
     * 所以一秒最多重画一次，而不是两次——省掉一半的无用位图分配。
     */
    private var lastQrUrl = ""

    private var ticker: Job? = null

    /** 由 Activity 的 onResume / onPause 驱动，实现「只在前台刷新」。 */
    fun setForeground(foreground: Boolean) {
        if (foreground) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = viewModelScope.launch {
            while (isActive) {
                if (_state.value.autoRefresh) tick()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 界面尺寸变化时调用；尺寸变了必须重画。 */
    fun onQrSizeChanged(px: Int) {
        if (px <= 0 || px == qrSizePx) return
        qrSizePx = px
        lastQrUrl = ""
        tick()
    }

    /** 弹一条一次性提示。 */
    fun showMessage(text: String, long: Boolean = false) {
        viewModelScope.launch { _messages.send(UiMessage(text, long)) }
    }

    /** 粘贴链接入口。 */
    fun applyLink(raw: String) {
        val parsed = Sign.parseSignUrl(raw)
        if (parsed == null) {
            showError(MSG_BAD_LINK)
            return
        }
        startSession(parsed)
        showMessage(MSG_OK)
    }

    /** 所有输入路径的汇合点。 */
    fun startSession(session: Session) {
        lastQrUrl = ""
        _state.update { it.copy(session = session, t = 0L, tt = "") }
        tick()
    }

    /**
     * 按当前时间重算 URL 与二维码。
     *
     * 对应网页版的 `updateDisplay`（`index.html:507-518`）：**输入里的 t / tt 一律丢弃**，
     * 这里永远用 `nowSeconds()` 重新签发。
     */
    fun tick() {
        val session = _state.value.session ?: return
        val t = Sign.nowSeconds()
        val url = Sign.buildUrl(session.ip, session.ipt, t)
        if (url == lastQrUrl) return
        lastQrUrl = url
        val qr = QrEncoder.encode(url, qrSizePx).toBitmap()
        _state.update { it.copy(url = url, qr = qr, t = t, tt = Sign.ticket(t, session.ip)) }
    }

    /** 「立即刷新」——强制重画，不等到下一秒。 */
    fun forceRefresh() {
        lastQrUrl = ""
        tick()
        showMessage(MSG_REFRESHED)
    }

    fun setAutoRefresh(enabled: Boolean) = _state.update { it.copy(autoRefresh = enabled) }

    /** 返回主页：清掉会话，但保留「自动刷新」这个偏好。 */
    fun clearSession() {
        lastQrUrl = ""
        _state.update { CheckInUiState(autoRefresh = it.autoRefresh) }
    }

    /** 统一的错误出口：清空会话、停掉二维码，对应网页版 `showError`（`index.html:558`）。 */
    fun showError(message: String) {
        lastQrUrl = ""
        _state.update {
            it.copy(session = null, url = "", qr = null, t = 0L, tt = "")
        }
        showMessage(message, long = true)
    }

    companion object {
        /** 与网页版 `setInterval(updateDisplay, 500)` 一致（`index.html:530`）。 */
        const val TICK_MS = 500L

        const val MSG_BAD_LINK = "链接里需要有效的 ip 和 ipt；旧链接里的时间戳会被忽略，改用当前时间重算。"
        const val MSG_OK = "已生成当前时间的签到码"
        const val MSG_REFRESHED = "已按当前时间刷新"
        const val MSG_BAD_QR = "识别到的内容不是易签到链接（需要包含 ip、ipt 参数）："
        const val MSG_NO_QR = "未能识别二维码，请换更清晰的近景或裁切二维码区域后重试。"
    }
}
