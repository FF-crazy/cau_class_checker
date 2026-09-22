package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓下来的登录页**跑解析器。
 *
 * 单元测试里的合成 HTML 是我自己写的，容易不知不觉写成"解析器能处理的样子"。
 * 这个夹具是从线上直接 curl 下来的，能暴露真实结构带来的问题。
 *
 * 注意：页面里的 `lt` 是一次性令牌，抓下来就失效了 —— 但这里只验证**解析**，
 * 不验证令牌有效性，所以没问题。
 */
class CasLoginRealPageTest {

    private val html: String by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
            "$FIXTURE 不在测试资源里"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `fixture actually loaded`() {
        assertTrue("夹具应有上万字节，实际 ${html.length}", html.length > 5000)
        assertTrue("夹具内容应该像登录页", html.contains("统一身份认证"))
    }

    @Test
    fun `finds lt and execution in the real page`() {
        val fields = CasLogin.parseHiddenFields(html)

        // 找不到就打印实际抓到了什么，方便定位
        assertNotNull("解析结果不该为空，实际抓到: $fields", fields)
        assertTrue(
            "没找到 lt。实际抓到的隐藏域: ${fields.keys}",
            !fields["lt"].isNullOrEmpty(),
        )
        assertTrue(
            "没找到 execution。实际抓到的隐藏域: ${fields.keys}",
            !fields["execution"].isNullOrEmpty(),
        )
        assertTrue("lt 应以 LT- 开头，实际 ${fields["lt"]}", fields["lt"]!!.startsWith("LT-"))
        assertTrue("lt 应以 -tpass 结尾，实际 ${fields["lt"]}", fields["lt"]!!.endsWith("-tpass"))
    }

    @Test
    fun `also picks up the other hidden fields the form needs`() {
        val fields = CasLogin.parseHiddenFields(html)
        for (key in listOf("rsa", "ul", "pl", "sl", "_eventId")) {
            assertTrue("应有 $key，实际抓到的: ${fields.keys}", key in fields)
        }
    }

    @Test
    fun `a clean real page has no error message`() {
        // 首次 GET 不该有失败原因
        org.junit.Assert.assertNull(CasLogin.parseErrorMessage(html))
    }

    private companion object {
        const val FIXTURE = "login-page-real.html"
    }
}
