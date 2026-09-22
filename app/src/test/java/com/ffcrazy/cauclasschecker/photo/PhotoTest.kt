package com.ffcrazy.cauclasschecker.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片处理里**能脱离 Android 跑**的那部分。
 *
 * 抽成纯函数就是为了有这层保险 —— 本项目没有模拟器，真机验证一次的成本是「等到下一节课」。
 */
class PhotoTest {

    // ------------------------------------------------------------------ 缩放

    @Test
    fun `横图按长边缩`() {
        assertEquals(1280 to 960, Photo.fitInside(4000, 3000))
    }

    @Test
    fun `竖图也按长边缩`() {
        assertEquals(960 to 1280, Photo.fitInside(3000, 4000))
    }

    @Test
    fun `本来就够小就原样返回`() {
        assertEquals(640 to 480, Photo.fitInside(640, 480))
        assertEquals("正好等于上限也算够小", 1280 to 720, Photo.fitInside(1280, 720))
    }

    @Test
    fun `缩放后长宽比不变`() {
        val (w, h) = Photo.fitInside(4000, 3000)
        assertEquals("4000:3000 缩完还得是 4:3", 4.0 / 3.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun `极端窄的图不会被缩成 0`() {
        // 宽高只要有一个是 0，Bitmap.createScaledBitmap 就会抛异常，
        // 而这类图片真的会出现（全景截图、1 像素的占位图）
        val (w, h) = Photo.fitInside(10000, 1)
        assertEquals(1280, w)
        assertTrue("高度不能是 0，实际 $h", h >= 1)
    }

    @Test
    fun `尺寸为 0 的图不会崩`() {
        assertEquals(0 to 0, Photo.fitInside(0, 0))
    }

    // ------------------------------------------------------------------ data URL

    @Test
    fun `照片拼成 data URL`() {
        val url = Photo.toDataUrl(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertTrue("前缀必须和实测抓包一致，实际：$url", url.startsWith("data:image/jpeg;base64,"))
        // JPEG 的 SOI + APP0 开头，编码出来就是 "/9j/" ——
        // 线上抓到的 photo 字段正是 data:image/jpeg;base64,/9j/4AAQ...
        assertEquals("data:image/jpeg;base64,/9j/", url)
    }

    @Test
    fun `空照片也能拼出合法前缀`() {
        assertEquals("data:image/jpeg;base64,", Photo.toDataUrl(ByteArray(0)))
    }
}
