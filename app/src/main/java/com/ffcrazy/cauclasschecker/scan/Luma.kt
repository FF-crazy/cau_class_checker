package com.ffcrazy.cauclasschecker.scan

import java.nio.ByteBuffer

/**
 * 从相机帧里取出亮度（Y）平面。
 *
 * ZXing 只需要亮度信息，所以 U/V 两个平面直接不看——省掉三分之二的拷贝。
 *
 * **这里是最容易出跨机型 bug 的地方**：`ImageProxy` 的 Y 平面**不是**紧凑排列的，
 * `rowStride` 通常大于 `width`（每行末尾有 padding），而某些设备 `pixelStride` 还会大于 1。
 * 直接把整个 buffer 当成 `width * height` 来读，在大多数手机上没事，
 * 在个别机型上会得到一张斜切错位的图 —— 然后二维码怎么都扫不出来。
 *
 * 所以这里做成纯函数（不碰 Android API），好在单测里用合成数据把各种 stride 组合盖住。
 */
object Luma {

    /**
     * 把 Y 平面拷成紧凑的 `width * height` 字节数组。
     *
     * 用绝对下标 `get(index)` 读，不改变 buffer 的 position，也不依赖它当前的位置。
     */
    fun copyYPlane(
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        require(width > 0 && height > 0) { "尺寸必须为正：${width}x$height" }
        require(pixelStride > 0) { "pixelStride 必须为正：$pixelStride" }
        require(rowStride >= width) { "rowStride($rowStride) 不能小于 width($width)" }

        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val rowBase = y * rowStride
            val outBase = y * width
            for (x in 0 until width) {
                out[outBase + x] = buffer.get(rowBase + x * pixelStride)
            }
        }
        return out
    }

    /**
     * 该帧需要的字节数（含 padding）。拿来和 `buffer.remaining()` 比对，
     * 数据不足时宁可放弃这一帧也不要读出越界。
     */
    fun requiredBytes(rowStride: Int, height: Int): Int = rowStride * height
}
