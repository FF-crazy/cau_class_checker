package com.ffcrazy.cauclasschecker.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.roundToInt

/**
 * 二维码编码。
 *
 * 这半边是**纯 JVM** 的——不引用任何 Android API，所以能在单元测试里跑
 * 「编码 → 取像素 → 解码回来」的完整往返，不上手机就能证明生成的码真的可扫。
 */
object QrEncoder {

    /**
     * 把文本编成二维码点阵。
     *
     * 纠错级用 **M**，对齐网页版的 `QRCode.CorrectLevel.M`（`index.html:498`）。
     * 留 4 模块静区（ZXing 默认值，显式写出以免以后被误改）。
     */
    fun encode(text: String, size: Int): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * 二维码的像素边长。复刻网页版 `qrPixelSize()`（`index.html:471-475`）：
     * 先把宽度减 24、夹到 [180, 280]，再乘屏幕密度。
     *
     * 必须乘密度——否则在高分屏上二维码会被放大插值，边缘发虚，教室的扫码器可能读不出来。
     */
    fun pixelSize(boxWidthDp: Float, density: Float): Int {
        val css = (boxWidthDp - 24f).toInt().coerceIn(180, 280)
        return (css * density).roundToInt()
    }
}
