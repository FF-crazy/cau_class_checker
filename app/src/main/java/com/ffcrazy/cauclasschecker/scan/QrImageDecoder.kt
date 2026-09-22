package com.ffcrazy.cauclasschecker.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 相册图片的二维码识别。
 *
 * 网页版这里是一整套 jsQR + 10MB OpenCV 的两级管线（`decode-static.mjs` 全 153 行
 * 加一个 Web Worker）；原生这边 ZXing 一次调用就够了，而且静态图可以慢慢来。
 *
 * 两个要点：
 *  - `ImageDecoder` 在 API 28+ 会**自动按 EXIF 转正**，所以不用引 exifinterface
 *  - 必须用 `ALLOCATOR_SOFTWARE`：硬件位图不允许 `getPixels`，会直接抛异常
 */
object QrImageDecoder {

    /** 与网页版 `MAX_EDGE = 1280` 对齐（`decode-static.mjs:3`）。 */
    const val MAX_EDGE = 1280

    fun decode(context: Context, uri: Uri): String? =
        loadScaled(context, uri)?.let { decodeBitmap(it) }

    private fun loadScaled(context: Context, uri: Uri): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val longEdge = maxOf(w, h)
            if (longEdge > MAX_EDGE) {
                val f = MAX_EDGE.toFloat() / longEdge
                decoder.setTargetSize(
                    (w * f).toInt().coerceAtLeast(1),
                    (h * f).toInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()

    /** 从已解码的位图里找二维码。失败返回 null（调用方据此提示用户换张更清晰的）。 */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.ALSO_INVERTED to true,
                    // 静态图没有实时性要求，可以用力解——相机那侧就不开了，太慢
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return runCatching { reader.decodeWithState(binary).text }
            .getOrNull()
            ?.also { reader.reset() }
            ?: run {
                reader.reset()
                null
            }
    }
}
