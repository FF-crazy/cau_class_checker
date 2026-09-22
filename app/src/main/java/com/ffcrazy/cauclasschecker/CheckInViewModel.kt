package com.ffcrazy.cauclasschecker

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ffcrazy.cauclasschecker.domain.Session
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.qr.QrEncoder
import com.ffcrazy.cauclasschecker.qr.toBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 签到页的全部状态。 [qr] 为 null 表示还没有会话。 */
data class CheckInUiState(
    val session: Session? = null,
    val url: String = "",
    val qr: Bitmap? = null,
    val t: Long = 0L,
    val tt: String = "",
    val autoRefresh: Boolean = true,
    val status: String? = null,
    val error: String? = null,
) {
    val hasSession: Boolean get() = session != null
}

/**
 * 签到页状态机。
 *
 * 三条输入路径（拍照 / 相册 / 粘贴链接）最终都汇入 [startSession]，
 * 失败都汇入 [showError]——对应网页版的 `onSessionReady` / `showError`。
 */
class CheckInViewModel : ViewModel() {

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

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

    /** 粘贴链接入口。 */
    fun applyLink(raw: String) {
        val parsed = Sign.parseSignUrl(raw)
        if (parsed == null) {
            showError(MSG_BAD_LINK)
            return
        }
        startSession(parsed, MSG_FROM_LINK)
    }

    /** 所有输入路径的汇合点。 */
    fun startSession(session: Session, status: String?) {
        lastQrUrl = ""
        _state.update {
            it.copy(
                session = session,
                status = status,
                error = null,
                t = 0L,
                tt = "",
            )
        }
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
        _state.update { it.copy(status = MSG_REFRESHED) }
    }

    fun setAutoRefresh(enabled: Boolean) = _state.update { it.copy(autoRefresh = enabled) }

    /** 统一的错误出口：清空会话、停掉二维码，对应网页版 `showError`（`index.html:558`）。 */
    fun showError(message: String) {
        lastQrUrl = ""
        _state.update {
            it.copy(
                session = null,
                url = "",
                qr = null,
                t = 0L,
                tt = "",
                status = null,
                error = message,
            )
        }
    }

    fun dismissStatus() = _state.update { it.copy(status = null, error = null) }

    companion object {
        /** 与网页版 `setInterval(updateDisplay, 500)` 一致（`index.html:530`）。 */
        const val TICK_MS = 500L

        const val MSG_BAD_LINK = "链接里需要有效的 ip 和 ipt；旧链接中的 t、tt 会被忽略，改用当前时间重算。"
        const val MSG_FROM_LINK = "已从链接提取 ip / ipt，正在按当前时间生成签到码"
        const val MSG_REFRESHED = "已按当前时间刷新"
        const val MSG_BAD_QR = "识别到的内容不是易签到链接（需要包含 ip、ipt 参数）："
        const val MSG_NO_QR = "未能识别二维码，请换更清晰的近景或裁切二维码区域后重试。"
    }
}
