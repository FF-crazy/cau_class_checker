package com.ffcrazy.cauclasschecker.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 复现 App 的登录第一步（GET 登录页）—— 这一步**不需要任何凭据**，
 * 所以可以在这里直接跑真实网络请求来定位问题。
 *
 * 只在排查时手动运行，日常 `testDebugUnitTest` 会连网络不太合适，
 * 但它确实需要一个真实响应才能说明问题。
 */
class CasGetDiagnosticTest {

    private val entry = CasLogin.loginUrl()

    /** OkHttp 会不会把 service 参数重新编码？这是首要嫌疑。 */
    @Test
    fun `toHttpUrl must not mangle the service parameter`() {
        println("原始 URL : $entry")
        val parsed = entry.toHttpUrl()
        println("解析后   : $parsed")
        println("service  : ${parsed.queryParameter("service")}")
        println("原样查询 : ${parsed.encodedQuery}")

        assertEquals("URL 被 OkHttp 改写了，服务端可能因此返回不同的页面", entry, parsed.toString())
        assertEquals(
            "service 参数被改写",
            "https://class.cau.edu.cn/",
            parsed.queryParameter("service"),
        )
    }

    /** 真的发一次 GET，看拿到的到底是什么。 */
    @Test
    fun `real GET returns a page containing lt and execution`() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(entry.toHttpUrl())
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            println("=".repeat(60))
            println("响应码   : ${response.code}")
            println("最终 URL : ${response.request.url}")
            println("Content-Type : ${response.header("Content-Type")}")
            println("响应体长度   : ${body.length}")
            println("正文前 400 字:")
            println(body.take(400))
            println("=".repeat(60))

            val fields = CasLogin.parseHiddenFields(body)
            println("解析出的隐藏域: ${fields.keys}")
            println("lt        = ${fields["lt"]}")
            println("execution = ${fields["execution"]}")

            assertEquals("GET 没拿到 200", 200, response.code)
            org.junit.Assert.assertTrue(
                "响应体里没有 <input —— 拿到的不是登录页",
                body.contains("<input", ignoreCase = true),
            )
            org.junit.Assert.assertTrue(
                "没解析出 lt / execution",
                !fields["lt"].isNullOrEmpty() && !fields["execution"].isNullOrEmpty(),
            )
        }
    }
}
