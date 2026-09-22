package com.ffcrazy.cauclasschecker.web

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersistentCookieJarTest {

    private val casUrl = "https://onecas.cau.edu.cn/tpass/login".toHttpUrl()
    private val svcUrl = "https://class.cau.edu.cn/".toHttpUrl()

    private val castgc = Cookie.Builder()
        .name("CASTGC").value("TGT-123")
        .domain("onecas.cau.edu.cn").path("/tpass").build()

    private val phpSession = Cookie.Builder()
        .name("PHPSESSID").value("abc123")
        .domain("class.cau.edu.cn").path("/").build()

    private fun newJar(): Pair<PersistentCookieJar, File> {
        val f = File.createTempFile("cookies", ".txt")
        f.delete() // 让 PersistentCookieJar 从"文件不存在"的状态开始
        f.deleteOnExit()
        return PersistentCookieJar(f) to f
    }

    @Test
    fun `clearHost removes only the named host`() {
        val (jar, _) = newJar()
        jar.saveFromResponse(casUrl, listOf(castgc))
        jar.saveFromResponse(svcUrl, listOf(phpSession))

        assertTrue("两条都该在", jar.hasCookiesFor("onecas.cau.edu.cn"))
        assertTrue(jar.hasCookiesFor("class.cau.edu.cn"))

        assertTrue("应该确实删掉了东西", jar.clearHost("onecas.cau.edu.cn"))

        assertFalse("CAS 域的 Cookie 应该没了", jar.hasCookiesFor("onecas.cau.edu.cn"))
        assertTrue("业务站点的 Cookie 必须留着", jar.hasCookiesFor("class.cau.edu.cn"))
    }

    @Test
    fun `clearHost on a host with no cookies reports false`() {
        val (jar, _) = newJar()
        jar.saveFromResponse(svcUrl, listOf(phpSession))
        assertFalse(jar.clearHost("onecas.cau.edu.cn"))
    }

    @Test
    fun `clearHost does not touch lookalike domains`() {
        val (jar, _) = newJar()
        val evil = Cookie.Builder()
            .name("X").value("1")
            .domain("notonecas.cau.edu.cn").path("/").build()
        jar.saveFromResponse("https://notonecas.cau.edu.cn/".toHttpUrl(), listOf(evil))

        jar.clearHost("onecas.cau.edu.cn")

        assertTrue(
            "「notonecas.cau.edu.cn」不该被「onecas.cau.edu.cn」匹配到",
            jar.hasCookiesFor("notonecas.cau.edu.cn"),
        )
    }

    @Test
    fun `cookies survive a reload from disk`() {
        val (jar1, file) = newJar()
        jar1.saveFromResponse(casUrl, listOf(castgc))
        jar1.saveFromResponse(svcUrl, listOf(phpSession))

        // 模拟 App 重启：新建一个 jar 读同一个文件
        val jar2 = PersistentCookieJar(file)
        assertTrue("CASTGC 应还在", jar2.hasCookiesFor("onecas.cau.edu.cn"))
        assertTrue("PHPSESSID 应还在", jar2.hasCookiesFor("class.cau.edu.cn"))
        assertEquals(1, jar2.loadForRequest(casUrl).size)
        assertEquals("TGT-123", jar2.loadForRequest(casUrl).single().value)
    }

    @Test
    fun `clearing is persisted to disk`() {
        val (jar1, file) = newJar()
        jar1.saveFromResponse(casUrl, listOf(castgc))
        jar1.clearHost("onecas.cau.edu.cn")

        val jar2 = PersistentCookieJar(file)
        assertFalse("重启后也不该复活", jar2.hasCookiesFor("onecas.cau.edu.cn"))
    }

    @Test
    fun `loadForRequest only returns cookies matching the url`() {
        val (jar, _) = newJar()
        jar.saveFromResponse(casUrl, listOf(castgc))
        jar.saveFromResponse(svcUrl, listOf(phpSession))

        // CASTGC 的 path 是 /tpass，请求 /tpass/login 应该带上；
        // PHPSESSID 属于另一个域，不该串过来
        val forCas = jar.loadForRequest(casUrl).map { it.name }
        assertEquals(listOf("CASTGC"), forCas)
        assertEquals(listOf("PHPSESSID"), jar.loadForRequest(svcUrl).map { it.name })
    }
}
