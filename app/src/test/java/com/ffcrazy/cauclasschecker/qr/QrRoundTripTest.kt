package com.ffcrazy.cauclasschecker.qr

import com.ffcrazy.cauclasschecker.domain.Sign
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码往返测试——**这是没有模拟器的情况下最有价值的一个测试**。
 *
 * 它不上手机、不开相机，纯 JVM 就把「我们生成的二维码真的能被扫回原文」这件事证明了。
 * 以后谁改了纠错级、静区、尺寸或字符集，这里会立刻红。
 */
class QrRoundTripTest {

    /** 走一遍 BitMatrix → ARGB 像素 → ZXing 解码，完全绕开 Android。 */
    private fun decodeBack(m: BitMatrix): String {
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (m[x, y]) BLACK else WHITE
            }
        }
        val source = RGBLuminanceSource(w, h, pixels)
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `a real check-in URL survives a full encode-decode round trip`() {
        val url = Sign.buildUrl("10.1.2.3", "260914190149", 1700000000L)
        assertEquals(url, decodeBack(QrEncoder.encode(url, 512)))
    }

    @Test
    fun `percent-encoded IPv6 URLs round trip`() {
        val url = Sign.buildUrl("2001:db8::1", "abc_DEF-123", 1700000000L)
        assertTrue("URL 里应含 %3A", url.contains("%3A"))
        assertEquals(url, decodeBack(QrEncoder.encode(url, 512)))
    }

    @Test
    fun `the URL keeps decoding as the clock advances`() {
        // 网页版每 500ms 重算一次，t 每秒都在变——每种长度都要能编能解
        var t = 1700000000L
        repeat(12) {
            val url = Sign.buildUrl("192.168.31.119", "260914190149", t)
            assertEquals("t=$t 时往返失败", url, decodeBack(QrEncoder.encode(url, 512)))
            t += 5000
        }
    }

    @Test
    fun `round trips at the smallest display size we ever render`() {
        // 180dp @1x 是最小的显示尺寸，这个尺寸下也必须可扫
        val size = QrEncoder.pixelSize(boxWidthDp = 0f, density = 1f)
        assertEquals("夹到下限 180", 180, size)
        val url = Sign.buildUrl("10.1.2.3", "260914190149", 1700000000L)
        assertEquals(url, decodeBack(QrEncoder.encode(url, size)))
    }

    @Test
    fun `pixelSize mirrors the web layout rules`() {
        // 复刻 qrPixelSize(): floor(box-24) 夹到 [180,280]，再乘密度取整
        assertEquals(180, QrEncoder.pixelSize(boxWidthDp = 100f, density = 1f)) // 76 -> 夹到 180
        assertEquals(280, QrEncoder.pixelSize(boxWidthDp = 600f, density = 1f)) // 576 -> 夹到 280
        assertEquals(256, QrEncoder.pixelSize(boxWidthDp = 280f, density = 1f)) // 256 在区间内
        assertEquals(512, QrEncoder.pixelSize(boxWidthDp = 280f, density = 2f)) // 256 * 2
        assertEquals(768, QrEncoder.pixelSize(boxWidthDp = 280f, density = 3f)) // 256 * 3
    }

    @Test
    fun `encode is deterministic for the same input`() {
        val url = Sign.buildUrl("10.1.2.3", "260914190149", 1700000000L)
        val a = QrEncoder.encode(url, 512)
        val b = QrEncoder.encode(url, 512)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                assertEquals("($x,$y) 不一致", a[x, y], b[x, y])
            }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
