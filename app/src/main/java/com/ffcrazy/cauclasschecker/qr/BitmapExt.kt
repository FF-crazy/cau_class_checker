package com.ffcrazy.cauclasschecker.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.common.BitMatrix

/**
 * 把 ZXing 的点阵转成 Android Bitmap。
 *
 * 刻意与 [QrEncoder] 分开：那边保持纯 JVM 可测，这边才碰 Android API。
 */
fun BitMatrix.toBitmap(): Bitmap {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (this[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
