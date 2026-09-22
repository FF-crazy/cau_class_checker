package com.ffcrazy.cauclasschecker.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Y 平面提取的边界测试。
 *
 * 这些合成平面模拟的是真实设备上各种 `rowStride` / `pixelStride` 组合——
 * 出问题的机型平时借不到，只能靠这里提前把坑堵上。
 */
class LumaTest {

    private fun buf(vararg bytes: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `tightly packed plane copies straight through`() {
        // width=4, height=2, rowStride=4, pixelStride=1 —— 无 padding 的理想情况
        val b = buf(1, 2, 3, 4, 5, 6, 7, 8)
        val out = Luma.copyYPlane(b, pixelStride = 1, rowStride = 4, width = 4, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
    }

    @Test
    fun `row padding is stripped`() {
        // 每行 4 个有效字节 + 2 个 padding。若把 buffer 当成紧凑数组直读，
        // 第二行就会整体错位 2 个像素 —— 二维码会变成斜的。
        val b = buf(
            1, 2, 3, 4, 99, 99,
            5, 6, 7, 8, 88, 88,
        )
        val out = Luma.copyYPlane(b, pixelStride = 1, rowStride = 6, width = 4, height = 2)
        assertArrayEquals("padding 必须被丢掉", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
    }

    @Test
    fun `pixel stride greater than one is handled`() {
        // 个别设备 pixelStride > 1，有效像素之间夹杂着要跳过的字节
        val b = buf(1, 0, 2, 0, 3, 0, 4, 0)
        val out = Luma.copyYPlane(b, pixelStride = 2, rowStride = 8, width = 4, height = 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
    }

    @Test
    fun `padding and pixel stride combined`() {
        // width=2, height=2, pixelStride=2, rowStride=8
        val b = buf(
            10, 0, 20, 0, 77, 77, 77, 77,
            30, 0, 40, 0, 66, 66, 66, 66,
        )
        val out = Luma.copyYPlane(b, pixelStride = 2, rowStride = 8, width = 2, height = 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), out)
    }

    @Test
    fun `reading does not depend on buffer position`() {
        // 绝对下标读取，position 被挪过也不影响结果
        val b = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        b.position(3)
        val out = Luma.copyYPlane(b, pixelStride = 1, rowStride = 3, width = 3, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun `requiredBytes accounts for padding`() {
        assertEquals(8, Luma.requiredBytes(rowStride = 4, height = 2))
        assertEquals(12, Luma.requiredBytes(rowStride = 6, height = 2))
    }

    @Test
    fun `rejects nonsensical geometry`() {
        val b = buf(1, 2, 3, 4)
        assertThrows(IllegalArgumentException::class.java) {
            Luma.copyYPlane(b, 1, rowStride = 2, width = 4, height = 1) // rowStride < width
        }
        assertThrows(IllegalArgumentException::class.java) {
            Luma.copyYPlane(b, 1, rowStride = 4, width = 0, height = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Luma.copyYPlane(b, 0, rowStride = 4, width = 4, height = 1) // pixelStride = 0
        }
    }
}
