package com.ffcrazy.cauclasschecker.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录入口 URL 的构造与解析。
 *
 * 这条曾经出过问题：`service` 参数里嵌着完整的目标地址，一旦
 * `HttpUrl` 在解析/重新序列化时把它改写了（比如把 `%3A%2F%2F` 解码成 `://`），
 * 服务端就可能返回不同的页面 —— 而且不会报错，只会表现为"解析不出 lt"。
 *
 * 这里把它钉死。纯字符串断言，不发网络请求，所以不会 flaky。
 */
class CasEntryUrlTest {

    @Test
    fun `service parameter is encoded the way the server expects`() {
        assertEquals(
            "https://onecas.cau.edu.cn/tpass/login?service=https%3A%2F%2Fclass.cau.edu.cn%2F",
            CasLogin.loginUrl(),
        )
    }

    @Test
    fun `parsing the entry url with OkHttp must not rewrite it`() {
        val entry = CasLogin.loginUrl()
        val parsed = entry.toHttpUrl()

        assertEquals("HttpUrl 改写了我们的 URL", entry, parsed.toString())
        assertEquals(
            "service 参数被改写",
            "https://class.cau.edu.cn/",
            parsed.queryParameter("service"),
        )
        assertEquals("service", parsed.queryParameterNames.single())
    }

    @Test
    fun `service points at the site root, never at the check-in page`() {
        // 指向签到页的话，登录要几十秒，等 CAS 送回来时 URL 里的 t 早过期了。
        // 服务器自己跳转时用的也是根地址。
        val service = CasLogin.loginUrl().toHttpUrl().queryParameter("service")!!
        assertTrue("service 不该带时间戳参数", !service.contains("casgeosig.php"))
        assertEquals(CasLogin.SERVICE_ROOT, service)
    }
}
