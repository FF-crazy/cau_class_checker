package com.ffcrazy.cauclasschecker.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 严格模式要的那张照片（见 `domain/SignMode.kt`）。
 *
 * 实测抓包里服务端收的是：
 *
 * ```
 * photo=data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD...
 * ```
 *
 * 也就是 **data URL 形式的 base64 JPEG**，当**普通表单字段**提交 ——
 * Content-Type 仍是 `application/x-www-form-urlencoded`，不是文件上传。
 * 所以这边只要把照片压成 JPEG、拼出 data URL 塞进表单字段就行。
 *
 * 抓包那次整个提交体 33KB、扣掉别的字段约 300 字节 → base64 约 32.6KB，
 * 说明浏览器端压得挺狠（原图约 24KB）。我们用长边 1280 / 质量 80，
 * 比它大一些但远在任何合理的上传限制之内 —— 照片是要给人看的，压太狠了对谁都好不了。
 */
object Photo {

    /** 长边上限。 */
    const val MAX_EDGE = 1280

    /** JPEG 质量。 */
    const val QUALITY = 80

    const val DATA_URL_PREFIX = "data:image/jpeg;base64,"

    /**
     * 拼成提交用的 data URL。
     *
     * 用 `java.util.Base64` 而不是 `android.util.Base64`：前者 API 26 起就有，
     * 而且是**纯 JVM** 的 —— 那样这个函数能直接在单元测试里验，不必起模拟器
     * （本机也根本没有模拟器）。
     */
    fun toDataUrl(jpeg: ByteArray): String =
        DATA_URL_PREFIX + Base64.getEncoder().encodeToString(jpeg)

    /**
     * 等比缩到长边不超过 [maxEdge]；本来就够小就原样返回。
     *
     * 抽成纯函数是为了能测取整 —— 长宽比在奇数尺寸上最容易缩歪。
     */
    fun fitInside(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge || longest == 0) return width to height
        val ratio = maxEdge.toDouble() / longest
        return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
    }

    /**
     * 给相机准备一个空的临时文件，返回它的 `content://` 地址。
     *
     * 放在 cacheDir 而不是相册：这张照片是签到的中间产物，没有理由留在用户的相册里。
     * 走 FileProvider 是因为 `ACTION_IMAGE_CAPTURE` 只接受 `content://`。
     */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        // 顺手清掉上一次拍的 —— 这里永远只需要一张，不然 cache 里会越堆越多
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "sign_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 读 → 缩 → JPEG → data URL。读不出来（不是图片、文件没了）返回 null。
     *
     * 用 `ImageDecoder` 而不是 `BitmapFactory`：相机的照片常带 EXIF 方向标记，
     * `BitmapFactory` 不认它，出来的图会躺倒；`ImageDecoder` 会自动转正。
     * 它同时支持 `setTargetSize`，不必自己算采样率再缩放一轮。
     */
    suspend fun dataUrlFrom(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val decoded = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 软件位图：不占 GPU 内存，也不必担心某些机型上硬件位图不支持的操作
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = fitInside(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
            }
        }.getOrNull() ?: return@withContext null

        val jpeg = ByteArrayOutputStream().use { out ->
            decoded.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        decoded.recycle()

        toDataUrl(jpeg)
    }

    private const val CAPTURE_DIR = "captures"
}
