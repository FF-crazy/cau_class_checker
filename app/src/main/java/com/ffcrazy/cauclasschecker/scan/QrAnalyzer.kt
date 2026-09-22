package com.ffcrazy.cauclasschecker.scan

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机帧的二维码识别器。
 *
 * 三层节流，缺一不可——否则这个回调会以 30fps 疯狂解码，把 CPU 和电量吃干：
 *
 *  1. `STRATEGY_KEEP_ONLY_LATEST`（在绑定时设置）：帧堆积时丢旧留新，不排队
 *  2. [busy]：保证同时最多一个解码在飞，绝不允许重入
 *  3. [THROTTLE_MS]：墙钟节流。二维码一秒最多变一次，一秒试 30 次纯属浪费，
 *     降到 6 次左右对手感毫无影响
 *
 * 解码成功后置 [finished]，后续帧直接返回，相机随即被解绑。
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // 只认二维码——这是最大的性能收益点，别再去找条形码了
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // 等价于网页版 jsQR 的 inversionAttempts:"attemptBoth"（decode-static.mjs:30）
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }

    private val busy = AtomicBoolean(false)

    @Volatile
    private var finished = false

    private var lastAttemptMs = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
            if (finished) return

            val now = System.currentTimeMillis()
            if (now - lastAttemptMs < THROTTLE_MS) return
            if (!busy.compareAndSet(false, true)) return
            lastAttemptMs = now

            val text = tryDecode(image)
            if (text != null) {
                finished = true
                onDecoded(text)
            }
        } catch (t: Throwable) {
            onError(t)
        } finally {
            busy.set(false)
            // 必须关，否则分析器会很快用完缓冲区并停止回调
            image.close()
        }
    }

    private fun tryDecode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (buffer.remaining() < Luma.requiredBytes(rowStride, height)) return null

        val luma = Luma.copyYPlane(buffer, pixelStride, rowStride, width, height)
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        // ZXing 的二维码定位器靠三个角上的定位图案找码，本身就能容忍任意旋转，
        // 所以这里不做物理旋转 —— 先在真机上验证，真出现 90/270 度扫不出再补。
        return runCatching { reader.decodeWithState(bitmap).text }
            .getOrNull()
            ?.also { reader.reset() }
            ?: run {
                reader.reset()
                null
            }
    }

    companion object {
        /** 一秒最多尝试 6 次。二维码一秒只变一次，再多也是白解。 */
        const val THROTTLE_MS = 160L
    }
}
